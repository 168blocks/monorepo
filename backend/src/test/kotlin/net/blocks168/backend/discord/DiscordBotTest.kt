package net.blocks168.backend.discord

import dev.kord.common.entity.Snowflake
import dev.kord.rest.request.KtorRequestHandler
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The bot against a fake Discord API (Ktor's MockEngine), so no token or network is needed. */
class DiscordBotTest {
    private val requests = mutableListOf<HttpRequestData>()

    private val bot = DiscordBot(
        KtorRequestHandler(
            HttpClient(MockEngine { request ->
                requests += request
                val path = request.url.encodedPath
                when {
                    request.method == HttpMethod.Post && path.endsWith("/users/@me/channels") ->
                        json("""{"id":"$DM_CHANNEL","type":1}""")
                    request.method == HttpMethod.Post && path.matches(Regex(".*/channels/\\d+/messages")) ->
                        json(message(path.split("/").dropLast(1).last(), body(request)["content"]!!.jsonPrimitive.content))
                    else -> respond("""{"code":0,"message":"404: Not Found"}""", HttpStatusCode.NotFound, jsonHeaders)
                }
            }) { expectSuccess = false },
            token = "test-token",
        ),
    )

    @Test
    fun `sends a channel message`() = runBlocking {
        assertEquals(MESSAGE, bot.sendChannelMessage(Snowflake(CHANNEL), "hello"))

        val req = requests.single()
        assertEquals("/api/v10/channels/$CHANNEL/messages", req.url.encodedPath)
        assertEquals("Bot test-token", req.headers[HttpHeaders.Authorization])
        assertEquals("hello", body(req)["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sends a direct message by opening a DM channel first`() = runBlocking {
        assertEquals(MESSAGE, bot.sendDirectMessage(Snowflake(USER), "psst"))

        val (open, send) = requests
        assertEquals("/api/v10/users/@me/channels", open.url.encodedPath)
        assertEquals(USER.toString(), body(open)["recipient_id"]!!.jsonPrimitive.content)
        assertEquals("/api/v10/channels/$DM_CHANNEL/messages", send.url.encodedPath)
        assertEquals("psst", body(send)["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `token comes from the environment, disabled when unset`() {
        assertNull(DiscordConfig.fromEnv(emptyMap()))
        assertNull(DiscordConfig.fromEnv(mapOf(DiscordConfig.TOKEN_ENV to "  ")))
        val config = DiscordConfig.fromEnv(mapOf(DiscordConfig.TOKEN_ENV to "secret\n"))!!
        assertEquals("secret", config.token)
        assertEquals("DiscordConfig(token=***)", config.toString())
        assertFailsWith<IllegalArgumentException> { DiscordConfig("") }
    }

    private companion object {
        const val CHANNEL = 1100000000000000001uL
        const val USER = 1100000000000000002uL
        const val DM_CHANNEL = 1100000000000000003uL
        val MESSAGE = Snowflake(1100000000000000004uL)
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

        fun body(request: HttpRequestData): JsonObject =
            Json.decodeFromString(JsonObject.serializer(), (request.body as TextContent).text)

        fun io.ktor.client.engine.mock.MockRequestHandleScope.json(text: String) =
            respond(text, HttpStatusCode.OK, jsonHeaders)

        fun message(channelId: String, content: String) = """
            {"id":"$MESSAGE","channel_id":"$channelId","content":${Json.encodeToString(String.serializer(), content)},
             "author":{"id":"1100000000000000005","username":"168blocks","discriminator":"0","avatar":null,"bot":true},
             "timestamp":"2026-11-05T03:00:00.000000+00:00","edited_timestamp":null,"tts":false,
             "mention_everyone":false,"mentions":[],"mention_roles":[],"attachments":[],"embeds":[],
             "pinned":false,"type":0}
        """.trimIndent()
    }
}
