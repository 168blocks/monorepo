package net.blocks168.dns

import java.net.Inet4Address
import java.net.InetAddress

/** An enabled edge as last read from `dns.edges`. */
data class Edge(val name: String, val region: String, val address: InetAddress, val port: Int, val healthy: Boolean)

/** What the answers are computed from: the enabled edges and the zone's current SOA serial. */
data class Snapshot(val edges: List<Edge>, val serial: Long)

/**
 * A resource record as the PowerDNS remote backend wants it (`lookup` result). [content] is in zone file
 * presentation format; [scopeMask] is the EDNS client subnet scope (0: the answer does not depend on the
 * client).
 */
data class Record(val qname: String, val qtype: String, val content: String, val ttl: Int, val scopeMask: Int = 0)

/**
 * The zone's contents, computed per query from a [Snapshot] (pure; [RemoteBackend] serves it):
 *
 * | Name | Records |
 * | --- | --- |
 * | `<zone>` | SOA, NS |
 * | `<ns>.<zone>` | A/AAAA of the name server |
 * | `<geo-name>.<zone>` (`play`) | A/AAAA of the healthy edges of the client's region, or of the first fallback region with any; TXT |
 * | `<region>.<zone>` | A/AAAA of that region's healthy edges only (none: just the TXT); TXT |
 * | `_minecraft._tcp.<geo-name or region>.<zone>` | SRV per edge (port + `<edge>.edge.<zone>`), the same edges as above |
 * | `<edge>.edge.<zone>` | A/AAAA of that enabled edge |
 *
 * Unhealthy edges are never in the geo or region answers. Edges share an address only on a dev network
 * (published ports on 127.0.0.1): A/AAAA are deduplicated, the SRV records keep them apart.
 */
class ZoneAnswers(private val config: DnsConfig, private val geo: GeoLocator) {
    private val zone = config.zone
    private val regionsByName = config.regions.associateBy { it.name }

    /** Answers [qtype] (`ANY` for all types) for [qname] (with or without the trailing dot). */
    fun lookup(qname: String, qtype: String, client: ClientSubnet?, snapshot: Snapshot): List<Record> {
        val name = qname.lowercase().removeSuffix(".")
        val type = qtype.uppercase()
        val records = recordsOf(name, client, snapshot) ?: return emptyList()
        val out = if (type == "ANY") records else records.filter { it.qtype == type }
        return out.map { it.copy(qname = qname) }
    }

    /** Whether [qname] is in this zone */
    fun inZone(qname: String): Boolean {
        val name = qname.lowercase().removeSuffix(".")
        return name == zone || name.endsWith(".$zone")
    }

    fun soa(serial: Long) = Record(
        "$zone.", "SOA",
        "${config.nameservers.first().label}.$zone. ${config.hostmaster}.$zone. $serial 3600 600 604800 ${config.ttl}",
        STATIC_TTL,
    )

    private fun recordsOf(name: String, client: ClientSubnet?, snapshot: Snapshot): List<Record>? {
        if (name == zone) {
            return listOf(soa(snapshot.serial)) + config.nameservers.map { Record(name, "NS", "${it.label}.$zone.", STATIC_TTL) }
        }
        if (!name.endsWith(".$zone")) return null
        val rel = name.removeSuffix(".$zone")
        val labels = rel.split('.')

        config.nameservers.firstOrNull { it.label == rel }?.let { return listOf(address(name, it.address, STATIC_TTL)) }

        if (labels.size == 2 && labels[1] == DnsConfig.EDGE_LABEL) {
            val edge = snapshot.edges.firstOrNull { it.name == labels[0] } ?: return null
            return listOf(address(name, edge.address, config.ttl))
        }

        val srv = labels.size == 3 && labels[0] == "_minecraft" && labels[1] == "_tcp"
        val target = if (srv) labels[2] else if (labels.size == 1) labels[0] else return null
        val (edges, scope, txt) = when {
            target == config.geoName -> {
                val region = client?.let(geo::regionOf) ?: config.defaultRegion
                val (picked, edges) = nearest(region, snapshot)
                // the answer depends on the client: valid for exactly its subnet (always correct, even with
                // nested prefixes in the table)
                Triple(edges, client?.subnet?.prefix ?: 0, "client region ${region ?: "unknown"}, answering ${picked ?: "none"}")
            }
            target in regionNames(snapshot) -> {
                val edges = healthy(target, snapshot)
                val all = snapshot.edges.count { it.region == target }
                Triple(edges, 0, "region $target: ${edges.size} of $all edges healthy")
            }
            else -> return null
        }
        val chosen = edges.take(config.maxAnswers)
        return if (srv) {
            chosen.map { Record(name, "SRV", "0 10 ${it.port} ${it.name}.${DnsConfig.EDGE_LABEL}.$zone.", config.ttl, scope) }
        } else {
            chosen.map { it.address }.distinct().map { address(name, it, config.ttl).copy(scopeMask = scope) } +
                Record(name, "TXT", "\"${txt}\"", config.ttl, scope)
        }
    }

    /** Configured regions, then the regions only `dns.edges` knows (for example added by an operator) */
    fun regionNames(snapshot: Snapshot): List<String> {
        val reserved = config.reservedLabels
        return (config.regions.map { it.name } + snapshot.edges.map { it.region }.sorted())
            .distinct()
            .filter { DnsConfig.isLabel(it) && it !in reserved }
    }

    /**
     * The healthy edges nearest to [region]: its own, else those of its fallbacks in order, else of any
     * region (configured order first). Returns the region answered with.
     */
    fun nearest(region: String?, snapshot: Snapshot): Pair<String?, List<Edge>> {
        val order = buildList {
            if (region != null) {
                add(region)
                addAll(regionsByName[region]?.fallbacks.orEmpty())
            }
            addAll(regionNames(snapshot))
        }.distinct()
        for (r in order) {
            val edges = healthy(r, snapshot)
            if (edges.isNotEmpty()) return r to edges
        }
        return null to emptyList()
    }

    private fun healthy(region: String, snapshot: Snapshot) = snapshot.edges.filter { it.region == region && it.healthy }.sortedBy { it.name }

    private fun address(name: String, address: InetAddress, ttl: Int) =
        Record(name, if (address is Inet4Address) "A" else "AAAA", address.hostAddress, ttl)

    companion object {
        /** TTL of SOA, NS and name server addresses */
        const val STATIC_TTL = 3600
    }
}
