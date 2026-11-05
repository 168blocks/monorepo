package net.blocks168.dns

import java.net.InetAddress

/** `host:port`, e.g. a probe target `edge-1:25565` */
data class HostPort(val host: String, val port: Int) {
    override fun toString() = "$host:$port"

    companion object {
        fun parse(text: String): HostPort {
            val i = text.lastIndexOf(':')
            require(i > 0) { "expected host:port, got $text" }
            val port = text.substring(i + 1).toIntOrNull()
            require(port != null && port in 1..65535) { "bad port in $text" }
            return HostPort(text.substring(0, i).removeSurrounding("[", "]"), port)
        }
    }
}

/** A region and the regions to answer with, in order, when it has no healthy edge. */
data class Region(val name: String, val fallbacks: List<String>)

/** An authoritative name server of the zone: `<label>.<zone>` with its address (glue). */
data class NameServer(val label: String, val address: InetAddress)

/** An edge from the config, upserted into `dns.edges` at startup (see [EdgeStore.seed]). */
data class SeedEdge(val name: String, val region: String, val address: InetAddress, val port: Int, val probe: HostPort?)

/**
 * The service's configuration (deploy/dns/dns.conf, docs/dns.md). One directive per line, `#` starts a
 * comment, `${VAR}` / `${VAR:-default}` are replaced from the environment:
 *
 * ```
 * zone 168blocks.test                 # the zone PowerDNS is authoritative for
 * geo-name play                       # play.<zone>: nearest healthy edges by client region
 * ttl 60                              # TTL of the edge records (SOA/NS: 3600)
 * max-answers 4                       # at most this many edges per answer
 * hostmaster hostmaster               # SOA RNAME: hostmaster.<zone>
 * nameserver ns1 192.0.2.53           # NS ns1.<zone> with its A/AAAA; the first is the SOA MNAME
 * region eu us asia                   # region eu; falls back to us, then asia
 * default-region eu                   # clients no prefix matches
 * prefix 192.0.2.0/24 eu              # client subnets -> region (longest prefix wins)
 * edge edge-1 eu 203.0.113.1 25565 probe=edge-1:25565
 * ```
 */
data class DnsConfig(
    val zone: String,
    val geoName: String = "play",
    val ttl: Int = 60,
    val maxAnswers: Int = 4,
    val hostmaster: String = "hostmaster",
    val nameservers: List<NameServer>,
    val regions: List<Region> = emptyList(),
    val defaultRegion: String? = null,
    val prefixes: List<Pair<Cidr, String>> = emptyList(),
    val edges: List<SeedEdge> = emptyList(),
) {
    init {
        require(isHostname(zone)) { "zone: bad name $zone" }
        require(nameservers.isNotEmpty()) { "at least one nameserver" }
        require(ttl in 1..86400) { "ttl out of range" }
        require(maxAnswers >= 1) { "max-answers must be at least 1" }
        for (label in listOf(geoName, hostmaster) + nameservers.map { it.label } + regions.map { it.name }) {
            require(isLabel(label)) { "bad DNS label: $label" }
        }
        val reserved = reservedLabels
        for (r in regions) require(r.name !in reserved) { "region ${r.name} collides with another name of the zone" }
        require(regions.map { it.name }.toSet().size == regions.size) { "duplicate region" }
        require(edges.map { it.name }.toSet().size == edges.size) { "duplicate edge" }
        for (e in edges) {
            require(isLabel(e.name)) { "edge name ${e.name} is not a DNS label" }
            require(isLabel(e.region) && e.region !in reserved) { "edge ${e.name}: bad region ${e.region}" }
        }
    }

    /** Labels directly under the zone that are not region names */
    val reservedLabels: Set<String> get() = setOf(geoName, EDGE_LABEL, "_minecraft") + nameservers.map { it.label }

    companion object {
        /** `<edge>.edge.<zone>`: each edge's own address, the target of the SRV records */
        const val EDGE_LABEL = "edge"

        fun isLabel(s: String) = s.matches(Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"))
        fun isHostname(s: String) = s.isNotEmpty() && s.length <= 253 && s.split('.').all(::isLabel)

        fun parse(text: String, env: Map<String, String> = System.getenv()): DnsConfig {
            var zone: String? = null
            var geoName = "play"
            var ttl = 60
            var maxAnswers = 4
            var hostmaster = "hostmaster"
            var defaultRegion: String? = null
            val nameservers = mutableListOf<NameServer>()
            val regions = mutableListOf<Region>()
            val prefixes = mutableListOf<Pair<Cidr, String>>()
            val edges = mutableListOf<SeedEdge>()
            text.lines().forEachIndexed { i, raw ->
                val line = expand(raw.substringBefore('#'), env).trim()
                if (line.isEmpty()) return@forEachIndexed
                val words = line.split(Regex("\\s+"))
                val args = words.drop(1)
                fun fail(msg: String): Nothing = throw IllegalArgumentException("dns config line ${i + 1}: $msg: $raw")
                fun arity(n: IntRange) { if (args.size !in n) fail("${words[0]} takes ${n.first}..${n.last} arguments") }
                try {
                    when (words[0]) {
                        "zone" -> { arity(1..1); zone = args[0].lowercase().removeSuffix(".") }
                        "geo-name" -> { arity(1..1); geoName = args[0].lowercase() }
                        "ttl" -> { arity(1..1); ttl = args[0].toInt() }
                        "max-answers" -> { arity(1..1); maxAnswers = args[0].toInt() }
                        "hostmaster" -> { arity(1..1); hostmaster = args[0].lowercase() }
                        "nameserver" -> { arity(2..2); nameservers += NameServer(args[0].lowercase(), parseAddress(args[1])) }
                        "region" -> { arity(1..32); regions += Region(args[0].lowercase(), args.drop(1).map { it.lowercase() }) }
                        "default-region" -> { arity(1..1); defaultRegion = args[0].lowercase() }
                        "prefix" -> { arity(2..2); prefixes += Cidr.parse(args[0]) to args[1].lowercase() }
                        "edge" -> {
                            arity(4..5)
                            val probe = args.getOrNull(4)?.let {
                                if (!it.startsWith("probe=")) fail("expected probe=host:port")
                                HostPort.parse(it.removePrefix("probe="))
                            }
                            val port = args[3].toInt()
                            if (port !in 1..65535) fail("bad port")
                            edges += SeedEdge(args[0].lowercase(), args[1].lowercase(), parseAddress(args[2]), port, probe)
                        }
                        else -> fail("unknown directive")
                    }
                } catch (e: IllegalArgumentException) {
                    if (e.message?.startsWith("dns config line") == true) throw e
                    fail(e.message ?: e.toString())
                }
            }
            return DnsConfig(
                zone = zone ?: throw IllegalArgumentException("dns config: no zone"),
                geoName = geoName,
                ttl = ttl,
                maxAnswers = maxAnswers,
                hostmaster = hostmaster,
                nameservers = nameservers,
                regions = regions,
                defaultRegion = defaultRegion,
                prefixes = prefixes,
                edges = edges,
            )
        }

        private val VAR = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(:-([^}]*))?}""")

        /** `${NAME}` and `${NAME:-default}` from [env]; an unset variable without a default is an error */
        fun expand(s: String, env: Map<String, String>): String = VAR.replace(s) { m ->
            env[m.groupValues[1]]?.takeIf { it.isNotEmpty() }
                ?: m.groups[3]?.value
                ?: throw IllegalArgumentException("environment variable ${m.groupValues[1]} is not set")
        }
    }
}
