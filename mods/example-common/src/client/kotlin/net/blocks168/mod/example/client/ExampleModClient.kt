package net.blocks168.mod.example.client

import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

/** Client side of the example mod (client-only code lives in src/client). */
object ExampleModClient : ClientModInitializer {
    override fun onInitializeClient() {
        LoggerFactory.getLogger("blocks168-example").info("168blocks example client initialized")
    }
}
