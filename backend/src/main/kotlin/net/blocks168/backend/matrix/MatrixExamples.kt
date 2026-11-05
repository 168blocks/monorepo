package net.blocks168.backend.matrix

import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId

/**
 * Examples of what the bot does, kept small so they double as documentation. MatrixBotTest runs them
 * against a throwaway homeserver.
 */
object MatrixExamples {
    /** Room message: the backend announces that it started (`BLOCKS_MATRIX_ANNOUNCE_ROOM`). */
    suspend fun announceStartup(bot: MatrixBot, roomId: RoomId): EventId =
        bot.sendText(roomId, "168blocks backend started")

    /**
     * Encrypted DM: tell a player something privately. The first DM to a player creates an encrypted
     * direct room and invites them; later ones reuse it.
     */
    suspend fun notifyPlayer(bot: MatrixBot, player: UserId, message: String): EventId =
        bot.sendDirectText(player, message)
}
