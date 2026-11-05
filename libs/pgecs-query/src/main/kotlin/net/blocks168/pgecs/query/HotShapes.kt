package net.blocks168.pgecs.query

import net.blocks168.pgecs.ir.QueryIr
import java.sql.Connection
import java.util.concurrent.CompletableFuture

/**
 * Records which query shapes a process runs most, in Postgres, and compiles the hottest ones of the
 * previous boot at startup, so the cost of equality saturation is paid before the first player needs
 * a query (pgecs design, *Pre-compiling*). Compiling them also warms egglog up (its JIT warm-up is
 * long and shared by all instances).
 *
 * Shapes are stored per [process] (a process kind such as `ktor-backend`, not one JVM: every backend
 * runs the same queries) by their canonical text ([QueryIr.canonicalText]); [parse] turns that back
 * into a query. Rows of shapes not used for [retentionDays] are dropped.
 */
class HotShapes(
    private val engine: QueryEngine,
    val process: String,
    private val parse: (String) -> QueryIr,
    val retentionDays: Int = 30,
) {
    companion object {
        /** The table, part of the pgecs base schema. */
        const val SCHEMA = """
            create table if not exists ecs_query_shape (
              process     text not null,
              fingerprint text not null,
              canonical   text not null,
              uses        bigint not null,
              last_used   timestamptz not null default now(),
              primary key (process, fingerprint)
            )
        """
    }

    /**
     * Adds the uses counted since the last flush to the table, through [connection], in the caller's
     * transaction. Call it periodically and at shutdown. Returns the number of shapes written.
     */
    fun flush(connection: Connection): Int {
        val counts = engine.uses.entries.map { (shape, n) -> shape to n.sumThenReset() }.filter { it.second > 0 }
        if (counts.isEmpty()) return 0
        connection.prepareStatement(
            """
            insert into ecs_query_shape (process, fingerprint, canonical, uses) values (?, ?, ?, ?)
            on conflict (process, fingerprint)
            do update set uses = ecs_query_shape.uses + excluded.uses, last_used = now()
            """.trimIndent(),
        ).use { st ->
            // sorted: concurrent flushes of two JVMs lock the rows in the same order
            for ((shape, n) in counts.sortedBy { it.first.fingerprint }) {
                st.setString(1, process)
                st.setString(2, shape.fingerprint)
                st.setString(3, shape.canonicalText)
                st.setLong(4, n)
                st.addBatch()
            }
            st.executeBatch()
        }
        connection.prepareStatement(
            "delete from ecs_query_shape where process = ? and last_used < now() - make_interval(days => ?)",
        ).use { st ->
            st.setString(1, process)
            st.setInt(2, retentionDays)
            st.executeUpdate()
        }
        engine.uses.entries.removeIf { it.value.sum() == 0L }
        return counts.size
    }

    /**
     * Reads the [limit] most used shapes through [connection] and compiles them in the background,
     * then warms egglog up on them ([QueryEngine.warmUpAsync], [warmUpRuns] runs). The future
     * completes when all are compiled (the warm-up goes on after); shapes that no longer parse or
     * compile (the code changed) are skipped. Returns how many compiled.
     */
    fun precompile(connection: Connection, limit: Int = 200, warmUpRuns: Int = 300): CompletableFuture<Int> {
        // plans made without statistics would be re-made on first use
        engine.refreshStats(connection)
        val texts = connection.prepareStatement(
            "select canonical from ecs_query_shape where process = ? order by uses desc limit ?",
        ).use { st ->
            st.setString(1, process)
            st.setInt(2, limit)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        val shapes = texts.mapNotNull { runCatching { parse(it).validate() }.getOrNull() }
        val futures = shapes.map { engine.compileAsync(it).handle { _, e -> e == null } }
        return CompletableFuture.allOf(*futures.toTypedArray()).thenApply {
            engine.warmUpAsync(shapes, warmUpRuns)
            futures.count { it.join() }
        }
    }
}
