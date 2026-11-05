package net.blocks168.pgecs

import net.blocks168.pgecs.testing.TestPostgres
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PgEcsTest {
    @Test
    fun `connects and pings`() {
        PgEcs.connect(TestPostgres.config).use { ecs ->
            val info = ecs.ping()
            assertEquals(TestPostgres.DATABASE, info.database)
            assertTrue(info.version.isNotBlank())
        }
    }

    @Test
    fun `transaction commits on success and rolls back on failure`() {
        PgEcs.connect(TestPostgres.config).use { ecs ->
            ecs.transaction { it.createStatement().execute("create table if not exists t_tx (v int)") }
            ecs.transaction { it.createStatement().execute("insert into t_tx values (1)") }
            assertFailsWith<IllegalStateException> {
                ecs.transaction {
                    it.createStatement().execute("insert into t_tx values (2)")
                    error("abort")
                }
            }
            val values = ecs.transaction { c ->
                c.createStatement().executeQuery("select v from t_tx order by v").use { rs ->
                    buildList { while (rs.next()) add(rs.getInt(1)) }
                }
            }
            assertEquals(listOf(1), values)
        }
    }

    @Test
    fun `pg_jsonschema is available`() {
        PgEcs.connect(TestPostgres.config).use { ecs ->
            val (valid, invalid) = ecs.transaction { c ->
                c.createStatement().executeQuery(
                    """
                    select jsonb_matches_schema('{"type":"object","required":["x"]}', '{"x":1}'),
                           jsonb_matches_schema('{"type":"object","required":["x"]}', '{"y":1}')
                    """,
                ).use { rs -> rs.next(); rs.getBoolean(1) to rs.getBoolean(2) }
            }
            assertTrue(valid)
            assertTrue(!invalid)
        }
    }

    @Test
    fun `retry gives up on unreachable server`() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val start = System.currentTimeMillis()
        assertFailsWith<SQLException> {
            PgEcs.connectWithRetry(PgEcsConfig("jdbc:postgresql://127.0.0.1:$port/x", "u"), timeoutMillis = 500, intervalMillis = 100)
        }
        assertTrue(System.currentTimeMillis() - start >= 500)
    }

    @Test
    fun `rejects non-postgres urls`() {
        assertFailsWith<SQLException> { PgEcs.connect(PgEcsConfig("jdbc:mysql://x/y", "u")) }
    }

    @Test
    fun `config from env`() {
        val cfg = PgEcsConfig.fromEnv(mapOf("PGECS_URL" to "jdbc:postgresql://db/x", "PGECS_USER" to "u"))
        assertEquals(PgEcsConfig("jdbc:postgresql://db/x", "u"), cfg)
    }
}
