package net.blocks168.pgecs

import java.security.SecureRandom
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import net.blocks168.pgecs.serial.PgecsJson

/**
 * The buffered writes of one transaction (pgecs design, *Deterministic lock order*). Writes to one
 * key `(entity, rel, target)` collapse to the last one, so [flush] sends at most one statement per
 * key, in this order:
 *
 * 1. new entities, in one statement;
 * 2. per entity, sorted by id: `removeAll` of relations, removals, then sets (an exclusive set also
 *    removes the relation's other pairs);
 * 3. deleted entities, in one statement, so cleanup policies and cascades run last.
 *
 * Sorting by entity id means two transactions touching the same entities lock them in the same
 * order, which avoids most deadlocks.
 */
internal class WriteBuffer {
    private sealed interface Op {
        data class Set(val value: String?, val merge: Boolean) : Op
        data object Remove : Op
    }

    private data class Key(val rel: String, val target: UUID)

    private class EntityOps {
        /** Relations whose pairs are all removed, except the targets set afterwards. */
        val removeAll = LinkedHashSet<String>()
        val ops = LinkedHashMap<Key, Op>()
    }

    private val created = LinkedHashMap<UUID, Boolean>() // id -> ifMissing
    private val deleted = LinkedHashSet<UUID>()
    private val entities = HashMap<UUID, EntityOps>()

    val isEmpty: Boolean get() = created.isEmpty() && deleted.isEmpty() && entities.isEmpty()

    fun create(id: UUID, ifMissing: Boolean) {
        require(id != NIL) { "the nil entity is reserved" }
        val old = created[id]
        if (old == null || old) created[id] = ifMissing
    }

    fun delete(id: UUID) {
        deleted += id
    }

    private fun ops(entity: UUID) = entities.getOrPut(entity) { EntityOps() }

    fun set(entity: UUID, rel: String, target: UUID, value: String?, merge: Boolean, exclusive: Boolean) {
        val e = ops(entity)
        if (exclusive) {
            e.removeAll += rel
            e.ops.keys.removeIf { it.rel == rel }
        }
        e.ops[Key(rel, target)] = Op.Set(value, merge)
    }

    fun remove(entity: UUID, rel: String, target: UUID) {
        ops(entity).ops[Key(rel, target)] = Op.Remove
    }

    fun removeAll(entity: UUID, rel: String) {
        val e = ops(entity)
        e.removeAll += rel
        e.ops.keys.removeIf { it.rel == rel }
    }

    fun clear() {
        created.clear()
        deleted.clear()
        entities.clear()
    }

    fun flush(c: Connection) {
        if (isEmpty) return
        try {
            if (created.isNotEmpty()) createEntities(c)
            for (entity in entities.keys.sortedWith(UUID_ORDER)) flushEntity(c, entity, entities.getValue(entity))
            if (deleted.isNotEmpty()) {
                c.prepareStatement("delete from ecs_entity where id = any(?)").use { st ->
                    st.setArray(1, c.createArrayOf("uuid", deleted.sortedWith(UUID_ORDER).toTypedArray()))
                    st.executeUpdate()
                }
            }
        } finally {
            // a failed statement aborts the transaction; nothing buffered can be sent after it anyway
            clear()
        }
    }

    private fun createEntities(c: Connection) {
        val strict = created.filterValues { !it }.keys.sortedWith(UUID_ORDER)
        val lenient = created.filterValues { it }.keys.sortedWith(UUID_ORDER)
        if (strict.isNotEmpty()) {
            c.prepareStatement("insert into ecs_entity (id) select unnest(?::uuid[])").use { st ->
                st.setArray(1, c.createArrayOf("uuid", strict.toTypedArray()))
                st.executeUpdate()
            }
        }
        if (lenient.isNotEmpty()) {
            c.prepareStatement("insert into ecs_entity (id) select unnest(?::uuid[]) on conflict (id) do nothing").use { st ->
                st.setArray(1, c.createArrayOf("uuid", lenient.toTypedArray()))
                st.executeUpdate()
            }
        }
    }

    private fun flushEntity(c: Connection, entity: UUID, e: EntityOps) {
        for (rel in e.removeAll) {
            val keep = e.ops.entries.filter { it.key.rel == rel && it.value is Op.Set }.map { it.key.target }
            c.prepareStatement("delete from ecs_component where entity = ? and rel = ? and target <> all(?)").use { st ->
                st.setObject(1, entity)
                st.setString(2, rel)
                st.setArray(3, c.createArrayOf("uuid", keep.toTypedArray()))
                st.executeUpdate()
            }
        }
        for ((key, op) in e.ops) if (op is Op.Remove) {
            c.prepareStatement("delete from ecs_component where entity = ? and rel = ? and target = ?").use { st ->
                st.setObject(1, entity)
                st.setString(2, key.rel)
                st.setObject(3, key.target)
                st.executeUpdate()
            }
        }
        for ((key, op) in e.ops) if (op is Op.Set) {
            val sql = when {
                op.value == null -> INSERT_NO_VALUE
                op.merge -> UPSERT_MERGE
                else -> UPSERT_REPLACE
            }
            c.prepareStatement(sql).use { st ->
                st.setObject(1, entity)
                st.setString(2, key.rel)
                st.setObject(3, key.target)
                if (op.value != null) st.setString(4, op.value)
                st.executeUpdate()
            }
        }
    }

    companion object {
        val NIL: UUID = UUID(0, 0)

        /** Postgres' order of uuids: unsigned bytes, which is the order of their text form. */
        val UUID_ORDER: Comparator<UUID> = Comparator { a, b ->
            val hi = java.lang.Long.compareUnsigned(a.mostSignificantBits, b.mostSignificantBits)
            if (hi != 0) hi else java.lang.Long.compareUnsigned(a.leastSignificantBits, b.leastSignificantBits)
        }

        private const val INSERT_NO_VALUE =
            "insert into ecs_component (entity, rel, target) values (?, ?, ?) on conflict (entity, rel, target) do nothing"

        // Writes that change nothing are skipped, so they neither bump the version nor log a change.
        private const val UPSERT_REPLACE =
            "insert into ecs_component (entity, rel, target, value) values (?, ?, ?, ?::jsonb) " +
                "on conflict (entity, rel, target) do update set value = excluded.value " +
                "where ecs_component.value is distinct from excluded.value"

        /**
         * Object values keep keys this process does not know (written by a newer JVM): the stored
         * object's keys are overwritten by the new value's, top level only, and only while both have
         * the same sealed-class discriminator. Fields this process knows are always written in full.
         */
        private const val MERGED =
            "case when jsonb_typeof(ecs_component.value) = 'object' and jsonb_typeof(excluded.value) = 'object' " +
                "and (ecs_component.value -> '@type') is not distinct from (excluded.value -> '@type') " +
                "then ecs_component.value || excluded.value else excluded.value end"

        private const val UPSERT_MERGE =
            "insert into ecs_component (entity, rel, target, value) values (?, ?, ?, ?::jsonb) " +
                "on conflict (entity, rel, target) do update set value = $MERGED " +
                "where ecs_component.value is distinct from $MERGED"
    }
}

/** UUIDv7 (RFC 9562): 48 bits of Unix milliseconds, then random bits; ids sort roughly by creation. */
object Uuid7 {
    private val random = SecureRandom()

    @JvmStatic
    fun next(millis: Long = System.currentTimeMillis()): UUID {
        val bytes = ByteArray(10).also(random::nextBytes)
        val randA = ((bytes[0].toLong() and 0xff) shl 4 or ((bytes[1].toLong() and 0xf0) ushr 4)) and 0xfff
        val msb = (millis and 0xffffffffffffL) shl 16 or (0x7L shl 12) or randA
        var lsb = 0L
        for (i in 2 until 10) lsb = lsb shl 8 or (bytes[i].toLong() and 0xff)
        lsb = lsb and 0x3fffffffffffffffL or Long.MIN_VALUE // variant 10
        return UUID(msb, lsb)
    }
}

/** Encoding and decoding of component values with the shared [PgecsJson]. */
internal object Values {
    fun <T> encode(component: Component<T>, value: T): String = PgecsJson.encodeToString(component.serializer, value)

    fun <T> decode(component: Component<T>, json: String): T = PgecsJson.decodeFromString(component.serializer, json)

    fun <T> decode(component: Component<T>, json: JsonElement): T = PgecsJson.decodeFromJsonElement(component.serializer, json)
}

/** Small JDBC helpers. */
internal object Sql {
    fun bind(st: java.sql.PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
    }

    fun exists(c: Connection, sql: String, vararg args: Any?): Boolean = c.prepareStatement(sql).use { st ->
        bind(st, args)
        st.executeQuery().use { it.next() }
    }

    fun string(c: Connection, sql: String, vararg args: Any?): String? = c.prepareStatement(sql).use { st ->
        bind(st, args)
        st.executeQuery().use { if (it.next()) it.getString(1) else null }
    }

    fun long(c: Connection, sql: String, vararg args: Any?): Long? = c.prepareStatement(sql).use { st ->
        bind(st, args)
        st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1).takeUnless { rs.wasNull() } else null }
    }

    fun uuids(c: Connection, sql: String, vararg args: Any?): List<UUID> = c.prepareStatement(sql).use { st ->
        bind(st, args)
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getObject(1) as UUID) } }
    }

    fun update(c: Connection, sql: String, vararg args: Any?): Int = c.prepareStatement(sql).use { st ->
        bind(st, args)
        st.executeUpdate()
    }
}
