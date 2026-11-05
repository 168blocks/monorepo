package dev.vxcc.b8.mod.creativehost

import net.minecraft.server.dedicated.DedicatedServer
import java.time.Duration

/** A server [CreativeHost] started; [server] runs on its own server thread like any `DedicatedServer`. */
class HostedServer internal constructor(
    val spec: HostedServerSpec,
    val server: DedicatedServer,
    /** whether it uses the host's datapack resources and registries ([SharedStem]) */
    val sharesResources: Boolean,
    /** setup (directory, template copy, settings), datapacks + registries, and init until the first tick */
    val timings: Timings,
) {
    data class Timings(val setup: Duration, val datapacks: Duration, val init: Duration) {
        val total: Duration get() = setup + datapacks + init
    }

    val id: String get() = spec.id

    /**
     * Stops the server (saves its world) and waits for its thread to end unless [wait] is false. Without
     * the `cohost-fixes` mod, C2ME ends the whole JVM here and vanilla shuts down the worker pools every
     * server shares (#75).
     */
    fun stop(wait: Boolean = true) {
        server.halt(wait)
    }

    override fun toString() = "HostedServer($id, port ${spec.port}, ${if (sharesResources) "shared" else "own"} resources)"
}
