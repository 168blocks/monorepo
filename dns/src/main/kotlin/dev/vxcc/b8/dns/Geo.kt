package dev.vxcc.b8.dns

import java.net.Inet4Address
import java.net.InetAddress

/** An IP prefix such as `192.0.2.0/24` or `2001:db8::/32`. The host bits of [address] are cleared. */
class Cidr(address: InetAddress, val prefix: Int) {
    val address: InetAddress = mask(address, prefix)

    init {
        require(prefix in 0..bits(address)) { "prefix /$prefix out of range for $address" }
    }

    val isV4 get() = address is Inet4Address

    operator fun contains(other: InetAddress): Boolean =
        other.javaClass == address.javaClass && mask(other, prefix) == address

    override fun equals(other: Any?) = other is Cidr && other.address == address && other.prefix == prefix
    override fun hashCode() = address.hashCode() * 31 + prefix
    override fun toString() = "${address.hostAddress}/$prefix"

    companion object {
        /** `a.b.c.d/n`, `x::y/n`, or a bare address (a host prefix) */
        fun parse(text: String): Cidr {
            val addr = text.substringBefore('/').trim()
            val address = parseAddress(addr)
            val prefix = if ('/' in text) text.substringAfter('/').trim().toInt() else bits(address)
            return Cidr(address, prefix)
        }

        fun bits(address: InetAddress) = address.address.size * 8

        fun mask(address: InetAddress, prefix: Int): InetAddress {
            val bytes = address.address
            for (i in bytes.indices) {
                val keep = (prefix - i * 8).coerceIn(0, 8)
                bytes[i] = (bytes[i].toInt() and (0xff shl (8 - keep))).toByte()
            }
            return InetAddress.getByAddress(bytes)
        }
    }
}

/** A literal IPv4 or IPv6 address, never a host name (no resolver lookups). */
fun parseAddress(text: String): InetAddress {
    val t = text.trim().removeSurrounding("[", "]")
    val literal = t.matches(Regex("""[0-9.]+""")) && t.count { it == '.' } == 3 || ':' in t
    require(literal && t.isNotEmpty()) { "not an IP address: $text" }
    return InetAddress.getByName(t)
}

/**
 * Where a query comes from, as PowerDNS passes it to the remote backend (`real-remote`): the EDNS
 * client subnet when the resolver sent one (`edns-subnet-processing`), otherwise the resolver's own
 * address as a host prefix.
 */
data class ClientSubnet(val subnet: Cidr) {
    companion object {
        fun parse(text: String?): ClientSubnet? = text?.takeIf { it.isNotBlank() }?.let { runCatching { ClientSubnet(Cidr.parse(it)) }.getOrNull() }
    }
}

/**
 * Maps a client to a region (a name the edges in `dns.edges` use, e.g. `eu`). Pluggable: [PrefixTable]
 * is the configured prefix table; a GeoIP database or latency map can implement the same interface.
 * `null` means unknown, and the default region applies.
 */
fun interface GeoLocator {
    fun regionOf(client: ClientSubnet): String?
}

/** Longest-prefix match over configured prefixes (`prefix <cidr> <region>` lines of the config). */
class PrefixTable(entries: List<Pair<Cidr, String>>) : GeoLocator {
    private val sorted = entries.sortedByDescending { it.first.prefix }

    override fun regionOf(client: ClientSubnet): String? =
        sorted.firstOrNull { (cidr, _) -> cidr.prefix <= client.subnet.prefix && client.subnet.address in cidr }?.second
}
