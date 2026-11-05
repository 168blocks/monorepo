package net.blocks168.pgecs.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.blocks168.pgecs.db.Database
import net.blocks168.pgecs.toSpec
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** The library's own components, registered by every freeze. */
object CoreComponents {
    /** flecs `ChildOf`: exclusive, acyclic, and deleting the parent deletes the children. */
    val CHILD_OF = ComponentSpec(
        name = "pgecs:child-of",
        kind = ComponentKind.RELATION,
        traits = Traits(exclusive = true, acyclic = true, onDeleteTarget = Cleanup.DELETE),
    )

    /** Leases, schedules and observer cursors of systems (net.blocks168.pgecs.system). */
    val SYSTEM: ComponentSpec by lazy { net.blocks168.pgecs.system.SystemComponent.toSpec() }

    val ALL: List<ComponentSpec> by lazy { listOf(CHILD_OF, SYSTEM) }
}

/** Freeze stopped; [problems] says why, one line each. Nothing that was applied is unsafe to keep. */
class FreezeException(val problems: List<String>) :
    IllegalStateException("pgecs freeze failed:\n" + problems.joinToString("\n") { "  - $it" })

/** One catalog row after freeze. */
data class CatalogEntry(
    val name: String,
    val entity: UUID,
    val kind: ComponentKind,
    /** `pending`, `active` or `retired`. */
    val state: String,
    val traits: Traits,
    val jsonSchema: String?,
)

/** What a freeze did. */
data class FreezeReport(
    /** New components, now active. */
    val created: List<String>,
    /** Components whose schema, traits or indexes were extended (or whose interrupted freeze was finished). */
    val upgraded: List<String>,
    val unchanged: List<String>,
    /** Components that the database already has in a newer, compatible form (a rolling deploy); left alone. */
    val newerInDatabase: List<String>,
    /** Components in the database that this process does not register; left alone. */
    val unclaimed: List<String>,
    val retired: List<String>,
    /** Indexes built with `CREATE INDEX CONCURRENTLY`. */
    val indexesBuilt: List<String>,
    /** The whole catalog afterwards, by name. */
    val catalog: Map<String, CatalogEntry>,
)

/**
 * Registry freeze (docs/pgecs.md, "Registry and freeze"): reconciles the database's catalog with the
 * component specs of this process, once at startup.
 *
 * 1. Validates the specs and diffs them against the catalog by fingerprint. When nothing differs, it
 *    returns without locks or writes, so roles without DDL rights can freeze too.
 * 2. Otherwise, on a dedicated connection holding the schema advisory lock: brings the base schema up
 *    to date, then **transaction A** adds new components as `pending`, applies additive schema changes
 *    (checking stored values against them) and records the indexes to build.
 * 3. Builds each index with `CREATE [UNIQUE] INDEX CONCURRENTLY`, outside any transaction, and checks
 *    `pg_index.indisvalid`. An index that failed (duplicate data, crash) is dropped and freeze stops,
 *    naming it and the conflicting entities; new components stay `pending`, which blocks writes to them.
 * 4. **Transaction B** checks existing data against newly added traits, activates traits, marks the
 *    components `active` with their fingerprints, retires components and records the history.
 *
 * Every interruption leaves a state that a rerun picks up. Changes that are not additive (another kind,
 * a removed or retyped field, a dropped trait or index) stop freeze: they need a new component version.
 */
class Freeze(private val db: Database) {

    /**
     * Runs freeze for [specs] (the library's [CoreComponents] are added) and retires the components
     * named in [retire]: they become `retired` and all their rows are deleted.
     */
    @JvmOverloads
    fun run(specs: Collection<ComponentSpec>, retire: Set<String> = emptySet()): FreezeReport {
        val all = CoreComponents.ALL + specs
        validate(specs, retire)

        db.openDedicatedConnection().use { c ->
            if (SchemaSetup.version(c) >= SchemaSetup.LATEST) {
                val plan = plan(c, all, retire)
                if (plan.isNoop) return plan.report(emptyList(), readCatalog(c))
            }
            return SchemaSetup.withSchemaLock(c, db.config.timeouts.schemaLock) {
                SchemaSetup.migrate(c)
                execute(c, plan(c, all, retire))
            }
        }
    }

    // ------------------------------------------------------------------------------------ validation

    private fun validate(specs: Collection<ComponentSpec>, retire: Set<String>) {
        val problems = mutableListOf<String>()
        CoreComponents.ALL.forEach { problems += it.problems(allowReserved = true) }
        specs.forEach { problems += it.problems() }
        val all = CoreComponents.ALL + specs
        all.groupBy { it.name }.filter { it.value.size > 1 }.keys.forEach { problems += "$it: registered more than once" }
        retire.filter { r -> all.any { it.name == r } }.forEach { problems += "$it: both registered and retired" }
        retire.filter { it.startsWith("pgecs:") }.forEach { problems += "$it: library components cannot be retired" }
        all.flatMap { s -> s.allIndexes().map { indexName(s.name, it) to s.name } }
            .groupBy({ it.first }, { it.second }).filter { it.value.size > 1 }
            .forEach { (n, owners) -> problems += "index name $n is derived for several indexes (${owners.joinToString()}); rename a key" }
        if (problems.isNotEmpty()) throw FreezeException(problems)
    }

    // ------------------------------------------------------------------------------------ state

    private class DbIndex(val name: String, val component: String, val spec: IndexSpec, val state: String, val physicallyValid: Boolean?)

    private class DbComponent(
        val name: String,
        val entity: UUID,
        val kind: ComponentKind,
        val state: String,
        val traits: Traits,
        val schema: String?,
        val fingerprint: String,
        val indexes: Map<String, DbIndex>,
    ) {
        /** The catalog row as a spec, for comparing with the registry. */
        fun asSpec() = ComponentSpec(
            name = name,
            kind = kind,
            traits = traits,
            jsonSchema = schema,
            indexes = indexes.values.map { it.spec }.filter { it.key != IndexSpec.EXCLUSIVE_KEY },
        )
    }

    private fun readComponents(c: Connection): Map<String, DbComponent> {
        val indexes = mutableMapOf<String, MutableMap<String, DbIndex>>()
        c.createStatement().use { st ->
            st.executeQuery(
                """
                select s.name, s.component, s.spec::text, s.state, i.indisvalid
                  from ecs_schema_index s
                  left join pg_class ic on ic.relname = s.name and ic.relnamespace = (
                         select relnamespace from pg_class where oid = 'ecs_component'::regclass)
                  left join pg_index i on i.indexrelid = ic.oid
                """.trimIndent(),
            ).use { rs ->
                while (rs.next()) {
                    val spec = Json.parseToJsonElement(rs.getString(3)) as JsonObject
                    val key = (spec["key"] as JsonPrimitive).content
                    val valid = rs.getBoolean(5).takeUnless { rs.wasNull() }
                    indexes.getOrPut(rs.getString(2)) { mutableMapOf() }[key] =
                        DbIndex(rs.getString(1), rs.getString(2), IndexSpec.fromJson(key, spec), rs.getString(4), valid)
                }
            }
            return st.executeQuery(
                "select name, entity, kind, state, traits::text, json_schema::text, fingerprint from ecs_component_type",
            ).use { rs ->
                buildMap {
                    while (rs.next()) {
                        val name = rs.getString(1)
                        put(
                            name,
                            DbComponent(
                                name = name,
                                entity = rs.getObject(2, UUID::class.java),
                                kind = ComponentKind.ofSql(rs.getString(3)),
                                state = rs.getString(4),
                                traits = Traits.fromJson(Json.parseToJsonElement(rs.getString(5)) as JsonObject),
                                schema = rs.getString(6),
                                fingerprint = rs.getString(7),
                                indexes = indexes[name].orEmpty(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun readCatalog(c: Connection): Map<String, CatalogEntry> = readComponents(c).mapValues { (_, d) ->
        CatalogEntry(d.name, d.entity, d.kind, d.state, d.traits, d.schema)
    }

    // ------------------------------------------------------------------------------------ plan

    private class IndexWork(val component: String, val spec: IndexSpec, val name: String, val ddl: String)

    private class Plan(
        val create: List<ComponentSpec>,
        val upgrade: List<ComponentSpec>,
        /** Upgrades whose JSON Schema changed. */
        val schemaChanged: Set<String>,
        /** Per upgraded component, the traits it gains (checked against data in transaction B). */
        val addedTraits: Map<String, Traits>,
        val indexes: List<IndexWork>,
        val retire: List<String>,
        val unchanged: List<String>,
        val newerInDatabase: List<String>,
        val unclaimed: List<String>,
    ) {
        val isNoop get() = create.isEmpty() && upgrade.isEmpty() && indexes.isEmpty() && retire.isEmpty()

        fun report(built: List<String>, catalog: Map<String, CatalogEntry>) = FreezeReport(
            created = create.map { it.name },
            upgraded = upgrade.map { it.name },
            unchanged = unchanged,
            newerInDatabase = newerInDatabase,
            unclaimed = unclaimed,
            retired = retire,
            indexesBuilt = built,
            catalog = catalog,
        )
    }

    private fun plan(c: Connection, specs: List<ComponentSpec>, retire: Set<String>): Plan {
        val dbState = if (SchemaSetup.version(c) == 0) emptyMap() else readComponents(c)
        val problems = mutableListOf<String>()
        val create = mutableListOf<ComponentSpec>()
        val upgrade = mutableListOf<ComponentSpec>()
        val schemaChanged = mutableSetOf<String>()
        val addedTraits = mutableMapOf<String, Traits>()
        val indexWork = mutableListOf<IndexWork>()
        val unchanged = mutableListOf<String>()
        val newer = mutableListOf<String>()
        val dbIndexOwners = dbState.values.flatMap { d -> d.indexes.values }.associateBy { it.name }

        fun indexesToBuild(spec: ComponentSpec, existing: DbComponent?) = spec.allIndexes().mapNotNull { idx ->
            val name = indexName(spec.name, idx)
            val owner = dbIndexOwners[name]
            if (owner != null && (owner.component != spec.name || owner.spec.key != idx.key)) {
                problems += "${spec.name}: index name $name already belongs to ${owner.component} (${owner.spec.key})"
            }
            val have = existing?.indexes?.get(idx.key)
            if (have != null && have.state == "valid" && have.physicallyValid == true) null
            else IndexWork(spec.name, idx, name, ddl(spec.name, idx, name))
        }

        for (spec in specs) {
            val d = dbState[spec.name]
            if (d == null) {
                create += spec
                indexWork += indexesToBuild(spec, null)
                continue
            }
            if (d.state == "retired") {
                problems += "${spec.name}: retired in the database; register a new version instead"
                continue
            }
            val current = d.asSpec()
            val toBuild = indexesToBuild(spec, d)
            val sameContent = whyNotExtension(spec, current).isEmpty() && whyNotExtension(current, spec).isEmpty()
            when {
                sameContent && d.state == "active" && d.fingerprint == spec.fingerprint() && toBuild.isEmpty() ->
                    unchanged += spec.name
                sameContent -> { // an earlier freeze was interrupted, or an index went missing
                    upgrade += spec
                    indexWork += toBuild
                    addedTraits[spec.name] = Traits()
                }
                whyNotExtension(current, spec).isEmpty() -> newer += spec.name
                else -> {
                    val why = whyNotExtension(spec, current)
                    if (why.isNotEmpty()) {
                        problems += "${spec.name}: not an additive change of the stored component (${why.joinToString("; ")}); " +
                            "register it under a new version name"
                        continue
                    }
                    upgrade += spec
                    indexWork += toBuild
                    if (spec.canonicalSchema() != current.canonicalSchema()) schemaChanged += spec.name
                    addedTraits[spec.name] = Traits(
                        acyclic = spec.traits.acyclic && !d.traits.acyclic,
                        symmetric = spec.traits.symmetric && !d.traits.symmetric,
                        oneOf = spec.traits.oneOf.takeIf { d.traits.oneOf == null },
                    )
                }
            }
        }
        if (problems.isNotEmpty()) throw FreezeException(problems)

        val registered = specs.map { it.name }.toSet()
        return Plan(
            create = create,
            upgrade = upgrade,
            schemaChanged = schemaChanged,
            addedTraits = addedTraits,
            indexes = indexWork,
            retire = retire.filter { dbState[it] != null && dbState[it]!!.state != "retired" }.sorted(),
            unchanged = unchanged,
            newerInDatabase = newer,
            unclaimed = dbState.keys.filter { it !in registered && it !in retire && dbState[it]!!.state != "retired" }.sorted(),
        )
    }

    // ------------------------------------------------------------------------------------ execute

    private fun execute(c: Connection, plan: Plan): FreezeReport {
        if (plan.isNoop) return plan.report(emptyList(), readCatalog(c))

        // Transaction A: new components (pending), additive schema changes, indexes to build
        SchemaSetup.inSerializableTx(c) {
            freezeMode(c)
            val problems = mutableListOf<String>()
            for (spec in plan.create + plan.upgrade.filter { it.name in plan.schemaChanged }) {
                val schema = spec.jsonSchema ?: continue
                if (!queryBool(c, "select jsonschema_is_valid(?::json)", schema)) problems += "${spec.name}: not a valid JSON Schema"
            }
            if (problems.isNotEmpty()) throw FreezeException(problems)
            for (spec in plan.create) {
                c.prepareStatement("insert into ecs_entity (id) values (?) on conflict do nothing").use {
                    it.setObject(1, spec.entity); it.executeUpdate()
                }
                c.prepareStatement(
                    "insert into ecs_component_type (name, entity, kind, state, traits, json_schema, fingerprint) " +
                        "values (?, ?, ?, 'pending', '{}', ?::jsonb, '')",
                ).use {
                    it.setString(1, spec.name); it.setObject(2, spec.entity); it.setString(3, spec.kind.sql)
                    it.setString(4, spec.jsonSchema); it.executeUpdate()
                }
            }
            for (spec in plan.upgrade.filter { it.name in plan.schemaChanged }) {
                val bad = c.prepareStatement(
                    "select entity from ecs_component where rel = ? and value is not null " +
                        "and not jsonb_matches_schema(?::json, value) limit 5",
                ).use {
                    it.setString(1, spec.name); it.setString(2, spec.jsonSchema)
                    it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                }
                if (bad.isNotEmpty()) problems += "${spec.name}: stored values do not match the new JSON Schema (entities ${bad.joinToString()})"
                c.prepareStatement("update ecs_component_type set json_schema = ?::jsonb where name = ?").use {
                    it.setString(1, spec.jsonSchema); it.setString(2, spec.name); it.executeUpdate()
                }
            }
            if (problems.isNotEmpty()) throw FreezeException(problems)
            for (w in plan.indexes) {
                c.prepareStatement(
                    """
                    insert into ecs_schema_index (name, component, spec, ddl, state)
                    values (?, ?, ?::jsonb, ?, 'pending')
                    on conflict (name) do update
                      set spec = excluded.spec, ddl = excluded.ddl, state = 'pending', error = null, updated_at = now()
                    """.trimIndent(),
                ).use {
                    it.setString(1, w.name); it.setString(2, w.component); it.setString(3, storedSpec(w.spec).toString())
                    it.setString(4, w.ddl); it.executeUpdate()
                }
            }
        }

        // Outside any transaction: build the indexes concurrently
        val failures = mutableListOf<String>()
        val built = mutableListOf<String>()
        for (w in plan.indexes) {
            dropIfInvalid(c, w.name)
            val error = try {
                c.createStatement().use { it.execute(w.ddl) }
                if (isValid(c, w.name)) null else "index is invalid after the build"
            } catch (e: SQLException) {
                "${e.message} (SQLSTATE ${e.sqlState})"
            }
            if (error == null) {
                built += w.name
                continue
            }
            dropIfInvalid(c, w.name)
            val conflicts = if (w.spec.unique) duplicates(c, w) else ""
            failures += "${w.component}: index ${w.name} failed: $error$conflicts"
            SchemaSetup.inSerializableTx(c) {
                freezeMode(c)
                c.prepareStatement("update ecs_schema_index set state = 'failed', error = ?, updated_at = now() where name = ?").use {
                    it.setString(1, error + conflicts); it.setString(2, w.name); it.executeUpdate()
                }
            }
        }
        if (failures.isNotEmpty()) throw FreezeException(failures)

        // Transaction B: check data against new traits, activate, retire, record
        SchemaSetup.inSerializableTx(c) {
            freezeMode(c)
            val problems = mutableListOf<String>()
            for ((name, added) in plan.addedTraits) problems += checkTraits(c, name, added)
            if (problems.isNotEmpty()) throw FreezeException(problems)
            for (spec in plan.create + plan.upgrade) {
                c.prepareStatement(
                    "update ecs_component_type set traits = ?::jsonb, state = 'active', fingerprint = ? where name = ?",
                ).use {
                    it.setString(1, spec.traits.toJson().toString()); it.setString(2, spec.fingerprint()); it.setString(3, spec.name)
                    it.executeUpdate()
                }
            }
            c.prepareStatement("update ecs_schema_index set state = 'valid', error = null, updated_at = now() where name = any(?)").use {
                it.setArray(1, c.createArrayOf("text", plan.indexes.map { w -> w.name }.toTypedArray())); it.executeUpdate()
            }
            val removedRows = mutableMapOf<String, Int>()
            for (name in plan.retire) {
                c.prepareStatement("update ecs_component_type set state = 'retired' where name = ?").use {
                    it.setString(1, name); it.executeUpdate()
                }
                removedRows[name] = c.prepareStatement("delete from ecs_component where rel = ?").use {
                    it.setString(1, name); it.executeUpdate()
                }
            }
            val changes = buildJsonObject {
                put("created", strings(plan.create.map { it.name }))
                put("upgraded", strings(plan.upgrade.map { it.name }))
                put("indexes", strings(built))
                put("retired", JsonObject(removedRows.mapValues { JsonPrimitive(it.value) }))
                put("fingerprints", JsonObject((plan.create + plan.upgrade).associate { it.name to JsonPrimitive(it.fingerprint()) }))
            }
            c.prepareStatement("insert into ecs_schema_history (changes) values (?::jsonb)").use {
                it.setString(1, changes.toString()); it.executeUpdate()
            }
        }
        return plan.report(built, readCatalog(c))
    }

    private fun freezeMode(c: Connection) {
        c.createStatement().use { it.execute("set local pgecs.in_freeze = 'on'") }
    }

    /** Existing pairs that violate traits a relation gains. */
    private fun checkTraits(c: Connection, rel: String, added: Traits): List<String> = buildList {
        if (added.acyclic) {
            val cyclic = c.prepareStatement(
                """
                with recursive walk(start, cur) as (
                  select entity, target from ecs_component where rel = ?
                  union
                  select w.start, e.target from walk w join ecs_component e on e.entity = w.cur and e.rel = ?
                )
                select start from walk where start = cur limit 5
                """.trimIndent(),
            ).use {
                it.setString(1, rel); it.setString(2, rel)
                it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            if (cyclic.isNotEmpty()) add("$rel: cannot become acyclic, entities ${cyclic.joinToString()} are on cycles")
        }
        if (added.symmetric) {
            val oneSided = c.prepareStatement(
                """
                select a.entity from ecs_component a
                 where a.rel = ? and a.entity <> a.target and not exists (
                   select 1 from ecs_component b where b.entity = a.target and b.rel = a.rel and b.target = a.entity)
                 limit 5
                """.trimIndent(),
            ).use {
                it.setString(1, rel)
                it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            if (oneSided.isNotEmpty()) add("$rel: cannot become symmetric, pairs on ${oneSided.joinToString()} have no reverse")
        }
        if (added.oneOf != null) {
            val outside = c.prepareStatement(
                """
                select a.entity from ecs_component a
                 where a.rel = ? and not exists (
                   select 1 from ecs_component p where p.entity = a.target and p.rel = 'pgecs:child-of' and p.target = ?)
                 limit 5
                """.trimIndent(),
            ).use {
                it.setString(1, rel); it.setObject(2, added.oneOf)
                it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            if (outside.isNotEmpty()) add("$rel: cannot get oneOf ${added.oneOf}, pairs on ${outside.joinToString()} target other entities")
        }
    }

    private fun isValid(c: Connection, name: String): Boolean = c.prepareStatement(
        """
        select i.indisvalid from pg_class ic join pg_index i on i.indexrelid = ic.oid
         where ic.relname = ? and ic.relnamespace = (select relnamespace from pg_class where oid = 'ecs_component'::regclass)
        """.trimIndent(),
    ).use {
        it.setString(1, name)
        it.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
    }

    private fun dropIfInvalid(c: Connection, name: String) {
        val exists = c.prepareStatement(
            "select 1 from pg_class where relname = ? and relnamespace = (select relnamespace from pg_class where oid = 'ecs_component'::regclass)",
        ).use { it.setString(1, name); it.executeQuery().use { rs -> rs.next() } }
        if (exists && !isValid(c, name)) c.createStatement().use { it.execute("drop index concurrently if exists $name") }
    }

    /** For a failed unique index: which entities share a key. */
    private fun duplicates(c: Connection, w: IndexWork): String = runCatching {
        val sql = "select array_agg(entity::text order by entity) from ecs_component where rel = ${literal(w.component)}" +
            (w.spec.where?.let { " and ($it)" } ?: "") +
            " group by ${w.spec.expressions.joinToString(", ")} having count(*) > 1 limit 5"
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val groups = buildList { while (rs.next()) add(rs.getString(1)) }
                if (groups.isEmpty()) "" else "; entities sharing a key: ${groups.joinToString(" ")}"
            }
        }
    }.getOrDefault("")

    private fun queryBool(c: Connection, sql: String, arg: String): Boolean = c.prepareStatement(sql).use {
        it.setString(1, arg)
        it.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
    }

    private fun strings(xs: List<String>) = buildJsonArray { xs.forEach { add(JsonPrimitive(it)) } }

    companion object {
        /** Name of the index for [idx] of [component]: `ecs_<x|u|g|i>_<component>__<key>`, at most 63 bytes. */
        @JvmStatic
        fun indexName(component: String, idx: IndexSpec): String {
            val prefix = when {
                idx.key == IndexSpec.EXCLUSIVE_KEY -> "ecs_x_"
                idx.unique -> "ecs_u_"
                idx.method == IndexMethod.GIN -> "ecs_g_"
                else -> "ecs_i_"
            }
            val slug = "${component}__${idx.key}".lowercase().replace(Regex("[^a-z0-9_]+"), "_")
            if (prefix.length + slug.length <= 63) return prefix + slug
            val hash = MessageDigest.getInstance("SHA-256").digest("$component\u0000${idx.key}".toByteArray())
                .take(4).joinToString("") { "%02x".format(it) }
            return prefix + slug.take(63 - prefix.length - 9) + "_" + hash
        }

        /** The `CREATE INDEX CONCURRENTLY` statement freeze runs for [idx]. */
        @JvmStatic
        fun ddl(component: String, idx: IndexSpec, name: String = indexName(component, idx)): String = buildString {
            append("create ")
            if (idx.unique) append("unique ")
            append("index concurrently if not exists ").append(name)
            append(" on ecs_component using ").append(idx.method.sql)
            append(" (").append(idx.expressions.joinToString(", ")).append(")")
            append(" where rel = ").append(literal(component))
            idx.where?.let { append(" and (").append(it).append(")") }
        }

        private fun literal(s: String) = "'" + s.replace("'", "''") + "'"

        private fun storedSpec(idx: IndexSpec): JsonObject = JsonObject(mapOf("key" to JsonPrimitive(idx.key)) + idx.toJson())

        /**
         * Why [new] is not an additive extension of [old] (empty when it is): same kind, no trait dropped
         * or changed, every old index kept as it was, and a JSON Schema that only adds optional properties.
         */
        internal fun whyNotExtension(new: ComponentSpec, old: ComponentSpec): List<String> = buildList {
            if (new.kind != old.kind) add("kind ${old.kind.sql} -> ${new.kind.sql}")
            val (n, o) = new.traits to old.traits
            if (o.exclusive && !n.exclusive) add("drops exclusive")
            if (o.acyclic && !n.acyclic) add("drops acyclic")
            if (o.symmetric && !n.symmetric) add("drops symmetric")
            if (o.oneOf != null && n.oneOf != o.oneOf) add("changes oneOf")
            if (n.onDeleteTarget != o.onDeleteTarget) add("onDeleteTarget ${o.onDeleteTarget.sql} -> ${n.onDeleteTarget.sql}")
            val (ns, os) = new.canonicalSchema() to old.canonicalSchema()
            when {
                ns == null && os == null -> {}
                ns == null -> add("removes the JSON Schema")
                os == null -> add("adds a JSON Schema")
                else -> addAll(JsonSchemas.whyNotExtension(ns, os, "$"))
            }
            val newIndexes = new.indexes.associateBy { it.key }
            for (idx in old.indexes) {
                val ni = newIndexes[idx.key]
                if (ni == null) add("drops index ${idx.key}") else if (ni != idx) add("changes index ${idx.key}")
            }
        }
    }
}

/** The additive-change rule for JSON Schemas (docs/pgecs.md, "Safety rules for the diff"). */
internal object JsonSchemas {
    private val STRUCTURAL = setOf("properties", "required")

    /**
     * Why [new] is not an additive extension of [old] at [path]: the same keywords, the same required
     * fields, every old property kept with an additive schema, and only optional new properties.
     */
    fun whyNotExtension(new: JsonElement, old: JsonElement, path: String): List<String> {
        if (new == old) return emptyList()
        if (new !is JsonObject || old !is JsonObject) return listOf("$path changed")
        if (new.filterKeys { it !in STRUCTURAL } != old.filterKeys { it !in STRUCTURAL }) {
            return listOf("$path changed beyond adding optional properties")
        }
        val required = { o: JsonObject -> (o["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet().orEmpty() }
        if (required(new) != required(old)) return listOf("$path: required fields changed")
        val newProps = new["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val oldProps = old["properties"] as? JsonObject ?: JsonObject(emptyMap())
        return oldProps.flatMap { (k, v) ->
            val nv = newProps[k] ?: return@flatMap listOf("$path.$k removed")
            whyNotExtension(nv, v, "$path.$k")
        }
    }
}
