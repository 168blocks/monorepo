package net.blocks168.e2e.scenario

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.blocks168.e2e.Network
import net.blocks168.e2e.agent.ProxyAgent
import net.blocks168.e2e.agent.ServerAgent
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.opentest4j.TestAbortedException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * A JUnit test that runs a [scenario] against the network: tagged `network` (only runs with
 * `tools/network.sh test`) and with [ScenarioExtension], which attaches the diagnostics of a failed
 * scenario to the test report.
 *
 * ```kotlin
 * @NetworkScenario
 * fun `second player sees the first`() = scenario {
 *     val alice = bot("alice").connect(edge(1))
 *     ...
 * }
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Test
@Tag("network")
@ExtendWith(ScenarioExtension::class)
annotation class NetworkScenario

/**
 * Something a scenario drives and cleans up: bots now, real game clients (`client("bob")`, see
 * e2e/.../client) later. [diagnostics] is called when the scenario fails; [close] always, at the end.
 */
interface ScenarioDriver : AutoCloseable {
    val name: String
    suspend fun diagnostics(out: Diagnostics) {}
}

/**
 * Runs [block] as one end-to-end scenario: a coroutine scope (players act in parallel with `launch` /
 * `async`), an overall [timeout], fresh player names, and on failure a diagnostics bundle (timeline,
 * bot logs, agent snapshots, container logs) attached to the JUnit report (see [ScenarioExtension]).
 *
 * [needsServers]: skip (not fail) when the Minecraft servers cannot run, i.e. without
 * `BLOCKS_ACCEPT_EULA=true`.
 */
fun scenario(
    timeout: Duration = 5.minutes,
    needsServers: Boolean = true,
    network: Network = Network.fromEnv(),
    env: Map<String, String> = System.getenv(),
    block: suspend ScenarioScope.() -> Unit,
) {
    if (needsServers) assumeTrue(env["BLOCKS_ACCEPT_EULA"] == "true", "Minecraft servers not running (BLOCKS_ACCEPT_EULA is not true)")
    val test = ScenarioExtension.current()
    val s = ScenarioScope(network, env, test?.displayName ?: "scenario")
    try {
        runBlocking(Dispatchers.IO) {
            withTimeout(timeout) { coroutineScope { s.scope = this; s.block() } }
        }
    } catch (e: Throwable) {
        if (e !is TestAbortedException) {
            s.log("FAILED: $e")
            val dir = runCatching { runBlocking { Diagnostics.collect(s, e, test) } }
                .onFailure { e.addSuppressed(it) }.getOrNull()
            dir?.let { println("scenario diagnostics: $it") }
        }
        throw e
    } finally {
        s.closeAll()
    }
}

/** The receiver of a [scenario] block. */
class ScenarioScope internal constructor(
    val network: Network,
    internal val env: Map<String, String>,
    val title: String,
) : CoroutineScope {
    internal lateinit var scope: CoroutineScope

    /** The scenario's own coroutine scope: `launch { }` / `async { }` run players in parallel. */
    override val coroutineContext get() = scope.coroutineContext
    private val start = TimeSource.Monotonic.markNow()
    private val timeline = ArrayList<String>()
    private val drivers = ArrayList<ScenarioDriver>()
    private val servers = LinkedHashMap<String, ServerAgent>()
    private var proxyAgent: ProxyAgent? = null

    /** Lines logged by the scenario and its drivers, with the time since the start. */
    fun timeline(): List<String> = synchronized(timeline) { timeline.toList() }

    fun log(line: String) {
        val t = start.elapsedNow().inWholeMilliseconds / 1000.0
        val entry = "[%7.2fs] %s".format(t, line)
        synchronized(timeline) { timeline += entry }
        println(entry)
    }

    /** Edge [n] (counted from 1) of the network under test: what players connect to. */
    fun edge(n: Int): Network.Endpoint = network.edge(n)

    /** A new bot with a fresh player name made from [role] (e.g. `alice_3k9q`); not connected yet. */
    fun bot(role: String): ScenarioBot = register(ScenarioBot(this, uniqueName(role)))

    /** The e2e agent of Fabric server [name] (`lobby`, `survival`), once it answers. */
    suspend fun server(name: String, readyTimeout: Duration = 3.minutes): ServerAgent {
        val agent = synchronized(servers) { servers.getOrPut(name) { ServerAgent.fromEnv(name, env) } }
        agent.awaitReady(readyTimeout)
        return agent
    }

    /** The Velocity e2e agent, once it answers. */
    suspend fun proxy(readyTimeout: Duration = 1.minutes): ProxyAgent {
        val agent = synchronized(this) { proxyAgent ?: ProxyAgent.fromEnv(env).also { proxyAgent = it } }
        agent.awaitReady(readyTimeout)
        return agent
    }

    /**
     * Registers a driver: it is closed at the end and asked for diagnostics on failure. This is the
     * hook for other kinds of players, e.g. real game clients:
     * `fun ScenarioScope.client(role: String) = register(GameClientDriver(...))`.
     */
    fun <T : ScenarioDriver> register(driver: T): T {
        synchronized(drivers) { drivers += driver }
        return driver
    }

    /** A fresh player name: at most 16 characters, unique in this JVM, random across runs. */
    fun uniqueName(role: String): String = Companion.uniqueName(role)

    internal fun drivers(): List<ScenarioDriver> = synchronized(drivers) { drivers.toList() }
    internal fun serverAgents(): Map<String, ServerAgent> = synchronized(servers) { servers.toMap() }
    internal fun proxyAgent(): ProxyAgent? = proxyAgent

    internal fun closeAll() {
        for (d in drivers().reversed()) runCatching { d.close() }
        servers.values.forEach { runCatching { it.close() } }
        proxyAgent?.let { runCatching { it.close() } }
    }

    companion object {
        private val used = ConcurrentHashMap.newKeySet<String>()

        fun uniqueName(role: String): String {
            val base = role.filter { it.isLetterOrDigit() || it == '_' }.take(9).ifEmpty { "p" }
            while (true) {
                val name = base + "_" + Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36 * 36 * 36 * 36), 36).padStart(5, '0')
                if (used.add(name)) return name
            }
        }
    }
}
