package net.blocks168.e2e

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.net.InetAddress
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Player DNS (docs/dns.md): PowerDNS with the dns service as its remote backend, on the local network's
 * zone `168blocks.test` (deploy/dns/dns.conf). Edge 1 and 3 are in `eu`, edge 2 in `us`, `asia` has no
 * edge and falls back to `us`. Everything is published on 127.0.0.1, so the edges are told apart by
 * their SRV records (port and `<edge>.edge.<zone>` target).
 */
@Tag("network")
class DnsTest {
    private val network = Network.fromEnv()
    private val zone = "168blocks.test"

    private fun query(name: String, type: Int, subnet: String? = null, tcp: Boolean = false): Dns.Response {
        val s = subnet?.let { InetAddress.getByName(it.substringBefore('/')) to it.substringAfter('/').toInt() }
        return Dns.query(network.dns, name, type, s, tcp)
    }

    /**
     * `edge:port` of each SRV answer of `_minecraft._tcp.<name>`; the name does not exist (NXDOMAIN) while
     * no edge is answered with
     */
    private fun srv(name: String, subnet: String? = null): Set<String> {
        val r = query("_minecraft._tcp.$name.$zone", Dns.SRV, subnet)
        check(r.rcode == Dns.NOERROR || r.rcode == Dns.NXDOMAIN) { "_minecraft._tcp.$name: rcode ${r.rcode}" }
        return r.answers(Dns.SRV).map {
            val (_, _, port, target) = it.data.split(' ')
            "${target.removeSuffix(".edge.$zone.")}:$port"
        }.toSet()
    }

    private fun edge(n: Int) = "edge-$n:${network.edge(n).port}"

    /** Polls [block] until it returns [expected] (edges start, probes run every 2 s, go stale after 7 s). */
    private fun <T> await(what: String, expected: T, timeout: Duration = 3.minutes, block: () -> T) {
        val end = System.nanoTime() + timeout.inWholeNanoseconds
        var last: Result<T>
        while (true) {
            last = runCatching(block)
            if (last.getOrNull() == expected) return
            if (System.nanoTime() > end) break
            Thread.sleep(1000)
        }
        throw AssertionError("$what: expected $expected, last ${last.getOrNull() ?: last.exceptionOrNull()}", last.exceptionOrNull())
    }

    private fun awaitEdgesUp() {
        await("eu edges", setOf(edge(1), edge(3))) { srv("eu") }
        await("us edges", setOf(edge(2))) { srv("us") }
    }

    @Test
    fun `zone apex has a sane soa and ns`() {
        await("SOA answers", Dns.NOERROR, 2.minutes) { query(zone, Dns.SOA).rcode }
        for (tcp in listOf(false, true)) {
            val r = query(zone, Dns.SOA, tcp = tcp)
            assertTrue(r.authoritative, "not authoritative (tcp=$tcp)")
            val soa = r.answers(Dns.SOA).single()
            val (mname, rname, serial, refresh, retry, expire, minimum) = soa.data.split(' ')
            assertEquals("ns1.$zone.", mname)
            assertEquals("hostmaster.$zone.", rname)
            assertTrue(serial.toLong() > 1_700_000_000, "serial $serial")
            assertTrue(refresh.toLong() > retry.toLong() && expire.toLong() > refresh.toLong(), soa.data)
            assertEquals(60, minimum.toInt())
        }
        assertEquals(listOf("ns1.$zone."), query(zone, Dns.NS).answers(Dns.NS).map { it.data })
        assertEquals(listOf("127.0.0.1"), query("ns1.$zone", Dns.A).answers(Dns.A).map { it.data })

        val missing = query("nope.$zone", Dns.A)
        assertEquals(Dns.NXDOMAIN, missing.rcode)
        assertEquals(Dns.SOA, missing.authority.single().type, "negative answer without the SOA")
        // not our zone: refused
        assertEquals(5, query("example.com", Dns.A).rcode)
    }

    @Test
    fun `every region answers its healthy edges`() {
        awaitEdgesUp()
        val eu = query("eu.$zone", Dns.A)
        assertTrue(eu.authoritative)
        // the edges share 127.0.0.1 here: one address record
        assertEquals(listOf("127.0.0.1"), eu.answers(Dns.A).map { it.data })
        assertEquals(60L, eu.answers(Dns.A).single().ttl)
        // asia has no edge: the name exists (TXT) but has no address or SRV
        val asia = query("asia.$zone", Dns.A)
        assertEquals(Dns.NOERROR, asia.rcode)
        assertEquals(emptyList(), asia.answers)
        assertEquals(emptySet(), srv("asia"))
        // the SRV targets resolve
        for (n in 1..3) assertEquals(listOf("127.0.0.1"), query("edge-$n.edge.$zone", Dns.A).answers(Dns.A).map { it.data })
    }

    @Test
    fun `play answers the nearest region by client subnet`() {
        awaitEdgesUp()
        assertEquals(setOf(edge(1), edge(3)), srv("play", "192.0.2.0/24"))
        assertEquals(setOf(edge(2)), srv("play", "198.51.100.0/24"))
        // asia falls back to us
        assertEquals(setOf(edge(2)), srv("play", "203.0.113.0/24"))
        assertEquals("client region asia, answering us", query("play.$zone", Dns.TXT, "203.0.113.0/24").answers.single().data)
        // unknown subnet: the default region (eu)
        assertEquals(setOf(edge(1), edge(3)), srv("play", "100.64.0.0/10"))
        // the scope echoed back: the answer holds for the client's subnet
        val ecs = query("play.$zone", Dns.A, "198.51.100.0/24").clientSubnet
        assertEquals(Dns.ClientSubnet(InetAddress.getByName("198.51.100.0"), 24, 24), ecs)
    }

    @Test
    fun `an unhealthy edge disappears`() {
        val compose = ComposeNetwork.fromEnv()
        assumeTrue(compose != null, "not the local compose network (BLOCKS_COMPOSE_PROJECT unset)")
        compose!!
        awaitEdgesUp()
        fun sql(s: String) = compose.run(
            compose.namespaceOf("core"), compose.postgresImage,
            "-h", "127.0.0.1", "-U", "blocks", "-d", "blocks", "-v", "ON_ERROR_STOP=1", "-Atc", s,
            entrypoint = "psql",
        ).also { assertTrue(it.ok, it.output) }

        // a region of its own with two more edges, probed through the real edges 1 and 2
        val region = "t-" + Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36 * 36 * 36), 36)
        val a = "$region-a"
        val b = "$region-b"
        sql(
            "insert into dns.edges (name, region, address, port, probe) values " +
                "('$a', '$region', '127.0.0.1', 1001, 'edge-1:25565'), ('$b', '$region', '127.0.0.1', 1002, 'edge-2:25565')",
        )
        try {
            await("both test edges healthy", setOf("$a:1001", "$b:1002"), 60.seconds) { srv(region) }
            // a stops answering its probe: stale after 7 s
            sql("update dns.edges set probe = '127.0.0.1:9' where name = '$a'")
            await("$a dropped", setOf("$b:1002"), 60.seconds) { srv(region) }
            assertEquals(listOf("127.0.0.1"), query("$region.$zone", Dns.A).answers(Dns.A).map { it.data })
            // b taken out by hand
            sql("update dns.edges set enabled = false where name = '$b'")
            await("$b dropped", emptySet<String>(), 30.seconds) { srv(region) }
            // a region only the database knows exists while it has an enabled edge, healthy or not
            assertEquals(Dns.NOERROR, query("$region.$zone", Dns.A).rcode)
            sql("update dns.edges set enabled = false where name = '$a'")
            await("$region gone", Dns.NXDOMAIN, 30.seconds) { query("$region.$zone", Dns.A).rcode }
            // the real edges were never affected
            assertEquals(setOf(edge(1), edge(3)), srv("eu"))
        } finally {
            sql("delete from dns.edges where region = '$region'")
        }
    }
}

private operator fun <T> List<T>.component6() = this[5]
private operator fun <T> List<T>.component7() = this[6]
