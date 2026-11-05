package dev.vxcc.b8.mod.example

import dev.vxcc.b8.common.ServerHello
import dev.vxcc.alexs168.pgecs.db.Database
import dev.vxcc.alexs168.pgecs.PgEcsConfig
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

/**
 * Example common mod (server and client): logs joins, counts ticks through a Java mixin and connects to
 * the database through pgecs when `PGECS_URL` is set.
 */
object ExampleMod : ModInitializer {
    const val MOD_ID = "blocks168-example"
    private val log = LoggerFactory.getLogger(MOD_ID)

    /** Server ticks since start (incremented by MinecraftServerMixin) */
    @Volatile
    var ticks: Long = 0
        private set

    private var ecs: Database? = null

    /** This server's id in the network (`BLOCKS_SERVER_ID`, set by deploy/compose.yaml) */
    val serverId: String = System.getenv("BLOCKS_SERVER_ID") ?: "dev"

    override fun onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            if (System.getenv("PGECS_URL") != null) {
                ecs = runCatching { Database.connectWithRetry(PgEcsConfig.fromEnv().copy(applicationName = "mc-$serverId")) }
                    .onSuccess { log.info("pgecs connected: {}", it.ping()) }
                    .onFailure { log.error("pgecs connection failed", it) }
                    .getOrNull()
            }
            log.info("168blocks server '{}' started ({})", serverId, server.serverModName)
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { ecs?.close(); ecs = null }
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            log.info("{} joined {}: {}", handler.player.name.string, serverId, hello(server))
        }
    }

    fun hello(server: MinecraftServer) = ServerHello(serverId, modVersion(), server.playerCount)

    fun onServerTick(@Suppress("UNUSED_PARAMETER") server: MinecraftServer) {
        ticks++
    }

    private fun modVersion(): String =
        FabricLoader.getInstance().getModContainer(MOD_ID).map { it.metadata.version.friendlyString }.orElse("?")
}
