package dev.vxcc.b8.e2e.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Client of a Fabric server's e2e agent (mods/e2e-agent). [name] is the server's id in the network
 * (`lobby`, `survival`); its URL comes from `BLOCKS_E2E_AGENT_<NAME>` (tools/instance.sh).
 */
class ServerAgent(val name: String, baseUrl: String) : AgentClient(baseUrl) {
    @Serializable
    data class Status(val server: String, val minecraft: String, val tick: Int, val players: Int)

    @Serializable
    data class Player(
        val uuid: String, val name: String, val dimension: String,
        val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float,
        val gameMode: String,
    )

    @Serializable
    data class World(val id: String, val players: Int)

    @Serializable
    data class CommandResult(val success: Boolean, val result: Int, val output: List<String>) {
        val text: String get() = output.joinToString("\n")
    }

    suspend fun status(): Status = get("/status")
    suspend fun players(): List<Player> = get("/players")
    suspend fun player(name: String): Player? = players().firstOrNull { it.name == name }
    suspend fun worlds(): List<World> = get("/worlds")

    /** Runs a server command as the server console (with or without the leading `/`). */
    suspend fun command(command: String): CommandResult =
        post("/command", buildJsonObject { put("command", JsonPrimitive(command)) })

    suspend fun assertOnline(player: String, timeout: Duration = 10.seconds) =
        await(timeout, "$player online on $name") { player(player) != null }

    suspend fun assertOffline(player: String, timeout: Duration = 10.seconds) =
        await(timeout, "$player offline on $name") { player(player) == null }

    suspend fun assertPosition(player: String, x: Double, y: Double, z: Double, tolerance: Double = 0.1, timeout: Duration = 10.seconds) {
        var last: Player? = null
        try {
            await(timeout, "$player at ($x, $y, $z) on $name") {
                last = player(player)
                last?.let { abs(it.x - x) <= tolerance && abs(it.y - y) <= tolerance && abs(it.z - z) <= tolerance } ?: false
            }
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}; last seen: ${last ?: "offline"}")
        }
    }

    companion object {
        private val defaults = mapOf("lobby" to "http://127.0.0.1:28501", "survival" to "http://127.0.0.1:28502")

        fun fromEnv(name: String, env: Map<String, String> = System.getenv()) = ServerAgent(name, url(env, name, defaults[name]))
    }
}
