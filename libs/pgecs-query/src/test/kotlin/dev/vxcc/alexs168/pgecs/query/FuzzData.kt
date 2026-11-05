package dev.vxcc.alexs168.pgecs.query

import dev.vxcc.alexs168.pgecs.ir.ComponentKind
import dev.vxcc.alexs168.pgecs.ir.ComponentRef
import dev.vxcc.alexs168.pgecs.ir.ValueType
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * The component types the random query tests use, and the value pools both the data and the
 * generated queries draw from (so that comparisons hit often enough).
 */
object FuzzSchema {
    val tags = listOf("t:a", "t:b", "t:c").map { ComponentRef("b8:$it", ComponentKind.TAG) }
    val rels = listOf("r:r", "r:q").map { ComponentRef("b8:$it", ComponentKind.RELATION) }
    val can = ComponentRef("b8:r:can", ComponentKind.RELATION)
    val capabilities = listOf("b8:cap:x", "b8:cap:y")
    val score = ComponentRef("b8:v:score", ComponentKind.VALUE, ValueType.INT)
    val at = ComponentRef("b8:v:at", ComponentKind.VALUE, ValueType.INSTANT)

    /** JSON objects `{"name": text, "n": int?, "at": instant?, "ref": uuid?, "meta": {"k": int}?}`; keys may be missing. */
    val json = ComponentRef("b8:v:json", ComponentKind.VALUE, ValueType.JSON)

    /**
     * Text values: case, whitespace that Kotlin's `trim` removes, prefixes of each other, and
     * characters outside ASCII and the BMP (lower/upper case and UTF-16 length differ there).
     */
    val names = listOf("ann", "Ann", "ANN", "bob", "bobby", "c", "", " ann ", "\tbob\n", "Ärger", "straße", "İz", "😀x", "\u00a0c\u2003", "ΣΑΣ")

    /** Instants far from now in both directions, so `now()` comparisons are deterministic, and close to each other. */
    val instants: List<Instant> = listOf(
        Instant.parse("2001-01-01T00:00:00Z"),
        Instant.parse("2001-01-01T00:00:00.001Z"),
        Instant.parse("2001-01-01T00:01:00Z"),
        Instant.parse("2101-01-01T00:00:00Z"),
        Instant.parse("2101-01-01T00:00:01Z"),
    )
}

/**
 * Random data for one dataset: written to a [TestDb] schema of its own and to an [Oracle]. Small
 * value ranges, so equal values, ties and empty groups happen.
 */
class FuzzData(seed: Long) : AutoCloseable {
    val db = TestDb("fuzz")
    val entities: List<UUID>
    val oracle: Oracle

    init {
        val s = FuzzSchema
        val rnd = Random(seed)
        s.tags.forEach { db.type(it.name) }
        (s.rels + s.can).forEach { db.type(it.name, "relation") }
        listOf(s.score, s.at, s.json).forEach { db.type(it.name, "value") }
        val typeEntities = s.capabilities.associateWith { db.type(it, "capability") }

        val rows = mutableListOf<Oracle.Row>()
        val nil = UUID(0, 0)
        entities = List(4 + rnd.nextInt(10)) { db.entity() }
        val tagP = rnd.nextInt(2, 5)
        val relP = rnd.nextInt(3, 8)
        for (e in entities) {
            for (t in s.tags) if (rnd.nextInt(tagP) == 0) { db.add(e, t.name); rows += Oracle.Row(e, t.name, nil, null) }
            for (r in s.rels) for (t in entities) if (rnd.nextInt(relP) == 0) { db.add(e, r.name, t); rows += Oracle.Row(e, r.name, t, null) }
            for (c in s.capabilities) if (rnd.nextInt(4) == 0) { db.add(e, s.can.name, typeEntities.getValue(c)); rows += Oracle.Row(e, s.can.name, typeEntities.getValue(c), null) }
            if (rnd.nextInt(3) != 0) {
                val v = rnd.nextLong(-3, 4)
                db.add(e, s.score.name, json = "$v")
                rows += Oracle.Row(e, s.score.name, nil, v)
            }
            if (rnd.nextInt(3) != 0) {
                val v = s.instants.random(rnd).toEpochMilli()
                db.add(e, s.at.name, json = "$v")
                rows += Oracle.Row(e, s.at.name, nil, v)
            }
            if (rnd.nextInt(3) != 0) {
                val obj = LinkedHashMap<String, Any?>()
                obj["name"] = s.names.random(rnd)
                // each optional key: missing, null or a value
                fun maybe(key: String, value: () -> Any) {
                    when (rnd.nextInt(3)) {
                        0 -> {}
                        1 -> obj[key] = null
                        else -> obj[key] = value()
                    }
                }
                maybe("n") { rnd.nextLong(-1, 3) }
                maybe("at") { s.instants.random(rnd).toEpochMilli() }
                maybe("ref") { entities.random(rnd).toString() }
                if (rnd.nextBoolean()) obj["meta"] = mapOf("k" to rnd.nextLong(0, 3))
                db.add(e, s.json.name, json = toJson(obj))
                rows += Oracle.Row(e, s.json.name, nil, obj)
            }
        }
        db.exec("analyze")
        oracle = Oracle(rows, typeEntities)
    }

    override fun close() = db.close()

    companion object {
        fun toJson(v: Any?): String = when (v) {
            null -> "null"
            is String -> buildString {
                append('"')
                for (ch in v) {
                    when {
                        ch == '"' -> append("\\\"")
                        ch == '\\' -> append("\\\\")
                        ch < ' ' -> append("\\u%04x".format(ch.code))
                        else -> append(ch)
                    }
                }
                append('"')
            }
            is Map<*, *> -> v.entries.joinToString(",", "{", "}") { (k, x) -> toJson(k as String) + ":" + toJson(x) }
            else -> v.toString()
        }
    }
}
