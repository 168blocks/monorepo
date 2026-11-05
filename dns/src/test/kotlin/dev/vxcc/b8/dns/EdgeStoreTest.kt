package dev.vxcc.b8.dns

import dev.vxcc.alexs168.pgecs.db.Database
import dev.vxcc.alexs168.pgecs.testing.TestPostgres
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [EdgeStore], [Prober] and [Edges] against a real PostgreSQL (TestPostgres). */
class EdgeStoreTest {
    private val db = Database.connect(TestPostgres.config)
    private val store = EdgeStore(db).also {
        it.migrate()
        it.migrate() // idempotent
        db.tx { connection.createStatement().use { st -> st.execute("delete from dns.edges") } }
    }

    @AfterTest
    fun close() = db.close()

    private fun sql(s: String) = db.tx { connection.createStatement().use { it.execute(s) } }

    private fun seedEdge(name: String, region: String, probe: HostPort? = null) =
        SeedEdge(name, region, InetAddress.getByName("192.0.2.1"), 25565, probe)

    @Test
    fun `seed upserts config fields and keeps state`() {
        store.seed(listOf(seedEdge("edge-1", "eu"), seedEdge("edge-2", "us")))
        sql("update dns.edges set enabled = false, heartbeat = now() where name = 'edge-2'")
        sql("insert into dns.edges (name, region, address, heartbeat) values ('extra', 'asia', '2001:db8::1', now())")
        store.seed(listOf(seedEdge("edge-1", "eu"), seedEdge("edge-2", "eu").copy(port = 25599)))

        val edges = store.load(Duration.ofSeconds(30))
        // edge-2 stays disabled, the extra edge stays
        assertEquals(listOf("edge-1", "extra"), edges.map { it.name })
        assertEquals(false, edges[0].healthy, "no heartbeat yet")
        assertEquals(Edge("extra", "asia", InetAddress.getByName("2001:db8::1"), 25565, true), edges[1])
        sql("update dns.edges set enabled = true where name = 'edge-2'")
        assertEquals(Pair("eu", 25599), store.load(Duration.ofSeconds(30)).single { it.name == "edge-2" }.let { it.region to it.port })
    }

    @Test
    fun `heartbeats go stale`() {
        sql("insert into dns.edges (name, region, address, heartbeat) values ('old', 'eu', '192.0.2.9', now() - interval '40 seconds')")
        assertEquals(false, store.load(Duration.ofSeconds(30)).single().healthy)
        assertEquals(true, store.load(Duration.ofSeconds(60)).single().healthy)
    }

    @Test
    fun `prober refreshes heartbeats of answering edges only`() {
        ServerSocket(0).use { up ->
            store.seed(
                listOf(
                    seedEdge("up", "eu", HostPort("127.0.0.1", up.localPort)),
                    seedEdge("down", "eu", HostPort("127.0.0.1", 1)),
                    seedEdge("static", "eu"),
                ),
            )
            val pinged = mutableListOf<HostPort>()
            val prober = Prober(store, Duration.ofHours(1)) { target, _ ->
                synchronized(pinged) { pinged += target }
                if (target.port == 1) throw IOException("connection refused")
            }
            val results = prober.round()
            assertEquals(setOf("up", "down"), results.keys)
            assertNull(results["up"])
            assertNotNull(results["down"])
            assertEquals(2, pinged.size)
            val health = store.load(Duration.ofSeconds(30)).associate { it.name to it.healthy }
            assertEquals(mapOf("down" to false, "static" to false, "up" to true), health)
            val error = db.read { connection.createStatement().use { st -> st.executeQuery("select probe_error from dns.edges where name = 'down'").use { it.next(); it.getString(1) } } }
            assertTrue("connection refused" in error, error)
            prober.close()
        }
    }

    @Test
    fun `status ping against a fake server`() {
        ServerSocket(0).use { server ->
            val t = thread {
                server.accept().use { s ->
                    val input = s.getInputStream()
                    // handshake and status request: length-prefixed, skip both
                    repeat(2) { input.readNBytes(input.read()) }
                    val json = """{"version":{"name":"x","protocol":1}}""".encodeToByteArray()
                    val body = byteArrayOf(0, json.size.toByte()) + json
                    s.getOutputStream().write(byteArrayOf(body.size.toByte()) + body)
                }
            }
            StatusPing.ping(HostPort("127.0.0.1", server.localPort), Duration.ofSeconds(2))
            t.join()
        }
        // a server that accepts and says nothing
        ServerSocket(0).use { server ->
            val t = thread { runCatching { server.accept().use { Thread.sleep(1500) } } }
            assertFailsWith<IOException> { StatusPing.ping(HostPort("127.0.0.1", server.localPort), Duration.ofMillis(300)) }
            t.join()
        }
        assertFailsWith<IOException> { StatusPing.ping(HostPort("127.0.0.1", 1), Duration.ofMillis(300)) }
    }

    @Test
    fun `snapshot keeps serving through a database outage and moves the serial on changes`() {
        var fail = false
        var now = 1000L
        val edges = Edges({ if (fail) error("database down") else store.load(Duration.ofSeconds(30)) }, { now })
        val first = edges.refresh()
        assertEquals(1000, first.serial)
        assertEquals(first, edges.refresh(), "no change, same serial")
        sql("insert into dns.edges (name, region, address, heartbeat) values ('e', 'eu', '192.0.2.9', now())")
        now = 900 // clock went backwards: still increases
        val second = edges.refresh()
        assertEquals(1001, second.serial)
        assertEquals(listOf("e"), second.edges.map { it.name })
        fail = true
        assertEquals(second, edges.refresh())
        assertNotNull(edges.lastError)
        fail = false
        edges.refresh()
        assertNull(edges.lastError)
    }
}
