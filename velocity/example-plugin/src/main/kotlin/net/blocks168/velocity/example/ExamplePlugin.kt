package net.blocks168.velocity.example

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import net.blocks168.common.ServerHello
import net.blocks168.pgecs.PgEcs
import net.blocks168.pgecs.PgEcsConfig
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Example plugin for the main Velocity proxy:
 * - marks the server list entry (MOTD) so end-to-end tests can see the plugin is active ([motd]),
 * - receives [ServerHello] from the Fabric servers over a plugin message channel,
 * - connects to the database through pgecs when `PGECS_URL` is set.
 */
class ExamplePlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
) {
    private val helloChannel = MinecraftChannelIdentifier.from(ServerHello.CHANNEL)
    private var ecs: PgEcs? = null

    /** Last [ServerHello] per backend server name */
    val hellos = ConcurrentHashMap<String, ServerHello>()

    @Subscribe
    fun onInit(event: ProxyInitializeEvent) {
        proxy.channelRegistrar.register(helloChannel)
        if (System.getenv("PGECS_URL") != null) {
            ecs = runCatching { PgEcs.connectWithRetry(PgEcsConfig.fromEnv()) }
                .onSuccess { logger.info("pgecs connected: {}", it.ping()) }
                .onFailure { logger.error("pgecs connection failed", it) }
                .getOrNull()
        }
        logger.info("168blocks example plugin initialized")
    }

    @Subscribe
    fun onShutdown(event: ProxyShutdownEvent) {
        ecs?.close()
    }

    @Subscribe
    fun onPing(event: ProxyPingEvent) {
        event.ping = event.ping.asBuilder().description(motd(proxy.playerCount)).build()
    }

    @Subscribe
    fun onLogin(event: PostLoginEvent) {
        logger.info("{} joined the network", event.player.username)
    }

    @Subscribe
    fun onPluginMessage(event: PluginMessageEvent) {
        if (event.identifier != helloChannel) return
        // only backends may send it; never forward it to the client
        event.result = PluginMessageEvent.ForwardResult.handled()
        val source = event.source as? ServerConnection ?: return
        val hello = runCatching { ServerHello.decode(event.data) }.getOrElse {
            logger.warn("bad {} from {}", ServerHello.CHANNEL, source.serverInfo.name, it); return
        }
        hellos[source.serverInfo.name] = hello
        logger.info("hello from {}: {}", source.serverInfo.name, hello)
    }
}
