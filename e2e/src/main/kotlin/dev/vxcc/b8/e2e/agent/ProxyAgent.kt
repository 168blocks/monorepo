package dev.vxcc.b8.e2e.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Client of the Velocity proxy's e2e agent (velocity/e2e-agent); its URL comes from
 * `BLOCKS_E2E_AGENT_VELOCITY` (tools/instance.sh).
 */
class ProxyAgent(baseUrl: String) : AgentClient(baseUrl) {
    @Serializable
    data class Status(val version: String, val players: Int)

    @Serializable
    data class Server(val name: String, val address: String, val players: Int)

    /** [server] is null while the player is not connected to a backend */
    @Serializable
    data class Player(val uuid: String, val name: String, val server: String?)

    /** [status] is Velocity's `ConnectionRequestBuilder.Status` (SUCCESS, ALREADY_CONNECTED, ...) */
    @Serializable
    data class SendResult(val status: String, val success: Boolean, val reason: String?)

    suspend fun status(): Status = get("/status")
    suspend fun servers(): List<Server> = get("/servers")
    suspend fun players(): List<Player> = get("/players")
    suspend fun player(name: String): Player? = players().firstOrNull { it.name == name }

    /** Moves [player] to backend [server], like `/server`; returns once Velocity has an answer. */
    suspend fun send(player: String, server: String): SendResult =
        post("/send", buildJsonObject { put("player", JsonPrimitive(player)); put("server", JsonPrimitive(server)) })

    suspend fun assertOn(player: String, server: String, timeout: Duration = 10.seconds) =
        await(timeout, "$player on $server") { player(player)?.server == server }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()) = ProxyAgent(url(env, "velocity", "http://127.0.0.1:28500"))
    }
}
