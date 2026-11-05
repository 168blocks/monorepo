package net.blocks168.backend.matrix

import de.connect2x.trixnity.client.CryptoDriverModule
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.MediaStoreModule
import de.connect2x.trixnity.client.RepositoriesModule
import de.connect2x.trixnity.client.create
import de.connect2x.trixnity.client.cryptodriver.vodozemac.vodozemac
import de.connect2x.trixnity.client.media.inMemory
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.store.repository.exposed.exposed
import de.connect2x.trixnity.clientserverapi.client.MatrixClientAuthProviderData
import de.connect2x.trixnity.clientserverapi.client.SyncState
import de.connect2x.trixnity.clientserverapi.client.classicLogin
import de.connect2x.trixnity.clientserverapi.model.authentication.IdentifierType
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.m.room.EncryptedMessageEventContent
import de.connect2x.trixnity.core.model.events.m.room.Membership
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.blocks168.pgecs.db.Database
import net.blocks168.pgecs.testing.TestPostgres
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * The bot against a real (throwaway) homeserver: a room message, an encrypted DM that a second Trixnity
 * client ("alice", a player) decrypts, and a restart that keeps the bot's device and DM room.
 */
class MatrixBotTest {
    private lateinit var homeserver: TestHomeserver
    private lateinit var ecs: Database
    private val closeables = mutableListOf<AutoCloseable>()

    @BeforeTest
    fun setUp() {
        homeserver = TestHomeserver("bot", "alice")
        ecs = Database.connect(TestPostgres.config)
    }

    @AfterTest
    fun tearDown() {
        closeables.asReversed().forEach { runCatching { it.close() } }
        ecs.close()
        homeserver.close()
    }

    private val botSchema = "matrix_bot_test_${System.nanoTime()}"

    private fun botConfig() = MatrixBotConfig(homeserver.baseUrl, "bot", TestHomeserver.PASSWORD, deviceId = "BOTDEVICE")

    private suspend fun startBot() =
        MatrixBot.start(botConfig(), MatrixStore(ecs, botSchema)).also { closeables += it }

    /** A player's client, with its own store, as a Matrix client app would have. */
    private suspend fun startPlayer(name: String): MatrixClient {
        val store = MatrixStore(ecs, "matrix_${name}_${System.nanoTime()}").also { it.migrate() }
        val client = MatrixClient.create(
            repositoriesModule = RepositoriesModule.exposed(store.database()),
            mediaStoreModule = MediaStoreModule.inMemory(),
            cryptoDriverModule = CryptoDriverModule.vodozemac(),
            authProviderData = MatrixClientAuthProviderData.classicLogin(
                baseUrl = homeserver.baseUrl,
                identifier = IdentifierType.User(name),
                password = TestHomeserver.PASSWORD,
            ).getOrThrow(),
        ) { this.name = name }.getOrThrow()
        closeables += client
        client.startSync()
        withTimeout(30.seconds) { client.syncState.first { it == SyncState.RUNNING } }
        return client
    }

    /** The decrypted text of [eventId] as [client] sees it, after joining [roomId] if invited. */
    private suspend fun readText(client: MatrixClient, roomId: RoomId, eventId: EventId): Pair<String, Boolean> =
        withTimeout(30.seconds) {
            val room = client.room.getById(roomId).first { it?.membership == Membership.INVITE || it?.membership == Membership.JOIN }
            if (room?.membership == Membership.INVITE) {
                client.api.room.joinRoom(roomId).getOrThrow()
                client.room.getById(roomId).first { it?.membership == Membership.JOIN }
            }
            val event = client.room.getTimelineEvent(roomId, eventId).first { it?.content != null }!!
            val content = assertIs<RoomMessageEventContent.TextBased.Text>(event.content!!.getOrThrow())
            content.body to (event.event.content is EncryptedMessageEventContent)
        }

    @Test
    fun `room message`() = runBlocking {
        val alice = startPlayer("alice")
        val bot = startBot()
        val roomId = alice.api.room.createRoom(invite = setOf(bot.userId)).getOrThrow()
        withTimeout(30.seconds) { bot.client.room.getById(roomId).first { it?.membership == Membership.INVITE } }
        bot.client.api.room.joinRoom(roomId).getOrThrow()
        withTimeout(30.seconds) { bot.client.room.getById(roomId).first { it?.membership == Membership.JOIN } }

        val eventId = MatrixExamples.announceStartup(bot, roomId)

        val (text, encrypted) = readText(alice, roomId, eventId)
        assertEquals("168blocks backend started", text)
        assertEquals(false, encrypted)
    }

    @Test
    fun `encrypted direct message survives a restart`() = runBlocking {
        val alice = startPlayer("alice")
        val aliceId = UserId(homeserver.userId("alice"))

        val bot = startBot()
        assertEquals("BOTDEVICE", bot.client.deviceId)
        val first = MatrixExamples.notifyPlayer(bot, aliceId, "your link code is 123456")
        val dmRoom = bot.directRoom(aliceId)
        assertEquals("your link code is 123456" to true, readText(alice, dmRoom, first))
        bot.close()
        closeables -= bot

        // same store: no new login, same device and keys, same DM room
        val restarted = startBot()
        assertEquals("BOTDEVICE", restarted.client.deviceId)
        assertEquals(dmRoom, restarted.directRoom(aliceId))
        val second = MatrixExamples.notifyPlayer(restarted, aliceId, "welcome back")
        assertEquals("welcome back" to true, readText(alice, dmRoom, second))
    }
}
