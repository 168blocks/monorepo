package dev.vxcc.b8.velocity.network

import dev.vxcc.alexs168.pgecs.db.Database
import dev.vxcc.alexs168.pgecs.PgEcsConfig
import dev.vxcc.alexs168.pgecs.testing.TestPostgres
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NetworkStoreTest {
    private val admin = TestPostgres.config
    /** what the edges connect as (trust in the test server; pg_hba by tunnel address in the network) */
    private val edgeConfig = admin.copy(user = "blocks_edge")

    private fun sql(vararg statements: String) = Database.connect(admin).use { db ->
        db.tx { connection.createStatement().use { st -> statements.forEach(st::execute) } }
    }

    @BeforeAll
    fun schema() {
        // roles as nix/postgres-entrypoint.sh creates them
        sql(
            "do $$ begin if not exists (select from pg_roles where rolname = 'blocks_edge') then create role blocks_edge login; end if; " +
                "if not exists (select from pg_roles where rolname = 'blocks_ops') then create role blocks_ops login; end if; end $$",
        )
        Database.connect(admin).use { db -> db.tx { NetworkSchema.migrate(connection) } }
        // a second run (another core start) applies nothing
        assertEquals(0, Database.connect(admin).use { db -> db.tx { NetworkSchema.migrate(connection) } })
    }

    private fun store(edge: String) = NetworkStore(edgeConfig, edge)
    private fun uniqueEdge() = "edge-" + UUID.randomUUID().toString().take(8)

    @Test
    fun edgeSeesTheSchemaVersion(): Unit = store(uniqueEdge()).use { assertEquals(NetworkSchema.VERSION, it.schemaVersion()) }

    @Test
    fun edgeRoleCannotChangeTheSchema(): Unit = store(uniqueEdge()).use { s ->
        val e = assertFailsWith<SQLException> { s.tx { c -> c.createStatement().use { it.execute("create table network.x (i int)") } } }
        assertEquals("42501", e.sqlState, e.message) // insufficient privilege
        assertFailsWith<SQLException> { s.tx { c -> c.createStatement().use { it.execute("drop table network.bans") } } }
    }

    @Test
    fun newestLoginWinsAcrossEdges() {
        val e1 = uniqueEdge()
        val e2 = uniqueEdge()
        store(e1).use { one ->
            store(e2).use { two ->
                val uuid = UUID.randomUUID()
                val old = UUID.randomUUID()
                val new = UUID.randomUUID()
                val before = one.onlineCount()
                one.register(uuid, "alice", old)
                assertEquals(before + 1, one.onlineCount())
                two.register(uuid, "alice", new)
                // still one row, now on edge 2
                assertEquals(before + 1, two.onlineCount())
                assertEquals(e2, two.findOnline("ALICE")?.edge)

                // edge 1's heartbeat learns its session was replaced and does not take the row back
                val session = NetworkStore.Session(uuid, "alice", old, "lobby")
                assertEquals(listOf(session), one.heartbeat("127.0.0.1:1", false, listOf(session)))
                assertEquals(e2, two.findOnline("alice")?.edge)
                // edge 1's disconnect of the old session leaves the new one alone
                one.unregister(uuid, old)
                assertEquals(e2, two.findOnline("alice")?.edge)
                two.setServer(uuid, new, "survival")
                assertEquals("survival", two.findOnline("alice")?.server)
                two.unregister(uuid, new)
                assertNull(two.findOnline("alice"))
            }
        }
    }

    @Test
    fun rowsOfADeadEdgeExpire(): Unit = store(uniqueEdge()).use { s ->
        val uuid = UUID.randomUUID()
        val session = UUID.randomUUID()
        val before = s.onlineCount()
        s.register(uuid, "bob", session)
        assertEquals(before + 1, s.onlineCount())
        sql("update network.online_players set heartbeat = now() - interval '1 minute' where uuid = '$uuid'")
        assertEquals(before, s.onlineCount())
        // the heartbeat of a live edge re-creates its players' rows
        assertEquals(emptyList(), s.heartbeat("127.0.0.1:1", false, listOf(NetworkStore.Session(uuid, "bob", session, null))))
        assertEquals(before + 1, s.onlineCount())
        s.unregister(uuid, session)
    }

    @Test
    fun drainTargetsAreLiveAcceptingOtherEdges() {
        val names = List(3) { uniqueEdge() }
        val stores = names.map(::store)
        try {
            stores[0].heartbeat("a:1", false, emptyList())
            stores[1].heartbeat("b:2", false, emptyList())
            stores[2].heartbeat("c:3", true, emptyList())
            val targets = stores[0].drainTargets().map { it.name }
            assertTrue(names[1] in targets && names[0] !in targets && names[2] !in targets, "$targets")
            stores[1].setDraining(true)
            assertTrue(names[1] !in stores[0].drainTargets().map { it.name })
        } finally {
            stores.forEach { it.close() }
        }
    }

    @Test
    fun bans(): Unit = store(uniqueEdge()).use { s ->
        val uuid = UUID.randomUUID()
        assertNull(s.ban("Mallory", uuid))
        s.addBan("Mallory", null, "griefing", "console")
        assertEquals("griefing", assertNotNull(s.ban("mallory", uuid)).reason)
        assertTrue(s.removeBan("MALLORY"))
        assertNull(s.ban("mallory", uuid))
    }

    @Test
    fun loginsNotifyEveryEdge() {
        val received = LinkedBlockingQueue<NetworkMessage>()
        NetworkListener(edgeConfig, { received.add(it) }, { m, e -> println("$m $e") }).start().use { listener ->
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!listener.listening && System.nanoTime() < end) Thread.sleep(50)
            assertTrue(listener.listening)
            store(uniqueEdge()).use { s ->
                val uuid = UUID.randomUUID()
                val session = UUID.randomUUID()
                s.register(uuid, "carol", session)
                val kick = received.poll(10, TimeUnit.SECONDS)
                assertEquals(NetworkMessage.Kick(uuid, exceptSession = session, reason = "You logged in from another location"), kick)
                s.publish(NetworkMessage.Drain("edge-9", true))
                assertEquals(NetworkMessage.Drain("edge-9", true), received.poll(10, TimeUnit.SECONDS))
                s.unregister(uuid, session)
            }
        }
    }

    @Test
    fun messagesRoundTrip() {
        val uuid = UUID.randomUUID()
        for (m in listOf(
            NetworkMessage.Kick(uuid, null, UUID.randomUUID(), "x"),
            NetworkMessage.Kick(null, "bob", null, "banned: \"quotes\""),
            NetworkMessage.Send("alice", "survival"),
            NetworkMessage.Broadcast("hello", staffOnly = true, from = "console"),
            NetworkMessage.Drain("edge-1", false),
        )) assertEquals(m, NetworkMessage.decode(m.encode()))
    }
}
