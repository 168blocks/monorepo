package dev.vxcc.b8.backend.discord

/**
 * Discord bot settings. The token comes from the environment (or a secret mounted into it) and is never
 * committed. Create a bot at https://discord.com/developers/applications → Bot → Reset Token.
 */
class DiscordConfig(val token: String) {
    init {
        require(token.isNotBlank()) { "empty Discord bot token" }
    }

    override fun toString() = "DiscordConfig(token=***)"

    companion object {
        const val TOKEN_ENV = "BLOCKS_DISCORD_TOKEN"

        /** The config from `BLOCKS_DISCORD_TOKEN`, or null (bot disabled) when it is unset or empty. */
        fun fromEnv(env: Map<String, String> = System.getenv()): DiscordConfig? =
            env[TOKEN_ENV]?.trim()?.takeIf { it.isNotEmpty() }?.let(::DiscordConfig)
    }
}
