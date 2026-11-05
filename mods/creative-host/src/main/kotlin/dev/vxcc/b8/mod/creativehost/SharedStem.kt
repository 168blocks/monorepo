package dev.vxcc.b8.mod.creativehost

import com.mojang.serialization.Dynamic
import net.minecraft.core.HolderLookup
import net.minecraft.core.LayeredRegistryAccess
import net.minecraft.core.Registry
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.Registries
import net.minecraft.server.Main
import net.minecraft.server.MinecraftServer
import net.minecraft.server.RegistryLayer
import net.minecraft.server.WorldLoader
import net.minecraft.server.WorldStem
import net.minecraft.server.dedicated.DedicatedServerSettings
import net.minecraft.resources.RegistryDataLoader
import net.minecraft.util.Util
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.storage.LevelStorageSource

/**
 * Builds a hosted server's [WorldStem] from the host's: the resource manager, `ReloadableServerResources`
 * (recipes, loot tables, advancements, functions, tags, command tree) and the `STATIC`, `WORLDGEN` and
 * `RELOADABLE` registry layers are the host's objects; only the world data and the `DIMENSIONS` layer are
 * the hosted world's own. That skips the datapack load (0.4–1.3 s) and its memory.
 *
 * Only valid while the hosted world selects exactly the host's datapacks and feature flags
 * ([HostedServerFactory] checks). The host's resource manager is reference-counted ([SharedResources]), so
 * stopping a hosted server leaves it open for the host.
 */
internal class SharedStem(private val host: MinecraftServer) {
    private val resources = host.resources
    private val registries: LayeredRegistryAccess<RegistryLayer> = host.registries()

    /** registries a world's dimensions are read against: static and worldgen layers */
    private val dimensionContext: RegistryAccess.Frozen = registries.getAccessForLoading(RegistryLayer.DIMENSIONS)

    /**
     * The `LevelStem`s the datapacks define (`dimension/` files), as `WorldLoader` reads them before a
     * world's own settings are applied. Not the host's final dimensions: those carry the host world's
     * generators, which would override the hosted world's (`WorldDimensions.bake` prefers these).
     */
    private val datapackDimensions: RegistryAccess.Frozen by lazy {
        RegistryDataLoader.load(
            resources.resourceManager(),
            dimensionContext.listRegistries().toList(),
            RegistryDataLoader.DIMENSION_REGISTRIES,
            Util.backgroundExecutor(),
        ).join()
    }

    val dataConfiguration: WorldDataConfiguration get() = host.worldData.dataConfiguration

    /** Stem for an existing world (its `level.dat` read into [levelTag]). */
    fun forExistingWorld(access: LevelStorageSource.LevelStorageAccess, levelTag: Dynamic<*>): WorldStem {
        val loaded = LevelStorageSource.getLevelDataAndDimensions(
            access, levelTag, dataConfiguration, datapackLevelStems(), dimensionContext as HolderLookup.Provider,
        )
        return stem(loaded.dimensions().dimensionsRegistryAccess(), loaded.worldDataAndGenSettings())
    }

    /** Stem for a new world created from [settings] (`level-type`, `level-seed`, `generator-settings`, ...). */
    fun forNewWorld(settings: DedicatedServerSettings): WorldStem {
        val context = WorldLoader.DataLoadContext(
            resources.resourceManager(), dataConfiguration, dimensionContext, datapackDimensions,
        )
        val created = Main.createNewWorldData(settings, context, datapackLevelStems(), false, false)
        return stem(created.finalDimensions(), created.cookie())
    }

    private fun datapackLevelStems(): Registry<LevelStem> = datapackDimensions.lookupOrThrow(Registries.LEVEL_STEM)

    private fun stem(
        dimensions: RegistryAccess.Frozen,
        world: net.minecraft.world.level.storage.LevelDataAndDimensions.WorldDataAndGenSettings,
    ): WorldStem {
        val layers = registries.replaceFrom(RegistryLayer.DIMENSIONS, dimensions, registries.getLayer(RegistryLayer.RELOADABLE))
        SharedResources.acquire(resources.resourceManager())
        return WorldStem(resources.resourceManager(), resources.managers(), layers, world)
    }
}
