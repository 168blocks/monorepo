package dev.vxcc.b8.mod.creativehost

import com.mojang.authlib.services.MinecraftServicesDiscoveryService
import com.mojang.serialization.Dynamic
import net.minecraft.core.registries.Registries
import net.minecraft.server.Main
import net.minecraft.server.MinecraftServer
import net.minecraft.server.Services
import net.minecraft.server.WorldLoader
import net.minecraft.server.WorldStem
import net.minecraft.server.dedicated.DedicatedServer
import net.minecraft.server.dedicated.DedicatedServerSettings
import net.minecraft.server.jsonrpc.JsonRpc
import net.minecraft.server.notifications.NotificationManager
import net.minecraft.server.packs.repository.PackRepository
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.util.Util
import net.minecraft.util.datafix.DataFixers
import net.minecraft.util.worldupdate.UpgradeProgress
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.storage.LevelStorageSource
import org.slf4j.LoggerFactory
import java.net.Proxy
import java.nio.file.Path
import java.util.Optional
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * Starts one [DedicatedServer] the way `net.minecraft.server.Main` does, in this JVM: its own directory,
 * settings, services, level storage and pack repository, with a [WorldStem] either shared with the host
 * ([SharedStem]) or loaded like `Main` loads one.
 */
internal class HostedServerFactory(private val host: MinecraftServer, private val hostDirectory: Path) {
    private val log = LoggerFactory.getLogger(CreativeHostMod.MOD_ID)
    private val shared by lazy { SharedStem(host) }

    fun start(spec: HostedServerSpec): HostedServer {
        val t0 = TimeSource.Monotonic.markNow()
        val dir = spec.directory.toAbsolutePath()
        val world = dir.resolve(WORLD)
        spec.template?.let { if (WorldTemplates.copyIfAbsent(it, world)) log.info("{}: copied template {}", spec.id, it) }
        val propertiesFile = dir.resolve("server.properties")
        ServerProperties.write(
            propertiesFile, hostDirectory.resolve("server.properties"),
            spec.properties + mapOf("server-port" to spec.port.toString(), "level-name" to WORLD),
        )
        val settings = DedicatedServerSettings(propertiesFile)
        val services = Services.create(MinecraftServicesDiscoveryService.create(Proxy.NO_PROXY), dir.toFile())
        val notifications = NotificationManager()
        val jsonRpc = JsonRpc.create(settings, notifications)
        val access = LevelStorageSource.createDefault(dir).validateAndCreateAccess(WORLD)
        var stem: WorldStem? = null
        var ownsAccess = true
        try {
            val levelTag = readLevel(access, notifications)
            val packs = ServerPacksSource.createPackRepository(access)
            val t1 = TimeSource.Monotonic.markNow()
            val share = spec.shareResources && selectsHostPacks(packs, levelTag, settings)
            stem = when {
                share && levelTag != null -> shared.forExistingWorld(access, levelTag)
                share -> shared.forNewWorld(settings)
                else -> loadOwn(access, packs, levelTag, settings)
            }
            val t2 = TimeSource.Monotonic.markNow()
            access.saveDataTag(stem.worldDataAndGenSettings().data())
            val worldStem = stem
            val server = MinecraftServer.spin { thread ->
                DedicatedServer(
                    thread, access, packs, worldStem, Optional.empty(), settings,
                    DataFixers.getDataFixer(), services, jsonRpc, notifications,
                ).also(notifications::setServer)
            }
            stem = null // the server owns stem and access now
            ownsAccess = false
            awaitFirstTick(server, spec)
            val timings = HostedServer.Timings(
                setup = (t1 - t0).toJavaDuration(), datapacks = (t2 - t1).toJavaDuration(), init = t2.elapsedNow().toJavaDuration(),
            )
            log.info("{}: ready on port {} in {} ms ({} resources)", spec.id, spec.port, timings.total.toMillis(), if (share) "shared" else "own")
            return HostedServer(spec, server, share, timings)
        } catch (e: Throwable) {
            stem?.let(::discard)
            if (ownsAccess) runCatching { access.close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
    }

    /** level.dat, upgraded to this version; null for a new world. Refuses worlds `Main` would refuse. */
    private fun readLevel(access: LevelStorageSource.LevelStorageAccess, notifications: NotificationManager): Dynamic<*>? {
        if (!access.hasWorldData()) return null
        val unfixed = access.getUnfixedDataTagWithFallback()
        val summary = access.fixAndGetSummaryFromTag(unfixed)
        check(!summary.requiresManualConversion() && summary.isCompatible) { "world ${access.levelId} cannot be loaded by this version" }
        return DataFixers.getFileFixer().fix(access, unfixed, UpgradeProgress(notifications))
    }

    /**
     * Selects the world's datapacks in [packs] (as `Main` would) and tells whether they and the feature
     * flags are exactly the host's, the condition for [SharedStem].
     */
    private fun selectsHostPacks(packs: PackRepository, levelTag: Dynamic<*>?, settings: DedicatedServerSettings): Boolean {
        val wanted = if (levelTag != null) LevelStorageSource.readDataConfig(levelTag)
        else WorldDataConfiguration(settings.properties.initialDataPackConfiguration, host.worldData.dataConfiguration.enabledFeatures())
        val selected = MinecraftServer.configurePackRepository(packs, wanted, levelTag == null, false)
        val hostPacks = host.packRepository.selectedIds.toList()
        val same = packs.selectedIds.toList() == hostPacks && selected.enabledFeatures() == host.worldData.dataConfiguration.enabledFeatures()
        if (!same) log.info("datapacks {} differ from the host's {}: loading them separately", packs.selectedIds, hostPacks)
        return same
    }

    /** The world's own datapack load, as `Main` does it (0.4–1.3 s). */
    private fun loadOwn(
        access: LevelStorageSource.LevelStorageAccess, packs: PackRepository, levelTag: Dynamic<*>?, settings: DedicatedServerSettings,
    ): WorldStem {
        val config = Main.loadOrCreateConfig(settings.properties, levelTag, false, packs)
        return Util.blockUntilDone { executor ->
            WorldLoader.load(config, { context ->
                val datapackDimensions = context.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM)
                if (levelTag != null) {
                    val loaded = LevelStorageSource.getLevelDataAndDimensions(
                        access, levelTag, context.dataConfiguration(), datapackDimensions, context.datapackWorldRegistries(),
                    )
                    WorldLoader.DataLoadOutput(loaded.worldDataAndGenSettings(), loaded.dimensions().dimensionsRegistryAccess())
                } else {
                    Main.createNewWorldData(settings, context, datapackDimensions, false, false)
                }
            }, ::WorldStem, Util.backgroundExecutor(), executor)
        }.get()
    }

    /** A stem that never reached a server: shared resources are only released, own ones closed. */
    private fun discard(stem: WorldStem) {
        if (SharedResources.release(stem.resourceManager())) stem.close()
    }

    private fun awaitFirstTick(server: DedicatedServer, spec: HostedServerSpec) {
        val deadline = TimeSource.Monotonic.markNow() + spec.startTimeout.toKotlinDuration()
        while (!server.isReady) {
            if (!server.runningThread.isAlive) error("${spec.id}: server thread ended during startup (see the log)")
            if (deadline.hasPassedNow()) {
                server.halt(false)
                error("${spec.id}: not ready after ${spec.startTimeout}")
            }
            Thread.sleep(2)
        }
    }

    companion object {
        const val WORLD = "world"
    }
}
