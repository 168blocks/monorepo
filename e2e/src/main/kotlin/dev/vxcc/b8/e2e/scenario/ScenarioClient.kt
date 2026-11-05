package dev.vxcc.b8.e2e.scenario

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import dev.vxcc.b8.e2e.Network
import dev.vxcc.b8.e2e.client.GameClient
import dev.vxcc.b8.e2e.client.GameClients
import dev.vxcc.b8.e2e.client.Screenshot
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A real, rendering game client (dev.vxcc.b8.e2e.client) in a scenario, started under its own
 * headless compositor with a fresh player name. Skips the scenario where clients cannot run (outside
 * the dev shell / e2e test task). On failure it attaches a last screenshot, the screenshots it took
 * and its log tail.
 */
suspend fun ScenarioScope.client(role: String, startupTimeout: Duration = 5.minutes): ScenarioClient {
    assumeTrue(GameClients.available(env), "real game clients are not available here (BLOCKS_CLIENT_ENV, BLOCKS_SWAY, BLOCKS_E2E_CLIENT_LAUNCH)")
    val name = uniqueName(role)
    log("$name: start game client")
    val clients = GameClients.fromEnv(env)
    val client = try {
        runInterruptible(Dispatchers.IO) { clients.start(name, startupTimeout.inWholeSeconds) }
    } catch (e: Throwable) {
        clients.close()
        throw e
    }
    log("$name: game client ready")
    return register(ScenarioClient(this, client, clients))
}

class ScenarioClient internal constructor(
    private val scenario: ScenarioScope,
    /** the underlying client, for everything the scenario wrapper does not cover */
    val client: GameClient,
    private val clients: GameClients,
) : ScenarioDriver {
    override val name: String get() = client.name

    private suspend fun <T> blocking(f: () -> T): T = runInterruptible(Dispatchers.IO) { f() }

    /** Connects through [endpoint] and waits until the world is rendered; retries while servers start. */
    suspend fun join(endpoint: Network.Endpoint, dimension: String? = null, timeout: Duration = 3.minutes) = apply {
        scenario.log("$name: join $endpoint")
        blocking { client.join(endpoint, dimension, timeout.inWholeMilliseconds) }
        scenario.log("$name: in the world")
    }

    /** Waits until this client sees [player] (a bot or another client) and returns where. */
    suspend fun awaitSees(player: ScenarioDriver, timeout: Duration = 60.seconds): GameClient.Position =
        blocking { client.awaitEntity(player.name, timeout.inWholeMilliseconds) }

    suspend fun awaitChat(text: String, timeout: Duration = 60.seconds) = blocking { client.awaitChat(text, timeoutMs = timeout.inWholeMilliseconds) }

    suspend fun chat(text: String) = apply { scenario.log("$name: chat '$text'"); blocking { client.chat(text) } }

    suspend fun look(yaw: Double, pitch: Double) = apply { blocking { client.look(yaw, pitch) } }

    suspend fun hud(visible: Boolean) = apply { blocking { client.hud(visible) } }

    suspend fun screenshot(label: String): Screenshot {
        scenario.log("$name: screenshot $label")
        return blocking { client.screenshot(label) }
    }

    override suspend fun diagnostics(out: Diagnostics) {
        // a last look at the screen, if the client still answers (its RPC blocks: bounded by a thread)
        runCatching { CompletableFuture.supplyAsync { client.screenshot("on-failure") }.get(20, TimeUnit.SECONDS) }
        out.text("client-$name.log", client.logTail(300))
        val dir = client.runDir.resolve("screenshots")
        if (Files.isDirectory(dir)) Files.list(dir).use { files ->
            files.filter { it.fileName.toString().endsWith(".png") }.forEach { out.file("client-$name-${it.fileName}", it) }
        }
    }

    override fun close() = clients.close()

    override fun toString() = name
}
