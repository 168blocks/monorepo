package dev.vxcc.b8.velocity.example

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyPingEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.proxy.ProxyServer
import dev.vxcc.alexs168.pgecs.db.Database
import dev.vxcc.alexs168.pgecs.PgEcsConfig
import org.slf4j.Logger

/**
 * Example plugin for the main Velocity proxy:
 * - marks the server list entry (MOTD) so end-to-end tests can see the plugin is active ([motd]),
 * - connects to the database through pgecs when `PGECS_URL` is set.
 */
class ExamplePlugin @Inject constructor(
    private val proxy: ProxyServer,
    private val logger: Logger,
) {
    private var ecs: Database? = null

    @Subscribe
    fun onInit(event: ProxyInitializeEvent) {
        if (System.getenv("PGECS_URL") != null) {
            ecs = runCatching { Database.connectWithRetry(PgEcsConfig.fromEnv().copy(applicationName = "velocity")) }
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
}
