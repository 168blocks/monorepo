package dev.vxcc.b8.e2e

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ThreadLocalRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A small DNS client (RFC 1035 over UDP, TCP on truncation or on request) for the player DNS tests: one
 * question, optionally with an EDNS client subnet (RFC 7871) as a resolver would send. Record data is
 * decoded to presentation format for A, AAAA, NS, CNAME, SOA, SRV and TXT.
 */
object Dns {
    const val A = 1
    const val NS = 2
    const val CNAME = 5
    const val SOA = 6
    const val TXT = 16
    const val AAAA = 28
    const val SRV = 33
    const val OPT = 41

    const val NOERROR = 0
    const val SERVFAIL = 2
    const val NXDOMAIN = 3

    private val typeNames = mapOf(A to "A", NS to "NS", CNAME to "CNAME", SOA to "SOA", TXT to "TXT", AAAA to "AAAA", SRV to "SRV", OPT to "OPT")

    /** A record; [data] in presentation format (names with the trailing dot, TXT strings unquoted and joined) */
    data class Rr(val name: String, val type: Int, val ttl: Long, val data: String) {
        val typeName get() = typeNames[type] ?: "TYPE$type"
        override fun toString() = "$name $ttl $typeName $data"
    }

    /** ECS option of the response: the subnet echoed and the scope the answer is valid for */
    data class ClientSubnet(val address: InetAddress, val sourcePrefix: Int, val scopePrefix: Int)

    data class Response(
        val id: Int,
        val rcode: Int,
        val authoritative: Boolean,
        val truncated: Boolean,
        val answers: List<Rr>,
        val authority: List<Rr>,
        val additional: List<Rr>,
        val clientSubnet: ClientSubnet?,
    ) {
        fun answers(type: Int) = answers.filter { it.type == type }
    }

    fun query(
        server: Network.Endpoint,
        name: String,
        type: Int,
        subnet: Pair<InetAddress, Int>? = null,
        tcp: Boolean = false,
        timeout: Duration = 5.seconds,
    ): Response {
        val id = ThreadLocalRandom.current().nextInt(0x10000)
        val query = encodeQuery(id, name, type, subnet)
        if (!tcp) {
            val r = udp(server, query, timeout)
            check(r.id == id) { "response id ${r.id} for query $id" }
            if (!r.truncated) return r
        }
        return tcp(server, query, timeout).also { check(it.id == id) { "response id ${it.id} for query $id" } }
    }

    private fun udp(server: Network.Endpoint, query: ByteArray, timeout: Duration): Response =
        DatagramSocket().use { socket ->
            socket.soTimeout = timeout.inWholeMilliseconds.toInt()
            socket.send(DatagramPacket(query, query.size, InetSocketAddress(server.host, server.port)))
            val buf = ByteArray(65535)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            decode(buf.copyOf(packet.length))
        }

    private fun tcp(server: Network.Endpoint, query: ByteArray, timeout: Duration): Response =
        Socket().use { socket ->
            socket.connect(InetSocketAddress(server.host, server.port), timeout.inWholeMilliseconds.toInt())
            socket.soTimeout = timeout.inWholeMilliseconds.toInt()
            DataOutputStream(socket.getOutputStream()).apply { writeShort(query.size); write(query); flush() }
            val input = DataInputStream(socket.getInputStream())
            decode(ByteArray(input.readUnsignedShort()).also(input::readFully))
        }

    /** A query with recursion not desired and an EDNS OPT record (with ECS when [subnet] is given) */
    fun encodeQuery(id: Int, name: String, type: Int, subnet: Pair<InetAddress, Int>? = null): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeShort(id)
        d.writeShort(0) // flags: standard query, RD = 0 (an authoritative server is asked)
        d.writeShort(1) // QDCOUNT
        d.writeShort(0)
        d.writeShort(0)
        d.writeShort(1) // ARCOUNT: OPT
        writeName(d, name)
        d.writeShort(type)
        d.writeShort(1) // IN
        // OPT: root name, type 41, class = UDP payload size, TTL = extended rcode/version/flags
        d.writeByte(0)
        d.writeShort(OPT)
        d.writeShort(1232)
        d.writeInt(0)
        val options = ByteArrayOutputStream()
        if (subnet != null) {
            val (address, prefix) = subnet
            val bytes = address.address.copyOf((prefix + 7) / 8)
            if (prefix % 8 != 0) bytes[bytes.size - 1] = (bytes.last().toInt() and (0xff shl (8 - prefix % 8))).toByte()
            DataOutputStream(options).apply {
                writeShort(8) // edns-client-subnet
                writeShort(4 + bytes.size)
                writeShort(if (address is Inet4Address) 1 else 2)
                writeByte(prefix)
                writeByte(0) // scope prefix: 0 in queries
                write(bytes)
            }
        }
        d.writeShort(options.size())
        d.write(options.toByteArray())
        return out.toByteArray()
    }

    private fun writeName(d: DataOutputStream, name: String) {
        for (label in name.removeSuffix(".").split('.').filter { it.isNotEmpty() }) {
            val b = label.encodeToByteArray()
            require(b.size in 1..63) { "bad label in $name" }
            d.writeByte(b.size)
            d.write(b)
        }
        d.writeByte(0)
    }

    fun decode(message: ByteArray): Response {
        val buf = ByteBuffer.wrap(message)
        val id = buf.short.toInt() and 0xffff
        val flags = buf.short.toInt() and 0xffff
        val qd = buf.short.toInt() and 0xffff
        val an = buf.short.toInt() and 0xffff
        val ns = buf.short.toInt() and 0xffff
        val ar = buf.short.toInt() and 0xffff
        repeat(qd) { readName(message, buf); buf.position(buf.position() + 4) }
        var subnet: ClientSubnet? = null
        var extendedRcode = 0
        fun section(n: Int) = List(n) {
            val name = readName(message, buf)
            val type = buf.short.toInt() and 0xffff
            val klass = buf.short.toInt() and 0xffff
            val ttl = buf.int.toLong() and 0xffffffffL
            val length = buf.short.toInt() and 0xffff
            val start = buf.position()
            val data = when (type) {
                A, AAAA -> InetAddress.getByAddress(message.copyOfRange(start, start + length)).hostAddress
                NS, CNAME -> readName(message, buf)
                SOA -> {
                    val mname = readName(message, buf)
                    val rname = readName(message, buf)
                    val nums = List(5) { buf.int.toLong() and 0xffffffffL }
                    "$mname $rname ${nums.joinToString(" ")}"
                }
                SRV -> {
                    val prio = buf.short.toInt() and 0xffff
                    val weight = buf.short.toInt() and 0xffff
                    val port = buf.short.toInt() and 0xffff
                    "$prio $weight $port ${readName(message, buf)}"
                }
                TXT -> buildString {
                    var p = start
                    while (p < start + length) {
                        val l = message[p].toInt() and 0xff
                        append(message.copyOfRange(p + 1, p + 1 + l).decodeToString())
                        p += 1 + l
                    }
                }
                OPT -> {
                    extendedRcode = (ttl ushr 24).toInt()
                    var p = start
                    while (p + 4 <= start + length) {
                        val code = ((message[p].toInt() and 0xff) shl 8) or (message[p + 1].toInt() and 0xff)
                        val len = ((message[p + 2].toInt() and 0xff) shl 8) or (message[p + 3].toInt() and 0xff)
                        if (code == 8 && len >= 4) {
                            val family = ((message[p + 4].toInt() and 0xff) shl 8) or (message[p + 5].toInt() and 0xff)
                            val source = message[p + 6].toInt() and 0xff
                            val scope = message[p + 7].toInt() and 0xff
                            val addr = message.copyOfRange(p + 8, p + 4 + len).copyOf(if (family == 1) 4 else 16)
                            subnet = ClientSubnet(InetAddress.getByAddress(addr), source, scope)
                        }
                        p += 4 + len
                    }
                    "udp=$klass"
                }
                else -> message.copyOfRange(start, start + length).joinToString("") { "%02x".format(it) }
            }
            buf.position(start + length)
            Rr(name, type, ttl, data)
        }
        val answers = section(an)
        val authority = section(ns)
        val additional = section(ar)
        return Response(
            id = id,
            rcode = (flags and 0xf) or (extendedRcode shl 4),
            authoritative = flags and 0x0400 != 0,
            truncated = flags and 0x0200 != 0,
            answers = answers,
            authority = authority,
            additional = additional.filter { it.type != OPT },
            clientSubnet = subnet,
        )
    }

    /** A possibly compressed name at [buf]'s position (advanced past it), with the trailing dot */
    private fun readName(message: ByteArray, buf: ByteBuffer): String {
        val labels = mutableListOf<String>()
        var p = buf.position()
        var jumped = false
        var hops = 0
        while (true) {
            if (p >= message.size) throw IOException("name runs past the message")
            val len = message[p].toInt() and 0xff
            when {
                len == 0 -> { if (!jumped) buf.position(p + 1); break }
                len and 0xc0 == 0xc0 -> {
                    if (++hops > 64) throw IOException("compression loop")
                    val target = ((len and 0x3f) shl 8) or (message[p + 1].toInt() and 0xff)
                    if (!jumped) buf.position(p + 2)
                    jumped = true
                    p = target
                }
                else -> {
                    labels += message.copyOfRange(p + 1, p + 1 + len).decodeToString()
                    p += 1 + len
                }
            }
        }
        return labels.joinToString(".") + "."
    }
}
