package dev.vxcc.b8.mod.creativehost

import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Starts extra servers ("hosted servers") in the JVM of the server this mod runs in (the host), for the
 * creative host of issue #6. Each hosted server is a full `DedicatedServer` with its own directory,
 * `server.properties`, port and world, started the way `net.minecraft.server.Main` starts one; what makes
 * it fast (0.2–0.5 s instead of seconds):
 *
 * - its world is copied from a template that already has its spawn area generated
 *   ([HostedServerSpec.template]), instead of being generated;
 * - it reuses the host's datapack resources and registries ([SharedStem]) instead of loading its own.
 *
 * Hosted servers share everything static with the host (bootstrap, built-in registries, mod state); the
 * fixes for static state that breaks that (C2ME's `System.exit` on stop, worker pools shut down on stop,
 * the watchdog, delayed crashes, Async) are in the `cohost-fixes` mod, which this mod depends on
 * (docs/co-hosting.md).
 */
object CreativeHost {
    private val log = LoggerFactory.getLogger(CreativeHostMod.MOD_ID)
    private val running = ConcurrentHashMap<String, HostedServer>()
    private val starting = HashSet<String>() // ids reserved while their server starts; guarded by itself
    private val starter = Executors.newCachedThreadPool { r -> Thread(r, "creative-host-start").apply { isDaemon = true } }

    @Volatile
    private var factory: HostedServerFactory? = null

    /** The server whose JVM this is; null until it has started. */
    @Volatile
    var host: MinecraftServer? = null
        private set

    val servers: Collection<HostedServer> get() = running.values

    fun server(id: String): HostedServer? = running[id]

    /** True for hosted servers, false for the host and anything else. */
    fun isHosted(server: MinecraftServer): Boolean = running.values.any { it.server === server }

    /**
     * Starts [spec] on a background thread; completes once the server has ticked once (it then accepts
     * connections) and fails if startup fails or takes longer than [HostedServerSpec.startTimeout].
     * Several starts may run at once.
     */
    fun start(spec: HostedServerSpec): CompletableFuture<HostedServer> {
        val f = checkNotNull(factory) { "the host server has not started yet" }
        synchronized(starting) {
            check(!running.containsKey(spec.id) && starting.add(spec.id)) { "a hosted server ${spec.id} is already running" }
        }
        return CompletableFuture.supplyAsync({ f.start(spec).also { running[spec.id] = it } }, starter)
            .whenComplete { _, _ -> synchronized(starting) { starting.remove(spec.id) } }
    }

    internal fun hostStarted(server: MinecraftServer, directory: Path) {
        if (host != null) return
        host = server
        factory = HostedServerFactory(server, directory)
    }

    internal fun serverStopped(server: MinecraftServer) {
        running.values.firstOrNull { it.server === server }?.let { running.remove(it.id, it) }
    }

    /** Stops every hosted server before the host itself stops. */
    internal fun hostStopping() {
        val all = running.values.toList()
        if (all.isNotEmpty()) log.info("host stopping: stopping {} hosted servers", all.size)
        all.forEach { runCatching { it.stop(wait = true) }.onFailure { e -> log.warn("stopping {} failed", it.id, e) } }
    }
}
