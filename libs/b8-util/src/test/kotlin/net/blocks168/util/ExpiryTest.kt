package net.blocks168.util

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.blocks168.pgecs.ChildOf
import net.blocks168.pgecs.Pgecs
import net.blocks168.pgecs.Tag
import net.blocks168.pgecs.db.Database
import net.blocks168.pgecs.query.QueryEngineConfig
import net.blocks168.pgecs.testing.TestPostgres

object Otp : Tag("test:util:otp")

class ExpiryTest {
    @Test
    fun `the sweep deletes expired entities and their children, notExpired hides the rest`() {
        Database.connect(TestPostgres.config).use { db ->
            Pgecs.on(db) { engine = QueryEngineConfig(eqsatInstances = 0) }.use { ecs ->
                ecs.freeze()
                Expiry.install(ecs)
                // the sweeps may have run against this database before (a shared PGECS_TEST_URL): make them due
                db.tx {
                    connection.createStatement().use {
                        it.executeUpdate("update ecs_component set value = value || '{\"nextRun\": 0}' where rel = 'pgecs:system:v1' and value->>'name' like 'b8:util:expire-%'")
                    }
                }
                val (old, fresh, child) = ecs.tx {
                    val old = create { +Otp; this[ExpiresSlowComponent] = Instant.now().minusSeconds(1) }
                    val fresh = create { +Otp; this[ExpiresFastComponent] = Instant.now().plusSeconds(600) }
                    Triple(old, fresh, create { set(ChildOf, old) })
                }
                val live = ecs.query {
                    val e by entity
                    e has Otp
                    notExpired(e)
                    select { e }
                }.fetch()
                assertEquals(listOf(fresh), live.filter { it == fresh || it == old })
                assertTrue(ecs.systems.runOnce(Expiry.SLOW))
                assertTrue(ecs.systems.runOnce(Expiry.FAST))
                ecs.read {
                    assertFalse(exists(old))
                    assertFalse(exists(child))
                    assertTrue(exists(fresh))
                }
                // the index is valid and is what the sweep's predicate uses
                val plan = ecs.database.read {
                    connection.createStatement().use { st ->
                        st.executeQuery(
                            "explain select entity from ecs_component c0 where c0.rel = 'b8:util:expires-slow:v1' " +
                                "and (c0.value#>>'{}')::bigint < 0",
                        ).use { rs -> buildString { while (rs.next()) appendLine(rs.getString(1)) } }
                    }
                }
                assertTrue("expires_slow" in plan, plan)
            }
        }
    }
}
