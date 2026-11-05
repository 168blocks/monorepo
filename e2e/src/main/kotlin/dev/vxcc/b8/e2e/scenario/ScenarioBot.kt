package dev.vxcc.b8.e2e.scenario

import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import dev.vxcc.b8.e2e.Network
import dev.vxcc.b8.e2e.agent.ProxyAgent
import dev.vxcc.b8.e2e.agent.ServerAgent
import dev.vxcc.b8.e2e.bot.Bot
import dev.vxcc.b8.e2e.bot.BotException
import dev.vxcc.b8.e2e.bot.ChatMessage
import dev.vxcc.b8.e2e.bot.Vec3
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A protocol-level [Bot] inside a scenario: suspending, cancellable versions of its actions and
 * awaits (the bot itself blocks), its log kept for the diagnostics, closed when the scenario ends.
 */
class ScenarioBot internal constructor(private val scenario: ScenarioScope, override val name: String) : ScenarioDriver {
    private val logLines = ArrayList<String>()
    val bot = Bot(name, log = ::log)

    val uuid: UUID get() = bot.uuid
    val position: Vec3 get() = bot.position
    val dimension: String? get() = bot.dimension
    val gameMode get() = bot.gameMode
    val joins get() = bot.joins

    private fun log(line: String) {
        synchronized(logLines) { logLines += line }
        scenario.log(line)
    }

    private suspend fun <T> blocking(f: () -> T): T = runInterruptible(Dispatchers.IO) { f() }

    /**
     * Connects through [endpoint] (usually `edge(n)`) and waits until the bot is in a world. Retries
     * for [retryFor] while the proxy cannot reach a server yet (servers still starting).
     */
    suspend fun connect(endpoint: Network.Endpoint, retryFor: Duration = 3.minutes): ScenarioBot {
        scenario.log("$name: connect to $endpoint")
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            try {
                blocking { bot.connect(endpoint, 60.seconds) }
                return this
            } catch (e: Exception) {
                if (e !is BotException && e !is IOException) throw e
                if (start.elapsedNow() > retryFor) throw e
                scenario.log("$name: join failed, retrying: ${e.message}")
                delay(3.seconds)
            }
        }
    }

    suspend fun awaitWorld(dimension: String, timeout: Duration = 10.seconds) = apply { blocking { bot.awaitWorld(dimension, timeout) } }

    /** Waits for the [n]th play login (n-1 server switches). */
    suspend fun awaitJoins(n: Int, timeout: Duration = 30.seconds) = apply { blocking { bot.awaitJoins(n, timeout) } }

    /** Walks to [target] (see [Bot.moveTo]: the bot does not know the terrain). */
    suspend fun moveTo(target: Vec3, onGround: Boolean = true) = apply {
        scenario.log("$name: move ${bot.position} -> $target")
        blocking { bot.moveTo(target, onGround = onGround) }
    }

    suspend fun moveBy(dx: Double = 0.0, dy: Double = 0.0, dz: Double = 0.0, onGround: Boolean = true) =
        moveTo(position + Vec3(dx, dy, dz), onGround)

    fun chat(message: String) = apply { scenario.log("$name: chat '$message'"); bot.chat(message) }

    /** A command without the leading slash, e.g. `server survival` (Velocity). */
    fun command(command: String) = apply { scenario.log("$name: /$command"); bot.command(command) }

    suspend fun awaitChat(timeout: Duration = 10.seconds, predicate: (ChatMessage) -> Boolean): ChatMessage =
        blocking { bot.awaitChat(timeout, predicate) }

    /** Waits until this bot sees [other] within [tolerance] of [at] (default: where [other] is). */
    suspend fun awaitSees(other: ScenarioBot, at: Vec3 = other.position, tolerance: Double = 0.01, timeout: Duration = 10.seconds): Vec3 =
        blocking { bot.awaitPlayerAt(other.uuid, at, tolerance, timeout) }

    /**
     * `/server <server>` through Velocity, retried while [server] is still starting. Done only when
     * the proxy agent reports the bot on [server]: a new login alone can also be Velocity's failover
     * back to the lobby when [server] turned the bot away (#43).
     */
    suspend fun switchTo(server: String, retryFor: Duration = 3.minutes) = apply {
        val proxy = scenario.proxy()
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            val target = bot.joins + 1
            command("server $server")
            val joined = try {
                awaitJoins(target, 20.seconds)
                true
            } catch (e: BotException) {
                if (!bot.connected) throw e
                false
            }
            if (joined && runCatching { proxy.assertOn(name, server, 5.seconds) }.isSuccess) {
                scenario.log("$name: on $server")
                return@apply
            }
            if (start.elapsedNow() > retryFor) throw AssertionError("$name did not get onto $server within $retryFor; proxy says ${proxy.player(name)}, last message: ${bot.messages().lastOrNull()?.text}")
            scenario.log("$name: switch to $server not done yet (proxy: ${proxy.player(name)?.server}, last message: ${bot.messages().lastOrNull()?.text})")
            delay(3.seconds)
        }
    }

    fun disconnect() = apply { scenario.log("$name: disconnect"); bot.disconnect() }

    override suspend fun diagnostics(out: Diagnostics) {
        val state = "name=$name uuid=$uuid phase=${bot.phase} dimension=${bot.dimension} gameMode=${bot.gameMode} " +
            "joins=${bot.joins} teleports=${bot.teleports} disconnect=${bot.disconnectReason}\n" +
            "players seen: ${bot.players()}\nmessages: ${bot.messages().joinToString("\n  ", "\n  ")}\n"
        out.text("bot-$name.log", state + "\n" + synchronized(logLines) { logLines.joinToString("\n") })
    }

    override fun close() = bot.close()

    override fun toString() = name
}

// --- agent assertions by bot, so scenarios never spell player names ---

suspend fun ServerAgent.assertOnline(bot: ScenarioBot, timeout: Duration = 10.seconds) = assertOnline(bot.name, timeout)
suspend fun ServerAgent.assertOffline(bot: ScenarioBot, timeout: Duration = 10.seconds) = assertOffline(bot.name, timeout)
suspend fun ServerAgent.assertPosition(bot: ScenarioBot, at: Vec3, tolerance: Double = 0.1, timeout: Duration = 10.seconds) =
    assertPosition(bot.name, at.x, at.y, at.z, tolerance, timeout)
suspend fun ProxyAgent.assertOn(bot: ScenarioBot, server: String, timeout: Duration = 10.seconds) = assertOn(bot.name, server, timeout)
suspend fun ProxyAgent.send(bot: ScenarioBot, server: String) = send(bot.name, server)
