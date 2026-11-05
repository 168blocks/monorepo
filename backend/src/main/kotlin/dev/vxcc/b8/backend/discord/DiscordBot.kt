package dev.vxcc.b8.backend.discord

import dev.kord.common.entity.Snowflake
import dev.kord.rest.json.request.DMCreateRequest
import dev.kord.rest.request.KtorRequestHandler
import dev.kord.rest.request.RequestHandler
import dev.kord.rest.service.RestClient

/**
 * The backend's Discord bot, on Kord's REST client (`kord-rest`): it sends messages, but does not connect to
 * the gateway, so it receives no events and shows as offline. That is all the backend needs for now.
 *
 * Later, for slash commands or reacting to messages, switch to `kord-core` (`Kord(token)` + `kord.login()`):
 * it is built on the same [RestClient] (`kord.rest`), so the code here keeps working unchanged.
 */
class DiscordBot(val rest: RestClient) {
    constructor(requestHandler: RequestHandler) : this(RestClient(requestHandler))
    constructor(config: DiscordConfig) : this(KtorRequestHandler(config.token))

    /** Sends [text] to the channel [channelId] (a guild text channel the bot can see, or a DM channel). */
    suspend fun sendChannelMessage(channelId: Snowflake, text: String): Snowflake =
        rest.channel.createMessage(channelId) { content = text }.id

    /**
     * Sends [text] as a direct message to the user [userId]. Discord only delivers it if the user shares a
     * server with the bot and allows DMs from its members; otherwise it fails with a 403.
     */
    suspend fun sendDirectMessage(userId: Snowflake, text: String): Snowflake {
        val dm = rest.user.createDM(DMCreateRequest(userId))
        return sendChannelMessage(dm.id, text)
    }
}
