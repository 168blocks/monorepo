package net.blocks168.pgecs.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID

/**
 * What freeze needs to know about one registered component, relation, tag or capability: plain data,
 * so the component handles (DSL) and the code that derives JSON Schemas and index expressions stay
 * independent of the Postgres side.
 */
data class ComponentSpec(
    /** `<project>:<namespace(s)>:<name>[:<version>]`, e.g. `b8:iam:email:v1`. */
    val name: String,
    val kind: ComponentKind,
    /** Relation traits; must be the default for other kinds. */
    val traits: Traits = Traits(),
    /**
     * JSON Schema of the stored value, as JSON text. Required for [ComponentKind.VALUE] and
     * [ComponentKind.CAPABILITY] (the payload); optional for relations (a relation with a schema
     * carries a value on every pair); not allowed for tags.
     */
    val jsonSchema: String? = null,
    /** Per-component indexes. [Traits.exclusive] adds its own unique index. */
    val indexes: List<IndexSpec> = emptyList(),
) {
    /** UUIDv5 of [name]: the component's entity id, the same in every process. */
    val entity: UUID get() = ComponentIds.of(name)

    /** Problems with this spec, empty when it is valid. [allowReserved]: may use the `pgecs:` namespace. */
    fun problems(allowReserved: Boolean = false): List<String> = buildList {
        if (!NAME.matches(name)) add("$name: not a valid component name (colon-separated segments, no whitespace or quotes)")
        if (!allowReserved && name.startsWith("pgecs:")) add("$name: the pgecs: namespace is reserved for the library")
        if (kind != ComponentKind.RELATION && traits != Traits()) add("$name: only relations have traits")
        when (kind) {
            ComponentKind.TAG -> if (jsonSchema != null) add("$name: a tag has no JSON Schema")
            ComponentKind.VALUE, ComponentKind.CAPABILITY -> if (jsonSchema == null) add("$name: needs a JSON Schema")
            ComponentKind.RELATION -> {}
        }
        if (jsonSchema != null) {
            runCatching { Json.parseToJsonElement(jsonSchema) }.onFailure { add("$name: JSON Schema is not valid JSON: ${it.message}") }
        }
        if (kind == ComponentKind.CAPABILITY && indexes.isNotEmpty()) add("$name: capabilities have no indexes")
        val keys = allIndexes().map { it.key }
        keys.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { add("$name: index key '$it' is declared twice") }
        indexes.forEach { idx -> idx.problems().forEach { add("$name: index ${idx.key}: $it") } }
        if (indexes.any { it.key == IndexSpec.EXCLUSIVE_KEY }) add("$name: index key '${IndexSpec.EXCLUSIVE_KEY}' is reserved for the exclusive trait")
    }

    /** [indexes] plus the ones implied by [traits]. */
    fun allIndexes(): List<IndexSpec> =
        if (traits.exclusive) indexes + IndexSpec(IndexSpec.EXCLUSIVE_KEY, listOf("entity"), unique = true) else indexes

    internal fun canonicalSchema(): JsonElement? = jsonSchema?.let { canonical(Json.parseToJsonElement(it)) }

    /** Hash over everything freeze applies; equal fingerprints mean nothing to do. */
    fun fingerprint(): String {
        val doc = buildJsonObject {
            put("kind", kind.sql)
            put("traits", traits.toJson())
            put("schema", canonicalSchema() ?: kotlinx.serialization.json.JsonNull)
            put("indexes", JsonObject(allIndexes().sortedBy { it.key }.associate { it.key to it.toJson() }))
        }
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical(doc).toString().toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        val NAME = Regex("""^[^:\s'"\\]+(:[^:\s'"\\]+)+$""")

        /** JSON with object keys sorted, recursively, so equal documents serialize equally. */
        internal fun canonical(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
            is JsonArray -> JsonArray(e.map(::canonical))
            else -> e
        }
    }
}

enum class ComponentKind(val sql: String) {
    /** No value, no target. */
    TAG("tag"),
    /** A JSON value, no target. */
    VALUE("value"),
    /** Pairs `(relation, target)`, optionally with a value. */
    RELATION("relation"),
    /** An outbox capability; its schema describes the payload. Never stored as a component row. */
    CAPABILITY("capability"),
    ;

    companion object {
        fun ofSql(s: String) = entries.first { it.sql == s }
    }
}

/** What happens to pairs `(R, x)` when their target `x` is deleted (flecs `OnDeleteTarget`). */
enum class Cleanup(val sql: String) {
    /** Remove the pair (the default). */
    REMOVE("remove"),
    /** Delete the entities holding the pair, as for `pgecs:child-of`. */
    DELETE("delete"),
    /** Refuse to delete the target while such pairs exist. */
    PANIC("panic"),
    ;

    companion object {
        fun ofSql(s: String) = entries.first { it.sql == s }
    }
}

/** Relation traits, with flecs semantics (docs/pgecs.md, "Flecs concepts mapped"). */
data class Traits(
    /** At most one pair of this relation per entity (partial unique index on `entity`). */
    val exclusive: Boolean = false,
    /** Pairs never form a cycle. */
    val acyclic: Boolean = false,
    /** `(R, b)` on `a` implies `(R, a)` on `b`. */
    val symmetric: Boolean = false,
    /** Targets must be children (`pgecs:child-of`) of this entity. */
    val oneOf: UUID? = null,
    val onDeleteTarget: Cleanup = Cleanup.REMOVE,
) {
    /** As stored in `ecs_component_type.traits`; defaults are left out. */
    fun toJson(): JsonObject = buildJsonObject {
        if (exclusive) put("exclusive", true)
        if (acyclic) put("acyclic", true)
        if (symmetric) put("symmetric", true)
        if (oneOf != null) put("oneOf", oneOf.toString())
        if (onDeleteTarget != Cleanup.REMOVE) put("onDeleteTarget", onDeleteTarget.sql)
    }

    companion object {
        fun fromJson(o: JsonObject): Traits = Traits(
            exclusive = o.bool("exclusive"),
            acyclic = o.bool("acyclic"),
            symmetric = o.bool("symmetric"),
            oneOf = (o["oneOf"] as? JsonPrimitive)?.content?.let(UUID::fromString),
            onDeleteTarget = (o["onDeleteTarget"] as? JsonPrimitive)?.content?.let(Cleanup::ofSql) ?: Cleanup.REMOVE,
        )

        private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.content == "true"
    }
}

enum class IndexMethod(val sql: String) { BTREE("btree"), GIN("gin"), GIST("gist"), HASH("hash") }

/**
 * One per-component index on `ecs_component`, always partial on `rel = '<component>'`.
 *
 * [expressions] are PostgreSQL index elements over the `ecs_component` columns, written exactly as in
 * `CREATE INDEX` (parenthesize expressions, add operator classes): `(lower(value->>'address'))`,
 * `((value::bigint))`, `entity`, `value jsonb_path_ops`. The query compiler emits the same SQL text for
 * the predicate, so Postgres matches the index. [where] narrows the index further.
 */
data class IndexSpec(
    /** Short name of the index within its component: lower case letters, digits and `_`. */
    val key: String,
    val expressions: List<String>,
    val unique: Boolean = false,
    val method: IndexMethod = IndexMethod.BTREE,
    val where: String? = null,
) {
    fun problems(): List<String> = buildList {
        if (!KEY.matches(key)) add("key must match ${KEY.pattern}")
        if (expressions.isEmpty()) add("no expressions")
        (expressions + listOfNotNull(where)).filter { ';' in it }.forEach { add("';' in '$it'") }
        if (unique && method != IndexMethod.BTREE) add("only btree indexes can be unique")
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("unique", unique)
        put("method", method.sql)
        put("expressions", JsonArray(expressions.map(::JsonPrimitive)))
        where?.let { put("where", it) }
    }

    companion object {
        val KEY = Regex("^[a-z0-9_]{1,40}$")
        const val EXCLUSIVE_KEY = "exclusive"

        fun fromJson(key: String, o: JsonObject) = IndexSpec(
            key = key,
            expressions = (o["expressions"] as JsonArray).map { (it as JsonPrimitive).content },
            unique = (o["unique"] as JsonPrimitive).content == "true",
            method = IndexMethod.entries.first { it.sql == (o["method"] as JsonPrimitive).content },
            where = (o["where"] as? JsonPrimitive)?.content,
        )
    }
}

/** Component entity ids: UUIDv5 of the component name in the pgecs namespace. */
object ComponentIds {
    private val URL_NAMESPACE: UUID = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

    /** The pgecs namespace, itself UUIDv5 of `https://168blocks.net/pgecs` in the URL namespace. */
    val NAMESPACE: UUID = uuid5(URL_NAMESPACE, "https://168blocks.net/pgecs")

    @JvmStatic
    fun of(name: String): UUID = uuid5(NAMESPACE, name)

    /** RFC 9562 UUIDv5 (SHA-1, name based). */
    @JvmStatic
    fun uuid5(namespace: UUID, name: String): UUID {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(java.nio.ByteBuffer.allocate(16).putLong(namespace.mostSignificantBits).putLong(namespace.leastSignificantBits).array())
        val h = sha1.digest(name.toByteArray(Charsets.UTF_8))
        h[6] = ((h[6].toInt() and 0x0f) or 0x50).toByte()
        h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte()
        val bb = java.nio.ByteBuffer.wrap(h, 0, 16)
        return UUID(bb.long, bb.long)
    }
}
