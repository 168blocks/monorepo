package net.blocks168.pgecs.query.plan

import java.sql.Connection

/**
 * Row count estimates the planner orders joins by.
 *
 * All components share one table, so Postgres' per-table statistics hide how big each component is.
 * The per-`rel` numbers come from Postgres' own column statistics of `ecs_component.rel` (its most
 * common values and their frequencies, kept current by autovacuum's `ANALYZE`), so reading them costs
 * nothing and never scans the table.
 */
data class Stats(
    /** Estimated rows per component name; components not listed get [otherRows]. */
    val rows: Map<String, Double>,
    val otherRows: Double,
    /** Estimated number of entities. */
    val entities: Double,
) {
    fun rows(rel: String): Double = rows[rel] ?: otherRows

    /** Average number of rows of [rel] per entity (for one bound entity or target). */
    fun perEntity(rel: String): Double = rows(rel) / entities.coerceAtLeast(1.0)

    /**
     * Whether [rel]'s estimate moved by more than [factor] since [old] was taken. Small components
     * (both estimates under [floor] rows) never count as drifted.
     */
    fun drifted(old: Stats, rel: String, factor: Double, floor: Double = 1000.0): Boolean {
        val a = old.rows(rel)
        val b = rows(rel)
        if (a < floor && b < floor) return false
        val hi = maxOf(a, b)
        val lo = minOf(a, b).coerceAtLeast(1.0)
        return hi / lo > factor
    }

    companion object {
        /** Used before the first read and when the tables were never analysed. */
        val UNKNOWN = Stats(emptyMap(), otherRows = 1000.0, entities = 10_000.0)

        /**
         * Reads the estimates through [connection]. Only reads the catalog (`pg_class`, `pg_stats`), so it
         * is cheap, takes no locks on the data and is fine inside the caller's transaction.
         */
        fun read(connection: Connection): Stats {
            val sql = """
                select (select reltuples from pg_class where oid = to_regclass('ecs_component')),
                       (select reltuples from pg_class where oid = to_regclass('ecs_entity')),
                       s.most_common_vals::text::text[], s.most_common_freqs, s.n_distinct
                  from (select 1) one
                  left join pg_stats s
                    on s.schemaname = current_schema() and s.tablename = 'ecs_component' and s.attname = 'rel'
            """.trimIndent()
            connection.prepareStatement(sql).use { st ->
                st.executeQuery().use { rs ->
                    if (!rs.next()) return UNKNOWN
                    val total = rs.getDouble(1).takeUnless { rs.wasNull() || it < 0 } ?: return UNKNOWN
                    val entities = rs.getDouble(2).takeUnless { rs.wasNull() || it < 0 } ?: UNKNOWN.entities
                    @Suppress("UNCHECKED_CAST")
                    val vals = (rs.getArray(3)?.array as? Array<String>) ?: emptyArray()
                    val freqs = (rs.getArray(4)?.array as? Array<*>)?.map { (it as Number).toDouble() } ?: emptyList()
                    val nDistinctRaw = rs.getDouble(5).takeUnless { rs.wasNull() } ?: 0.0
                    val nDistinct = if (nDistinctRaw < 0) -nDistinctRaw * total else nDistinctRaw
                    val rows = vals.indices.associate { vals[it] to freqs[it] * total }
                    val restFraction = (1.0 - freqs.sum()).coerceAtLeast(0.0)
                    val restDistinct = (nDistinct - vals.size).coerceAtLeast(1.0)
                    val other = if (vals.isEmpty()) {
                        // analysed, but no common values (or not analysed yet): spread evenly
                        total / nDistinct.coerceAtLeast(1.0)
                    } else {
                        restFraction * total / restDistinct
                    }
                    return Stats(rows, other.coerceAtLeast(1.0), entities.coerceAtLeast(1.0))
                }
            }
        }
    }
}
