# Discord bot (Kord)

The backend talks to Discord with [Kord](https://github.com/kordlib/kord). Code: `backend/src/main/kotlin/net/blocks168/backend/discord/`.

## What it does now

`DiscordBot` uses only `kord-rest`, Kord's REST client: it can send messages to channels and DMs to users. It does
not connect to the gateway, so it receives no events (no commands, no reading messages) and shows as offline.

```kotlin
val bot = DiscordBot(DiscordConfig.fromEnv() ?: return) // null: BLOCKS_DISCORD_TOKEN unset, bot disabled
bot.sendChannelMessage(Snowflake(channelId), "Server restarting in 5 minutes")
bot.sendDirectMessage(Snowflake(userId), "Your world backup is ready")
```

Anything else in Discord's REST API is on `bot.rest` (`rest.channel`, `rest.guild`, `rest.user`, ...).

## Configuration

| Variable | What |
| --- | --- |
| `BLOCKS_DISCORD_TOKEN` | Bot token (Developer Portal → your application → Bot → Reset Token). Unset or empty: no bot. |

Never commit the token. Keep it in your shell, a local untracked env file, or the deployment's secret store.

Inviting the bot: Developer Portal → OAuth2 → URL Generator, scope `bot`, permission *Send Messages*, open the URL.
A DM only arrives when the user shares a server with the bot and allows DMs from that server's members.

## Trying it

```sh
BLOCKS_DISCORD_TOKEN=... BLOCKS_DISCORD_CHANNEL_ID=... BLOCKS_DISCORD_USER_ID=... \
  ./dev ./gradlew :backend:runDiscordExample
```

Either id may be left out. Ids: Discord → Settings → Advanced → Developer Mode, then right-click → Copy ID.

The unit tests (`DiscordBotTest`) run the bot against a fake Discord API (Ktor `MockEngine`), so they need no
token or network.

## Later: gateway events with kord-core

For slash commands or reacting to messages, add `dev.kord:kord-core` (same version as `kord`) and log in:

```kotlin
val kord = Kord(config.token)
val bot = DiscordBot(kord.rest)          // the sending code stays as is
kord.on<MessageCreateEvent> { ... }
kord.login { intents += Intent.GuildMessages }  // suspends while connected; run it in its own coroutine
```

`kord-core` brings the gateway connection and a cache; it needs the bot to stay connected, so it belongs in the
long-running backend process, not in a one-off task.
