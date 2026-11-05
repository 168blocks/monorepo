package net.blocks168.dns

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import net.blocks168.pgecs.PgEcsConfig
import net.blocks168.pgecs.db.Database
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The player DNS service (docs/dns.md): PowerDNS's remote backend, answering from the edges in
 * PostgreSQL.
 *
 * Environment:
 * - `PGECS_URL`, `PGECS_USER`: database (role `blocks`, from the core namespace; see [PgEcsConfig])
 * - `BLOCKS_DNS_CONFIG`: config file (default `dns.conf` in the working directory), see [DnsConfig]
 * - `BLOCKS_DNS_LISTEN`: HTTP address for PowerDNS, default `127.0.0.1:8053` (never public)
 * - `BLOCKS_DNS_STALE_SECONDS`: an edge without a heartbeat for this long is unhealthy (default 30)
 * - `BLOCKS_DNS_PROBE_SECONDS`: status-ping edges with a probe address this often (default 10; 0: off)
 * - `BLOCKS_DNS_REFRESH_MILLIS`: re-read the edges this often (default 1000)
 */
fun main() {
    val log = LoggerFactory.getLogger("net.blocks168.dns")
    val env = System.getenv()
    fun seconds(name: String, default: Long) = Duration.ofMillis(((env[name]?.toDouble() ?: default.toDouble()) * 1000).toLong())
    val config = DnsConfig.parse(File(env["BLOCKS_DNS_CONFIG"] ?: "dns.conf").readText(), env)
    val listen = HostPort.parse(env["BLOCKS_DNS_LISTEN"] ?: "127.0.0.1:8053")
    val stale = seconds("BLOCKS_DNS_STALE_SECONDS", 30)
    val probeInterval = seconds("BLOCKS_DNS_PROBE_SECONDS", 10)
    val refresh = Duration.ofMillis(env["BLOCKS_DNS_REFRESH_MILLIS"]?.toLong() ?: 1000)

    val db = Database.connectWithRetry(PgEcsConfig.fromEnv().copy(applicationName = "dns"))
    val store = EdgeStore(db)
    store.migrate()
    store.seed(config.edges)
    log.info("zone {}: {} configured edges, stale after {}, probes every {}", config.zone, config.edges.size, stale, probeInterval)

    if (!probeInterval.isZero) Prober(store, probeInterval).start()
    val edges = Edges.from(store, stale).also { it.refresh() }
    Executors.newSingleThreadScheduledExecutor { Thread(it, "dns-refresh").apply { isDaemon = true } }
        .scheduleWithFixedDelay({ edges.refresh() }, refresh.toMillis(), refresh.toMillis(), TimeUnit.MILLISECONDS)

    val answers = ZoneAnswers(config, PrefixTable(config.prefixes))
    embeddedServer(Netty, port = listen.port, host = listen.host) {
        dnsModule(config, answers, edges)
    }.start(wait = true)
}
