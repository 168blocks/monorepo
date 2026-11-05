package dev.vxcc.b8.backend.matrix

import de.connect2x.trixnity.core.model.RoomId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import dev.vxcc.alexs168.pgecs.db.Database
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.vxcc.b8.backend.matrix.MatrixStartup")

/**
 * Starts the Matrix bot in the background when it is configured (`BLOCKS_MATRIX_HOMESERVER`), so a
 * slow or unreachable homeserver never holds up the backend. Returns the job, or null when the bot is off.
 */
fun startMatrixBot(
    db: Database,
    config: MatrixBotConfig? = MatrixBotConfig.fromEnv(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) = config?.let {
    scope.launch {
        runCatching {
            val bot = MatrixBot.start(config, MatrixStore(db))
            config.announceRoom?.let { MatrixExamples.announceStartup(bot, RoomId(it)) }
        }.onFailure { log.error("Matrix bot did not start", it) }
    }
} ?: run {
    log.info("Matrix bot is off (BLOCKS_MATRIX_HOMESERVER is not set)")
    null
}
