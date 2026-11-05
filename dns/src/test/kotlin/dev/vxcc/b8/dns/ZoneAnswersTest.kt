package dev.vxcc.b8.dns

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZoneAnswersTest {
    private val config = DnsConfig.parse(
        """
        zone 168blocks.test
        nameserver ns1 192.0.2.53
        region eu us
        region us eu
        region asia us eu
        default-region eu
        prefix 192.0.2.0/24 eu
        prefix 198.51.100.0/24 us
        prefix 203.0.113.0/24 asia
        """.trimIndent(),
    )
    private val zone = ZoneAnswers(config, PrefixTable(config.prefixes))

    private fun edge(name: String, region: String, ip: String, port: Int = 25565, healthy: Boolean = true) =
        Edge(name, region, InetAddress.getByName(ip), port, healthy)

    private val edges = listOf(
        edge("edge-1", "eu", "10.0.0.1"),
        edge("edge-2", "us", "10.0.0.2"),
        edge("edge-3", "eu", "10.0.0.3"),
        edge("edge-4", "eu", "10.0.0.4", healthy = false),
    )
    private val snapshot = Snapshot(edges, 1234)

    private fun ask(name: String, type: String, client: String? = null, s: Snapshot = snapshot) =
        zone.lookup(name, type, client?.let { ClientSubnet.parse(it) }, s)

    private fun contents(name: String, type: String, client: String? = null, s: Snapshot = snapshot) =
        ask(name, type, client, s).map { it.content }

    @Test
    fun `soa and ns at the apex`() {
        val soa = ask("168blocks.test.", "SOA").single()
        assertEquals("ns1.168blocks.test. hostmaster.168blocks.test. 1234 3600 600 604800 60", soa.content)
        assertEquals("168blocks.test.", soa.qname)
        assertEquals(listOf("ns1.168blocks.test."), contents("168blocks.test", "NS"))
        assertEquals(listOf("SOA", "NS"), ask("168blocks.test", "ANY").map { it.qtype })
        assertEquals(listOf("192.0.2.53"), contents("NS1.168blocks.test.", "A"))
        assertEquals(emptyList(), contents("ns1.168blocks.test", "AAAA"))
    }

    @Test
    fun `region names answer only their healthy edges`() {
        assertEquals(listOf("10.0.0.1", "10.0.0.3"), contents("eu.168blocks.test", "A"))
        assertEquals(listOf("10.0.0.2"), contents("us.168blocks.test", "A"))
        assertEquals(
            listOf("0 10 25565 edge-1.edge.168blocks.test.", "0 10 25565 edge-3.edge.168blocks.test."),
            contents("_minecraft._tcp.eu.168blocks.test", "SRV"),
        )
        // a configured region without healthy edges exists (TXT), but has no addresses
        assertEquals(emptyList(), contents("asia.168blocks.test", "A"))
        assertEquals(listOf("\"region asia: 0 of 0 edges healthy\""), contents("asia.168blocks.test", "TXT"))
        assertEquals(listOf("\"region eu: 2 of 3 edges healthy\""), contents("eu.168blocks.test", "TXT"))
        // unknown names: nothing (NXDOMAIN)
        assertEquals(emptyList(), ask("mars.168blocks.test", "ANY"))
        assertEquals(emptyList(), ask("x.eu.168blocks.test", "ANY"))
        assertEquals(emptyList(), ask("example.com", "ANY"))
    }

    @Test
    fun `regions only the database knows are served too`() {
        val s = Snapshot(edges + edge("edge-9", "t-xyz", "10.0.0.9"), 1)
        assertEquals(listOf("10.0.0.9"), contents("t-xyz.168blocks.test", "A", s = s))
    }

    @Test
    fun `geo name picks the client's region, then its fallbacks`() {
        // ECS subnet from the prefix table; the scope is the client's subnet
        val eu = ask("play.168blocks.test", "A", "192.0.2.0/24")
        assertEquals(listOf("10.0.0.1", "10.0.0.3"), eu.map { it.content })
        assertTrue(eu.all { it.scopeMask == 24 })
        assertEquals(listOf("10.0.0.2"), contents("play.168blocks.test", "A", "198.51.100.7/32"))
        // asia has no edges: its first fallback, us
        assertEquals(listOf("10.0.0.2"), contents("play.168blocks.test", "A", "203.0.113.0/24"))
        assertEquals(listOf("\"client region asia, answering us\""), contents("play.168blocks.test", "TXT", "203.0.113.0/24"))
        // unknown clients and no client: the default region
        assertEquals(listOf("10.0.0.1", "10.0.0.3"), contents("play.168blocks.test", "A", "100.64.0.0/10"))
        assertEquals(listOf("10.0.0.1", "10.0.0.3"), contents("play.168blocks.test", "A"))
        assertEquals(listOf(25565, 25565), contents("_minecraft._tcp.play.168blocks.test", "SRV", "192.0.2.0/24").map { it.split(' ')[2].toInt() })
    }

    @Test
    fun `geo name falls through to any healthy region`() {
        val euDown = Snapshot(edges.map { if (it.region == "eu") it.copy(healthy = false) else it }, 1)
        // eu falls back to us
        assertEquals(listOf("10.0.0.2"), contents("play.168blocks.test", "A", "192.0.2.0/24", euDown))
        val allDown = Snapshot(edges.map { it.copy(healthy = false) }, 1)
        assertEquals(emptyList(), contents("play.168blocks.test", "A", "192.0.2.0/24", allDown))
        assertEquals(listOf("\"client region eu, answering none\""), contents("play.168blocks.test", "TXT", "192.0.2.0/24", allDown))
    }

    @Test
    fun `shared addresses are deduplicated, srv keeps the ports`() {
        val dev = Snapshot(listOf(edge("edge-1", "eu", "127.0.0.1", 25501), edge("edge-3", "eu", "127.0.0.1", 25503)), 1)
        assertEquals(listOf("127.0.0.1"), contents("eu.168blocks.test", "A", s = dev))
        assertEquals(
            listOf("0 10 25501 edge-1.edge.168blocks.test.", "0 10 25503 edge-3.edge.168blocks.test."),
            contents("_minecraft._tcp.eu.168blocks.test", "SRV", s = dev),
        )
        assertEquals(listOf("127.0.0.1"), contents("edge-3.edge.168blocks.test", "A", s = dev))
        assertEquals(emptyList(), contents("edge-7.edge.168blocks.test", "A", s = dev))
    }

    @Test
    fun `ipv6 edges get aaaa records and answers are capped`() {
        val many = Snapshot((1..6).map { edge("e$it", "eu", "2001:db8::$it") }, 1)
        val aaaa = ask("eu.168blocks.test", "AAAA", s = many)
        assertEquals(config.maxAnswers, aaaa.size)
        assertTrue(aaaa.all { it.qtype == "AAAA" && it.ttl == 60 })
        assertEquals(emptyList(), contents("eu.168blocks.test", "A", s = many))
    }
}
