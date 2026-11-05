package dev.vxcc.b8.mod.cohosttest

import com.mojang.authlib.services.MinecraftServicesDiscoveryService
import com.mojang.serialization.Dynamic
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.server.Main
import net.minecraft.server.MinecraftServer
import net.minecraft.server.Services
import net.minecraft.server.WorldLoader
import net.minecraft.server.WorldStem
import net.minecraft.server.dedicated.DedicatedServer
import net.minecraft.server.dedicated.DedicatedServerProperties
import net.minecraft.server.dedicated.DedicatedServerSettings
import net.minecraft.server.jsonrpc.JsonRpc
import net.minecraft.server.notifications.NotificationManager
import net.minecraft.server.packs.repository.PackRepository
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.util.Util
import net.minecraft.util.datafix.DataFixers
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.storage.LevelDataAndDimensions
import net.minecraft.world.level.storage.LevelStorageSource
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional

/**
 * Starts a DedicatedServer with its own new world in [dir], in this JVM, the way Main does (minimal
 * stand-in for mods/creative-host, which shares datapacks and copies template worlds).
 */
object ExtraServer {
    private val loadOrCreateConfig = Main::class.java.getDeclaredMethod(
        "loadOrCreateConfig", DedicatedServerProperties::class.java, Dynamic::class.java, Boolean::class.javaPrimitiveType, PackRepository::class.java,
    ).apply { isAccessible = true }
    private val createNewWorldData = Main::class.java.getDeclaredMethod(
        "createNewWorldData", DedicatedServerSettings::class.java, WorldLoader.DataLoadContext::class.java, Registry::class.java,
        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
    ).apply { isAccessible = true }

    fun start(dir: Path, properties: Map<String, String>): DedicatedServer {
        Files.createDirectories(dir)
        val props = dir.resolve("server.properties")
        Files.writeString(props, properties.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
        val settings = DedicatedServerSettings(props)
        val services = Services.create(MinecraftServicesDiscoveryService.create(Proxy.NO_PROXY), dir.toFile())
        val notifications = NotificationManager()
        val rpc = JsonRpc.create(settings, notifications)
        val access = LevelStorageSource.createDefault(dir).validateAndCreateAccess("world")
        val packs = ServerPacksSource.createPackRepository(access)
        val init = loadOrCreateConfig.invoke(null, settings.properties, null, false, packs) as WorldLoader.InitConfig
        val stem = Util.blockUntilDone { executor ->
            WorldLoader.load(init, { ctx ->
                val dims = ctx.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM)
                @Suppress("UNCHECKED_CAST")
                createNewWorldData.invoke(null, settings, ctx, dims, false, false) as WorldLoader.DataLoadOutput<LevelDataAndDimensions.WorldDataAndGenSettings>
            }, ::WorldStem, Util.backgroundExecutor(), executor)
        }.get()
        access.saveDataTag(stem.worldDataAndGenSettings().data())
        val server = MinecraftServer.spin { thread ->
            DedicatedServer(thread, access, packs, stem, Optional.empty(), settings, DataFixers.getDataFixer(), services, rpc, notifications)
                .also { notifications.setServer(it) }
        }
        val deadline = System.nanoTime() + 120_000_000_000
        while (!server.isReady) {
            check(server.isRunning || System.nanoTime() < deadline) { "extra server in $dir did not start" }
            check(System.nanoTime() < deadline) { "extra server in $dir did not start within 120 s" }
            if (!server.isRunning && !server.isReady) error("extra server in $dir stopped while starting")
            Thread.sleep(10)
        }
        return server
    }
}
