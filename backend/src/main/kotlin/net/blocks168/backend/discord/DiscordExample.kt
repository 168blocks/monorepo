package net.blocks168.backend.discord

import dev.kord.common.entity.Snowflake
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Example: sends a message to a channel and/or a DM to a user with the real Discord API.
 *
 * ```sh
 * BLOCKS_DISCORD_TOKEN=... \
 * BLOCKS_DISCORD_CHANNEL_ID=123... \
 * BLOCKS_DISCORD_USER_ID=456... \
 *   ./dev ./gradlew :backend:runDiscordExample
 * ```
 *
 * IDs: Discord settings → Advanced → Developer Mode, then right-click a channel or user → Copy ID.
 * The bot must be invited to the channel's server (OAuth2 → URL Generator, scope `bot`, permission
 * Send Messages); a DM only arrives if the user shares a server with the bot.
 */
fun main() = runBlocking {
    val config = DiscordConfig.fromEnv() ?: run {
        System.err.println("set ${DiscordConfig.TOKEN_ENV} to the bot token")
        exitProcess(2)
    }
    val channel = System.getenv("BLOCKS_DISCORD_CHANNEL_ID")?.takeIf { it.isNotBlank() }?.let(::Snowflake)
    val user = System.getenv("BLOCKS_DISCORD_USER_ID")?.takeIf { it.isNotBlank() }?.let(::Snowflake)
    if (channel == null && user == null) {
        System.err.println("set BLOCKS_DISCORD_CHANNEL_ID and/or BLOCKS_DISCORD_USER_ID")
        exitProcess(2)
    }

    val bot = DiscordBot(config)
    if (channel != null) println("channel message: ${bot.sendChannelMessage(channel, "Hello from the 168blocks backend!")}")
    if (user != null) println("direct message: ${bot.sendDirectMessage(user, "Hello from the 168blocks backend, just for you.")}")
    exitProcess(0) // Kord's HTTP client keeps non-daemon threads alive
}
