package dev.vxcc.b8.mod.cohostfixes

import net.minecraft.server.MinecraftServer
import java.util.concurrent.ConcurrentHashMap

/**
 * The MinecraftServers running in this JVM. A server is in here from the start of its `runServer` until
 * `onServerExit`; the mixins use it to keep JVM-wide actions (exit, executor shutdown, crash relay) for
 * when the last server goes.
 */
object CoHost {
    private val running: MutableSet<MinecraftServer> = ConcurrentHashMap.newKeySet()

    @JvmStatic
    fun started(server: MinecraftServer) {
        running += server
    }

    @JvmStatic
    fun exited(server: MinecraftServer) {
        running -= server
    }

    /** Servers running now. */
    @JvmStatic
    fun servers(): Set<MinecraftServer> = running.toSet()

    /** Whether a server other than [server] still runs in this JVM. */
    @JvmStatic
    fun othersRunning(server: MinecraftServer): Boolean = running.any { it !== server }

    /** Whether more than one server runs in this JVM. */
    @JvmStatic
    fun shared(): Boolean = running.size > 1
}
