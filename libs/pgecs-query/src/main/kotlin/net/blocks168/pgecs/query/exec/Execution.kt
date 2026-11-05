package net.blocks168.pgecs.query.exec

import net.blocks168.pgecs.ir.ValueType
import net.blocks168.pgecs.query.plan.Kind
import net.blocks168.pgecs.query.sql.Bind
import net.blocks168.pgecs.query.sql.CompiledSql
import net.blocks168.pgecs.query.sql.Mode
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** [Mode.ONE] found no row or more than one. */
class ResultSizeException(message: String) : IllegalStateException(message)

/**
 * Runs compiled statements on a JDBC connection: binds arguments by their plan kinds and decodes
 * result columns by their IR types.
 *
 * Values in: entity ids as [UUID] (or their string form), text as [String], integers as any [Number],
 * instants as [Instant] or epoch milliseconds, durations as [Duration] or milliseconds, JSON as its
 * text. Values out, per IR type: [UUID], [String], [Long], [Double], [Boolean], [Instant], [Duration],
 * and JSON as its text; null for SQL null.
 *
 * Never commits, rolls back or changes the connection: it runs inside whatever transaction the caller
 * has open, and lets [java.sql.SQLException]s through for the transaction runner to classify.
 */
object Execution {
    /**
     * Runs [sql] (null when the plan proved the query empty) with [args] and returns its rows, each
     * with one value per entry of [columns]. [mode] must be the one [sql] was compiled for.
     */
    fun run(connection: Connection, sql: CompiledSql?, columns: List<ValueType>, args: List<Any?>, mode: Mode): List<Array<Any?>> {
        if (sql == null) {
            return when (mode) {
                Mode.COUNT -> listOf(arrayOf(0L))
                Mode.ONE -> throw ResultSizeException("expected exactly one row, got none")
                else -> emptyList()
            }
        }
        val now = System.currentTimeMillis()
        val rows = connection.prepareStatement(sql.sql).use { st ->
            sql.binds.forEachIndexed { i, b -> bind(connection, st, i + 1, b, args, now) }
            st.executeQuery().use { rs ->
                if (mode == Mode.COUNT) {
                    check(rs.next()) { "count returned no row" }
                    return listOf(arrayOf(rs.getLong(1)))
                }
                buildList { while (rs.next()) add(Array(columns.size) { decode(rs, it + 1, columns[it]) }) }
            }
        }
        if (mode == Mode.ONE && rows.size != 1) {
            throw ResultSizeException("expected exactly one row, got ${if (rows.isEmpty()) "none" else "more than one"}")
        }
        return rows
    }

    private fun bind(connection: Connection, st: PreparedStatement, i: Int, b: Bind, args: List<Any?>, now: Long) {
        when (b) {
            Bind.Now -> st.setLong(i, now)
            is Bind.Const -> value(st, i, b.kind, b.value)
            is Bind.Param -> {
                val v = args[b.index]
                when {
                    v == null -> st.setNull(i, sqlType(b.kind))
                    b.list -> {
                        val items = (v as? Collection<*>)?.toList() ?: (v as Array<*>).toList()
                        val converted = items.map { it?.let { x -> convert(b.kind, x) } }.toTypedArray()
                        st.setArray(i, connection.createArrayOf(b.kind.sqlType, converted))
                    }
                    else -> value(st, i, b.kind, v)
                }
            }
        }
    }

    private fun value(st: PreparedStatement, i: Int, kind: Kind, v: Any) {
        when (val c = convert(kind, v)) {
            is UUID -> st.setObject(i, c)
            is Long -> st.setLong(i, c)
            is Double -> st.setDouble(i, c)
            is Boolean -> st.setBoolean(i, c)
            else -> st.setString(i, c as String)
        }
    }

    private fun convert(kind: Kind, v: Any): Any = when (kind) {
        Kind.ENTITY -> v as? UUID ?: UUID.fromString(v.toString())
        Kind.STRING -> v.toString()
        Kind.LONG -> when (v) {
            is Instant -> v.toEpochMilli()
            is Duration -> v.toMillis()
            is Number -> v.toLong()
            else -> throw IllegalArgumentException("cannot bind ${v::class.java.name} as an integer")
        }
        Kind.DOUBLE -> (v as? Number)?.toDouble() ?: throw IllegalArgumentException("cannot bind ${v::class.java.name} as a double")
        Kind.BOOLEAN -> v as? Boolean ?: throw IllegalArgumentException("cannot bind ${v::class.java.name} as a boolean")
        Kind.JSON -> v.toString()
    }

    private fun sqlType(kind: Kind): Int = when (kind) {
        Kind.ENTITY -> Types.OTHER
        Kind.STRING, Kind.JSON -> Types.VARCHAR
        Kind.LONG -> Types.BIGINT
        Kind.DOUBLE -> Types.DOUBLE
        Kind.BOOLEAN -> Types.BOOLEAN
    }

    private fun decode(rs: ResultSet, i: Int, t: ValueType): Any? {
        val v: Any? = when (t.kind) {
            ValueType.Kind.UUID -> when (val o = rs.getObject(i)) {
                null -> null
                is UUID -> o
                else -> UUID.fromString(o.toString())
            }
            ValueType.Kind.INT -> rs.getLong(i)
            ValueType.Kind.FLOAT -> rs.getDouble(i)
            ValueType.Kind.BOOL -> rs.getBoolean(i)
            ValueType.Kind.TEXT, ValueType.Kind.JSON -> rs.getString(i)
            ValueType.Kind.INSTANT -> Instant.ofEpochMilli(rs.getLong(i))
            ValueType.Kind.DURATION -> Duration.ofMillis(rs.getLong(i))
        }
        return if (rs.wasNull()) null else v
    }
}
