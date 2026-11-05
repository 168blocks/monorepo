package dev.vxcc.b8.dns

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigAndGeoTest {
    private val text = """
        # test zone
        zone 168blocks.test.
        geo-name play
        ttl 60
        nameserver ns1 192.0.2.53   # glue
        nameserver ns2 2001:db8::53
        region eu us
        region us eu
        default-region eu
        prefix 198.51.100.0/24 us
        prefix 198.51.0.0/16 eu
        prefix 2001:db8:1::/48 us
        edge edge-1 eu 127.0.0.1 ${'$'}{EDGE1_PORT:-25501} probe=edge-1:25565
        edge edge-2 us 127.0.0.1 ${'$'}{EDGE2_PORT}
    """.trimIndent()

    @Test
    fun `parses directives and expands the environment`() {
        val c = DnsConfig.parse(text, mapOf("EDGE2_PORT" to "25512"))
        assertEquals("168blocks.test", c.zone)
        assertEquals(listOf("ns1", "ns2"), c.nameservers.map { it.label })
        assertEquals(InetAddress.getByName("2001:db8::53"), c.nameservers[1].address)
        assertEquals(listOf(Region("eu", listOf("us")), Region("us", listOf("eu"))), c.regions)
        assertEquals("eu", c.defaultRegion)
        assertEquals(
            listOf(
                SeedEdge("edge-1", "eu", InetAddress.getByName("127.0.0.1"), 25501, HostPort("edge-1", 25565)),
                SeedEdge("edge-2", "us", InetAddress.getByName("127.0.0.1"), 25512, null),
            ),
            c.edges,
        )
    }

    @Test
    fun `rejects bad configs with the line`() {
        val unset = assertFailsWith<IllegalArgumentException> { DnsConfig.parse(text, emptyMap()) }
        assertTrue("EDGE2_PORT" in unset.message!!, unset.message)
        val unknown = assertFailsWith<IllegalArgumentException> { DnsConfig.parse("zone a.test\nnameserver ns1 192.0.2.1\nbogus 1") }
        assertTrue("line 3" in unknown.message!!, unknown.message)
        assertFailsWith<IllegalArgumentException> { DnsConfig.parse("zone a.test") } // no name server
        assertFailsWith<IllegalArgumentException> { DnsConfig.parse("zone a.test\nnameserver ns1 example.com") } // not an address
        assertFailsWith<IllegalArgumentException> { DnsConfig.parse("zone a.test\nnameserver ns1 192.0.2.1\nregion play") } // collides
    }

    @Test
    fun `cidr matching`() {
        val c = Cidr.parse("198.51.100.77/24")
        assertEquals("198.51.100.0/24", c.toString())
        assertTrue(InetAddress.getByName("198.51.100.1") in c)
        assertFalse(InetAddress.getByName("198.51.101.1") in c)
        assertFalse(InetAddress.getByName("::1") in c)
        assertTrue(InetAddress.getByName("2001:db8:1:ff::1") in Cidr.parse("2001:db8:1::/48"))
        assertEquals(32, Cidr.parse("192.0.2.1").prefix)
        assertTrue(InetAddress.getByName("8.8.8.8") in Cidr.parse("0.0.0.0/0"))
    }

    @Test
    fun `prefix table takes the longest prefix`() {
        val geo = PrefixTable(DnsConfig.parse(text, mapOf("EDGE2_PORT" to "1")).prefixes)
        fun region(s: String) = geo.regionOf(ClientSubnet.parse(s)!!)
        assertEquals("us", region("198.51.100.0/24"))
        assertEquals("eu", region("198.51.7.0/24"))
        assertEquals("us", region("2001:db8:1:2::/56"))
        assertNull(region("203.0.113.0/24"))
        // a client subnet wider than the prefix is not inside it
        assertEquals("eu", region("198.51.0.0/16"))
        assertNull(region("198.0.0.0/8"))
        assertNull(ClientSubnet.parse("not an address"))
    }
}
