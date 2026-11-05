package dev.vxcc.b8.e2e

import kotlinx.coroutines.runBlocking
import dev.vxcc.b8.e2e.agent.ServerAgent
import dev.vxcc.b8.e2e.bot.Bot
import dev.vxcc.b8.e2e.bot.BotException
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The WireGuard layout of the local network (deploy/compose.yaml), looked at from inside: from the core
 * namespace, from an edge's namespace, and from a container on `wan` without a tunnel.
 * Design: https://claude.ai/artifact/WDswfCSv1YSxbGT7HWgtzd (e2e checks 4-6).
 */
@Tag("network")
class TunnelTest {
    private val compose = ComposeNetwork.fromEnv()
    private val network = Network.fromEnv()
    private val eula get() = System.getenv("BLOCKS_ACCEPT_EULA") == "true"

    private fun compose(): ComposeNetwork {
        assumeTrue(compose != null, "not the local compose network (BLOCKS_COMPOSE_PROJECT unset)")
        return compose!!
    }

    private fun ComposeNetwork.psql(network: String, host: String, role: String, sql: String) =
        run(network, postgresImage, "-h", host, "-U", role, "-d", "blocks", "-Atc", sql, entrypoint = "psql")

    @Test
    fun coreNamespaceReachesPostgresOverLocalhost() {
        val c = compose()
        val blocks = c.psql(c.namespaceOf("core"), "127.0.0.1", "blocks", "select current_user")
        assertEquals(Pair(true, "blocks"), blocks.ok to blocks.output, blocks.output)
        // the edge and ops roles only come from their tunnel subnets
        val edge = c.psql(c.namespaceOf("core"), "127.0.0.1", "blocks_edge", "select 1")
        assertFalse(edge.ok, "blocks_edge from localhost: ${edge.output}")
    }

    /** Check 4: on `wan` without a tunnel, PostgreSQL and the Fabric servers are unreachable. */
    @Test
    fun noTunnelNoAccess() {
        val c = compose()
        // control: core is there (its published backend port is the public one)
        assertTrue(c.canConnect(c.wan, "core", 8080), "core's public backend port not reachable from wan")
        for (host in listOf("core", "10.168.0.1")) {
            for (port in listOf(5432, 25601, 25602)) {
                assertFalse(c.canConnect(c.wan, host, port), "$host:$port reachable from wan without a tunnel")
            }
        }
    }

    /** Check 6, and: an edge reaches PostgreSQL and the Fabric servers only through its tunnel. */
    @Test
    fun edgeRoleComesFromItsTunnelAddress() {
        val c = compose()
        val edge2 = c.namespaceOf("edge-2")
        val asEdge = c.psql(edge2, "10.168.0.1", "blocks_edge", "select current_user || ' ' || host(inet_client_addr())")
        // no NAT between tunnel and PostgreSQL: it sees the edge's tunnel address
        assertEquals(Pair(true, "blocks_edge 10.168.1.2"), asEdge.ok to asEdge.output, asEdge.output)
        val asCore = c.psql(edge2, "10.168.0.1", "blocks", "select 1")
        assertFalse(asCore.ok, "an edge connected as blocks: ${asCore.output}")
        assertContains(asCore.output, "pg_hba.conf rejects connection for host \"10.168.1.2\", user \"blocks\"")

        // core's wan address has nothing but WireGuard (and the published ports) for an edge either
        for (port in listOf(5432, 25601, 25602)) assertFalse(c.canConnect(edge2, "core", port), "core:$port outside the tunnel")
        if (eula) {
            runBlocking { ServerAgent.fromEnv("lobby").use { it.awaitReady(5.minutes) } }
            assertTrue(c.canConnect(edge2, "10.168.0.1", 25601), "lobby not reachable through the tunnel")
        }
    }

    /** Check 5: with its peer removed from the hub, an edge refuses logins; restored, it lets players in again. */
    @Test
    fun removedPeerRefusesLogins() {
        val c = compose()
        assumeTrue(eula, "Minecraft EULA not accepted (BLOCKS_ACCEPT_EULA)")
        runBlocking { ServerAgent.fromEnv("lobby").use { it.awaitReady(5.minutes) } }
        val edge = network.edge(2)
        fun name() = "wg_" + Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36 * 36 * 36 * 36), 36)
        fun login(): Result<Unit> = runCatching { Bot(name()).use { it.connect(edge, 30.seconds) } }

        // the servers may still be starting behind the edge
        awaitLogin("edge 2 before the test", ::login)

        val allowed = c.exec("core", "wg", "show", "wg0", "allowed-ips")
        assertTrue(allowed.ok, allowed.output)
        val key = allowed.output.lines().map { it.split('\t') }.single { it.getOrNull(1) == "10.168.1.2/32" }[0]
        val removed = c.exec("core", "wg", "set", "wg0", "peer", key, "remove")
        assertTrue(removed.ok, removed.output)
        try {
            val revoked = login()
            assertTrue(revoked.isFailure, "edge 2 let a player in without its tunnel")
            println("edge 2 without its peer: ${revoked.exceptionOrNull()?.message}")
        } finally {
            val restored = c.exec("core", "wg", "set", "wg0", "peer", key, "allowed-ips", "10.168.1.2/32")
            assertTrue(restored.ok, restored.output)
        }
        // the edge's next keepalive (every 25 s) brings the tunnel back
        awaitLogin("edge 2 after its peer was restored", ::login)
    }

    private fun awaitLogin(what: String, login: () -> Result<Unit>) {
        val end = System.nanoTime() + 180.seconds.inWholeNanoseconds
        while (true) {
            val r = login()
            if (r.isSuccess) return
            val e = r.exceptionOrNull()!!
            if (e !is BotException && e !is IOException) throw e
            if (System.nanoTime() > end) throw AssertionError("$what: no login: ${e.message}", e)
            Thread.sleep(3000)
        }
    }
}
