package net.blocks168.velocity.e2e

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.slf4j.Logger
import java.util.concurrent.TimeUnit

/**
 * Dev-only agent for the end-to-end tests (docs/testing.md, "Server-side assertions"): serves the
 * proxy's state as JSON over HTTP on `BLOCKS_E2E_AGENT_PORT` (deploy/compose.yaml sets it; without it
 * the agent stays off). Unauthenticated: never deploy it to production.
 *
 *     GET  /status    {version, players}
 *     GET  /servers   [{name, address, players}]
 *     GET  /players   [{uuid, name, server}]       server is null while not connected to a backend
 *     POST /send      {"player": "alice", "server": "survival"} -> {status, success, reason}
 *     POST /command   {"command": "network drain"} -> {success}     as the console
 */
class E2eAgentPlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
) {
    private var http: JsonHttpServer? = null

    data class Status(val version: String, val players: Int)
    data class ServerInfo(val name: String, val address: String, val players: Int)
    data class PlayerInfo(val uuid: String, val name: String, val server: String?)
    data class SendResult(val status: String, val success: Boolean, val reason: String?)
    data class CommandResult(val success: Boolean)

    @Subscribe
    fun onInit(event: ProxyInitializeEvent) {
        val port = System.getenv("BLOCKS_E2E_AGENT_PORT")?.toIntOrNull() ?: run {
            logger.info("BLOCKS_E2E_AGENT_PORT not set; e2e agent disabled")
            return
        }
        http = JsonHttpServer(port).also { routes(it); it.start() }
        logger.warn("e2e agent listening on port {} (dev only: unauthenticated proxy control)", port)
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        http?.close()
        http = null
    }

    private fun routes(http: JsonHttpServer) {
        http.get("/status") { Status(proxy.version.version, proxy.playerCount) }
        http.get("/servers") {
            proxy.allServers.map {
                ServerInfo(it.serverInfo.name, it.serverInfo.address.let { a -> "${a.hostString}:${a.port}" }, it.playersConnected.size)
            }
        }
        http.get("/players") {
            proxy.allPlayers.map { p ->
                PlayerInfo(p.uniqueId.toString(), p.username, p.currentServer.map { it.serverInfo.name }.orElse(null))
            }
        }
        http.post("/send") { body ->
            val name = body.get("player")?.asString ?: throw IllegalArgumentException("missing \"player\"")
            val target = body.get("server")?.asString ?: throw IllegalArgumentException("missing \"server\"")
            val player = proxy.getPlayer(name).orElseThrow { NoSuchElementException("player not online: $name") }
            val server = proxy.getServer(target).orElseThrow { NoSuchElementException("no such server: $target") }
            val result = player.createConnectionRequest(server).connect().get(30, TimeUnit.SECONDS)
            SendResult(
                status = result.status.name,
                success = result.isSuccessful,
                reason = result.reasonComponent.map { PlainTextComponentSerializer.plainText().serialize(it) }.orElse(null),
            )
        }
        http.post("/command") { body ->
            val command = body.get("command")?.asString ?: throw IllegalArgumentException("missing \"command\"")
            CommandResult(proxy.commandManager.executeAsync(proxy.consoleCommandSource, command.removePrefix("/")).get(30, TimeUnit.SECONDS))
        }
    }
}
