package dev.vxcc.b8.mod.creativehost

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import net.minecraft.world.level.levelgen.FlatLevelSource
import net.minecraft.world.level.storage.LevelResource
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Dev-only check of a real host (`-Dblocks168.creative-host.selftest=<template world>`): once the host is
 * up, starts two hosted servers from the template and one new flat world with the host's world datapacks
 * (all three share the host's resources), and one new world without them (loads its own). It checks
 * datapack data on each, that the shared resources are counted and survive stopping a server, logs
 * "creative host self test passed" or "FAILED" and halts the JVM with 0 or 1. Ports: `server-port` + 1..4.
 */
internal object SelfTest {
    private val log = LoggerFactory.getLogger("${CreativeHostMod.MOD_ID}/selftest")
    const val PROPERTY = "blocks168.creative-host.selftest"

    fun registerIfEnabled() {
        val template = System.getProperty(PROPERTY)?.let(Path::of) ?: return
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            if (server === CreativeHost.host) thread(name = "creative-host-selftest", isDaemon = true) { run(server, template) }
        }
    }

    private fun run(host: MinecraftServer, template: Path) {
        val ok = runCatching { check(host, template) }.onFailure { log.error("creative host self test FAILED", it) }.isSuccess
        if (ok) log.info("creative host self test passed")
        Runtime.getRuntime().halt(if (ok) 0 else 1)
    }

    private fun check(host: MinecraftServer, template: Path) {
        val base = Path.of("selftest").toAbsolutePath()
        WorldTemplates.deleteTree(base)
        // a new world gets the host world's datapacks (a world selects the datapacks in its own directory)
        val hostDatapacks = host.getWorldPath(LevelResource.DATAPACK_DIR)
        val flatDatapacks = base.resolve("flat/world/datapacks")
        if (Files.isDirectory(hostDatapacks)) copyTree(hostDatapacks, flatDatapacks)
        val flat = mapOf("level-type" to "minecraft:flat")
        val specs = listOf(
            HostedServerSpec("selftest-a", base.resolve("a"), host.port + 1, template),
            HostedServerSpec("selftest-b", base.resolve("b"), host.port + 2, template),
            HostedServerSpec("selftest-flat", base.resolve("flat"), host.port + 3, properties = flat),
            HostedServerSpec("selftest-own", base.resolve("own"), host.port + 4, properties = flat),
        )
        val started = specs.map(CreativeHost::start).map(CompletableFuture<HostedServer>::join)
        for (hosted in started) {
            log.info("{}: {} ms (setup {}, datapacks {}, init {}), shared resources: {}", hosted.id, hosted.timings.total.toMillis(),
                hosted.timings.setup.toMillis(), hosted.timings.datapacks.toMillis(), hosted.timings.init.toMillis(), hosted.sharesResources)
            val sameDatapacks = hosted.server.packRepository.selectedIds.toList() == host.packRepository.selectedIds.toList()
            require(hosted.sharesResources == sameDatapacks) { "${hosted.id}: shares resources ${hosted.sharesResources}, same datapacks $sameDatapacks" }
            require(hosted.sharesResources == (hosted.id != "selftest-own")) { "${hosted.id}: unexpected shares resources ${hosted.sharesResources}" }
            val loot = hosted.server.reloadableRegistries().lookup().lookupOrThrow(net.minecraft.core.registries.Registries.LOOT_TABLE).listElements().count()
            require(loot > 100) { "${hosted.id}: only $loot loot tables" }
            require(hosted.server.recipeManager.recipes.size > 100) { "${hosted.id}: recipes missing" }
            CompletableFuture.runAsync({
                hosted.server.commands.performPrefixedCommand(hosted.server.createCommandSourceStack(), "loot spawn ~ 100 ~ loot minecraft:chests/simple_dungeon")
            }, hosted.server).join()
        }
        for (hosted in started.drop(2)) {
            val generator = hosted.server.overworld().chunkSource.generator
            require(generator is FlatLevelSource) { "${hosted.id}: new world ignored level-type (${generator::class.simpleName})" }
        }
        val hostGenerator = host.overworld().chunkSource.generator::class
        require(started.first().server.overworld().chunkSource.generator::class == hostGenerator) { "template world generator changed" }

        val resources = host.resources.resourceManager()
        val sharing = started.count { it.sharesResources }
        val users = SharedResources.users(resources)
        require(users == 1 + sharing) { "$users users of the host's resources, expected ${1 + sharing}" }
        started.first().stop()
        require(SharedResources.users(resources) == users - 1) { "stopping a hosted server did not release the shared resources once" }
        val stillReads = resources.listResources("loot_table") { it.path.endsWith(".json") }.size
        require(stillReads > 100) { "the host's resources are unreadable after a hosted server stopped ($stillReads loot tables)" }
        started.drop(1).forEach { it.stop() }
        require(CreativeHost.servers.isEmpty()) { "stopped servers still listed: ${CreativeHost.servers}" }
        require(SharedResources.users(resources) == 1) { "shared resources still counted after all hosted servers stopped" }
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { paths ->
            paths.forEach { p -> Files.copy(p, to.resolve(from.relativize(p).toString()).also { Files.createDirectories(it.parent) }) }
        }
    }
}
