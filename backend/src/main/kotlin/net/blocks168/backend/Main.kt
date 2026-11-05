package net.blocks168.backend

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import net.blocks168.pgecs.PgEcs
import net.blocks168.pgecs.PgEcsConfig
import java.io.File

/**
 * Environment:
 * - `PGECS_URL`, `PGECS_USER`: database (no password; access is by network, see deploy/compose.yaml) (see [PgEcsConfig.fromEnv])
 * - `BLOCKS_HTTP_PORT`: listen port, default 8080
 * - `BLOCKS_FRONTEND_DIR`: built web frontend to serve at `/` (optional)
 */
fun main() {
    val port = System.getenv("BLOCKS_HTTP_PORT")?.toInt() ?: 8080
    val frontend = System.getenv("BLOCKS_FRONTEND_DIR")?.let(::File)
    val ecs = PgEcs.connectWithRetry(PgEcsConfig.fromEnv())
    val counter = PgCounterStore(ecs).also { it.migrate() }
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        backendModule(BackendServices(ecs, counter), frontend)
    }.start(wait = true)
}
