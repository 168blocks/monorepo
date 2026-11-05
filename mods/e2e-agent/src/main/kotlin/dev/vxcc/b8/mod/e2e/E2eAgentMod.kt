package dev.vxcc.b8.mod.e2e

import com.google.gson.JsonObject
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.commands.CommandResultCallback
import net.minecraft.commands.CommandSource
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Dev-only agent for the end-to-end tests (docs/testing.md, "Server-side assertions"): serves this
 * server's state as JSON over HTTP on `BLOCKS_E2E_AGENT_PORT` (deploy/compose.yaml sets it; without it
 * the agent stays off). Unauthenticated and able to run any command: never deploy it to production.
 *
 *     GET  /status    {server, minecraft, tick, players}
 *     GET  /players   [{uuid, name, dimension, x, y, z, yaw, pitch, gameMode}]
 *     GET  /worlds    [{id, players}]
 *     POST /command   {"command": "list"} -> {success, result, output: [lines]}
 */
object E2eAgentMod : DedicatedServerModInitializer {
    const val MOD_ID = "blocks168-e2e-agent"
    private val log = LoggerFactory.getLogger(MOD_ID)
    private const val TIMEOUT_SECONDS = 10L

    /** This server's id in the network (`BLOCKS_SERVER_ID`, set by deploy/compose.yaml) */
    private val serverId: String = System.getenv("BLOCKS_SERVER_ID") ?: "dev"

    private var http: JsonHttpServer? = null

    data class Status(val server: String, val minecraft: String, val tick: Int, val players: Int)
    data class PlayerInfo(
        val uuid: String, val name: String, val dimension: String,
        val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float,
        val gameMode: String,
    )
    data class WorldInfo(val id: String, val players: Int)
    data class CommandResult(val success: Boolean, val result: Int, val output: List<String>)

    override fun onInitializeServer() {
        val port = System.getenv("BLOCKS_E2E_AGENT_PORT")?.toIntOrNull() ?: run {
            log.info("BLOCKS_E2E_AGENT_PORT not set; e2e agent disabled")
            return
        }
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            http = JsonHttpServer(port).also { routes(it, server); it.start() }
            log.warn("e2e agent listening on port {} (dev only: unauthenticated server control)", port)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { http?.close(); http = null }
    }

    private fun routes(http: JsonHttpServer, server: MinecraftServer) {
        http.get("/status") {
            onServerThread(server) { Status(serverId, server.serverVersion, server.tickCount, server.playerCount) }
        }
        http.get("/players") {
            onServerThread(server) {
                server.playerList.players.map { p ->
                    PlayerInfo(
                        uuid = p.uuid.toString(), name = p.gameProfile.name,
                        dimension = p.level().dimension().identifier().toString(),
                        x = p.x, y = p.y, z = p.z, yaw = p.yRot, pitch = p.xRot,
                        gameMode = p.gameMode().serializedName,
                    )
                }
            }
        }
        http.get("/worlds") {
            onServerThread(server) {
                server.allLevels.map { WorldInfo(it.dimension().identifier().toString(), it.players().size) }
            }
        }
        http.post("/command") { body -> runCommand(server, body) }
    }

    private fun runCommand(server: MinecraftServer, body: JsonObject): CommandResult {
        val command = body.get("command")?.takeIf { it.isJsonPrimitive }?.asString?.removePrefix("/")
        require(!command.isNullOrBlank()) { "missing \"command\"" }
        val output = CopyOnWriteArrayList<String>()
        val capture = object : CommandSource {
            override fun sendSystemMessage(message: Component) { output += message.string }
            override fun acceptsSuccess() = true
            override fun acceptsFailure() = true
            override fun shouldInformAdmins() = false
        }
        var success = false
        var result = 0
        onServerThread(server) {
            val source = server.createCommandSourceStack()
                .withSource(capture)
                .withCallback(CommandResultCallback { ok, r -> success = ok; result = r })
            server.commands.performPrefixedCommand(source, command)
        }
        log.info("e2e agent ran /{}: success={} result={}", command, success, result)
        return CommandResult(success, result, output.toList())
    }

    private fun <T> onServerThread(server: MinecraftServer, block: () -> T): T =
        CompletableFuture.supplyAsync(block, server).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
}
