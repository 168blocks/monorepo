package dev.vxcc.b8.mod.cohostfixes

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader

/** Refuses co-hosting with mods known to keep one server's state in statics. */
object CohostFixesMod : ModInitializer {
    /** Mod id to why it cannot run with several servers in one JVM (docs/co-hosting.md). */
    val INCOMPATIBLE = mapOf(
        "async" to "Async keeps the server, its entity-ticking pool and task queues in statics " +
            "(ParallelProcessor) and shuts the pool down when any server stops",
    )

    override fun onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            if (!CoHost.othersRunning(server)) return@register
            for ((id, why) in INCOMPATIBLE) {
                check(!FabricLoader.getInstance().isModLoaded(id)) {
                    "cohost-fixes: cannot start a second server in this JVM with mod '$id' loaded: $why"
                }
            }
        }
    }
}
