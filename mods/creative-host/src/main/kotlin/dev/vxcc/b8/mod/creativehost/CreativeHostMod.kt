package dev.vxcc.b8.mod.creativehost

import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import java.nio.file.Path

/** Registers the first server of the JVM as [CreativeHost.host] and stops hosted servers with it. */
object CreativeHostMod : DedicatedServerModInitializer {
    const val MOD_ID = "blocks168-creative-host"

    override fun onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            // the process's own server starts first; hosted servers start later and are never the host
            if (CreativeHost.host == null) CreativeHost.hostStarted(server, Path.of("").toAbsolutePath())
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            if (server === CreativeHost.host) CreativeHost.hostStopping()
        }
        ServerLifecycleEvents.SERVER_STOPPED.register(CreativeHost::serverStopped)
        SelfTest.registerIfEnabled()
    }
}
