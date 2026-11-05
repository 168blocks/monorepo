package net.blocks168.backend.matrix

import io.ktor.http.Url

/**
 * The Matrix bot's account. Credentials only ever come from the environment (deploy secrets), never
 * from the repository.
 *
 * - `BLOCKS_MATRIX_HOMESERVER`: base URL of the bot's homeserver, e.g. `https://matrix.example.org`
 * - `BLOCKS_MATRIX_USER`: the bot's user name (localpart or full `@bot:server`)
 * - `BLOCKS_MATRIX_PASSWORD`: only used for the first login; afterwards the bot uses the access token and
 *   device it stored in PostgreSQL, so its device id and encryption keys survive restarts
 * - `BLOCKS_MATRIX_DEVICE_ID`: device id for the first login, default `168blocks-backend`
 * - `BLOCKS_MATRIX_ANNOUNCE_ROOM`: optional room id; the backend posts a message there when it starts
 *   (example, see [MatrixExamples.announceStartup])
 */
data class MatrixBotConfig(
    val homeserver: Url,
    val user: String,
    val password: String,
    val deviceId: String = DEFAULT_DEVICE_ID,
    val announceRoom: String? = null,
) {
    override fun toString(): String =
        "MatrixBotConfig(homeserver=$homeserver, user=$user, password=***, deviceId=$deviceId, announceRoom=$announceRoom)"

    companion object {
        const val DEFAULT_DEVICE_ID = "168blocks-backend"

        /** The bot's config, or null when `BLOCKS_MATRIX_HOMESERVER` is not set (the bot is off). */
        fun fromEnv(env: Map<String, String> = System.getenv()): MatrixBotConfig? {
            val homeserver = env["BLOCKS_MATRIX_HOMESERVER"]?.takeIf { it.isNotBlank() } ?: return null
            return MatrixBotConfig(
                homeserver = Url(homeserver),
                user = env["BLOCKS_MATRIX_USER"] ?: error("BLOCKS_MATRIX_USER is not set"),
                password = env["BLOCKS_MATRIX_PASSWORD"] ?: "",
                deviceId = env["BLOCKS_MATRIX_DEVICE_ID"]?.takeIf { it.isNotBlank() } ?: DEFAULT_DEVICE_ID,
                announceRoom = env["BLOCKS_MATRIX_ANNOUNCE_ROOM"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}
