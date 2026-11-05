package dev.vxcc.b8.backend

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import dev.vxcc.alexs168.pgecs.db.Database
import dev.vxcc.alexs168.pgecs.PgEcsConfig
import dev.vxcc.b8.backend.matrix.startMatrixBot
import java.io.File

/**
 * Environment:
 * - `PGECS_URL`, `PGECS_USER`: database, no password (see [PgEcsConfig])
 * - `BLOCKS_HTTP_PORT`: listen port, default 8080
 * - `BLOCKS_FRONTEND_DIR`: built web frontend to serve at `/` (optional)
 * - `BLOCKS_MATRIX_*`: Matrix bot, off unless `BLOCKS_MATRIX_HOMESERVER` is set (see [MatrixBotConfig])
 */
fun main() {
    val port = System.getenv("BLOCKS_HTTP_PORT")?.toInt() ?: 8080
    val frontend = System.getenv("BLOCKS_FRONTEND_DIR")?.let(::File)
    val ecs = Database.connectWithRetry(PgEcsConfig.fromEnv().copy(applicationName = "backend"))
    val counter = PgCounterStore(ecs).also { it.migrate() }
    startMatrixBot(ecs)
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        backendModule(BackendServices(ecs, counter), frontend)
    }.start(wait = true)
}
