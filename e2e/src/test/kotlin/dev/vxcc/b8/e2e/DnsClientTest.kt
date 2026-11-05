package dev.vxcc.b8.e2e

import org.junit.jupiter.api.Tag
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The test DNS client's wire format, without a server. */
@Tag("unit")
class DnsClientTest {
    @Test
    fun `query carries an ecs option with the prefix's bytes only`() {
        val q = Dns.encodeQuery(0x1234, "play.168blocks.test.", Dns.SRV, InetAddress.getByName("198.51.100.77") to 20)
        val hex = q.joinToString("") { "%02x".format(it) }
        assertTrue(hex.startsWith("123400000001000000000001"), hex)
        assertTrue(hex.contains("04706c6179" + "09313638626c6f636b7304" + "74657374" + "00" + "0021" + "0001"), hex)
        // OPT, then option 8: length 7, family 1, source /20, scope 0, 198.51.96 (3 bytes, masked)
        assertTrue(hex.endsWith("00" + "0029" + "04d0" + "00000000" + "000b" + "0008" + "0007" + "0001" + "14" + "00" + "c63360"), hex)
    }

    @Test
    fun `decodes compressed answers, soa and the echoed subnet`() {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeShort(7); d.writeShort(0x8400 or 3) // response, AA, NXDOMAIN
        d.writeShort(1); d.writeShort(2); d.writeShort(1); d.writeShort(1)
        // question at offset 12: eu.x.test SRV IN
        val qname = 12
        d.write(byteArrayOf(2, 'e'.code.toByte(), 'u'.code.toByte(), 1, 'x'.code.toByte(), 4) + "test".encodeToByteArray() + byteArrayOf(0))
        d.writeShort(Dns.SRV); d.writeShort(1)
        // answer 1: SRV, name pointer to the question, target edge.<pointer to x.test>
        d.writeShort(0xc000 or qname); d.writeShort(Dns.SRV); d.writeShort(1); d.writeInt(60)
        d.writeShort(6 + 5 + 2); d.writeShort(0); d.writeShort(10); d.writeShort(25501)
        d.write(byteArrayOf(4) + "edge".encodeToByteArray()); d.writeShort(0xc000 or (qname + 3))
        // answer 2: TXT with two strings
        d.writeShort(0xc000 or qname); d.writeShort(Dns.TXT); d.writeShort(1); d.writeInt(60)
        d.writeShort(6); d.write(byteArrayOf(2, 'h'.code.toByte(), 'i'.code.toByte(), 2, ' '.code.toByte(), '!'.code.toByte()))
        // authority: SOA
        d.writeShort(0xc000 or (qname + 3)); d.writeShort(Dns.SOA); d.writeShort(1); d.writeInt(3600)
        d.writeShort(2 + 2 + 20); d.writeShort(0xc000 or qname); d.writeShort(0xc000 or (qname + 3))
        listOf(42, 3600, 600, 604800, 60).forEach(d::writeInt)
        // additional: OPT with ECS 192.0.2.0/24 scope 24
        d.writeByte(0); d.writeShort(Dns.OPT); d.writeShort(1232); d.writeInt(0)
        d.writeShort(4 + 7); d.writeShort(8); d.writeShort(7); d.writeShort(1); d.writeByte(24); d.writeByte(24)
        d.write(byteArrayOf(192.toByte(), 0, 2))

        val r = Dns.decode(out.toByteArray())
        assertEquals(7, r.id)
        assertEquals(Dns.NXDOMAIN, r.rcode)
        assertTrue(r.authoritative)
        assertEquals("eu.x.test. 60 SRV 0 10 25501 edge.x.test.", r.answers[0].toString())
        assertEquals("hi !", r.answers(Dns.TXT).single().data)
        assertEquals("eu.x.test. x.test. 42 3600 600 604800 60", r.authority.single().data)
        assertEquals(Dns.ClientSubnet(InetAddress.getByName("192.0.2.0"), 24, 24), r.clientSubnet)
        assertEquals(emptyList(), r.additional)
    }
}
