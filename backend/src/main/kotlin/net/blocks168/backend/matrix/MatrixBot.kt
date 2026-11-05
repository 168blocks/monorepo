package net.blocks168.backend.matrix

import de.connect2x.trixnity.client.CryptoDriverModule
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.MediaStoreModule
import de.connect2x.trixnity.client.RepositoriesModule
import de.connect2x.trixnity.client.create
import de.connect2x.trixnity.client.cryptodriver.vodozemac.vodozemac
import de.connect2x.trixnity.client.media.inMemory
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.client.store.RoomOutboxMessage
import de.connect2x.trixnity.client.store.repository.exposed.exposed
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.clientserverapi.client.MatrixClientAuthProviderData
import de.connect2x.trixnity.clientserverapi.client.SyncState
import de.connect2x.trixnity.clientserverapi.client.classicLogin
import de.connect2x.trixnity.clientserverapi.model.authentication.IdentifierType
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.InitialStateEvent
import de.connect2x.trixnity.core.model.events.m.DirectEventContent
import de.connect2x.trixnity.core.model.events.m.room.EncryptionEventContent
import de.connect2x.trixnity.core.model.events.m.room.MemberEventContent
import de.connect2x.trixnity.core.model.events.m.room.Membership
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The backend's Matrix bot: posts to rooms and sends end-to-end encrypted direct messages to users on any
 * homeserver. A full Trixnity client (sync loop, device lists, Olm/Megolm), because encrypted sending
 * needs all of that; its state lives in PostgreSQL ([MatrixStore]), so the bot keeps one device and its
 * keys across restarts.
 *
 * Start with [start], stop with [close].
 */
class MatrixBot private constructor(val client: MatrixClient, private val engine: HttpClientEngine) : AutoCloseable {
    private val dmLock = Mutex()

    val userId: UserId get() = client.userId

    /**
     * Sends [text] to [roomId] (the bot must have joined it) and returns the event id once the homeserver
     * accepted it. Encrypted rooms are encrypted transparently. On a timeout the message stays in
     * Trixnity's outbox (in PostgreSQL) and is still sent later.
     */
    suspend fun sendText(roomId: RoomId, text: String, timeout: Duration = SEND_TIMEOUT): EventId {
        val transactionId = client.room.sendMessage(roomId) { text(text) }
        var last: RoomOutboxMessage<*>? = null
        return withTimeoutOrNull(timeout) {
            client.room.getOutbox(roomId, transactionId)
                .mapNotNull { message ->
                    last = message
                    message?.sendError?.let { error("sending to $roomId failed: $it") }
                    message?.eventId
                }
                .first()
        } ?: error("sending to $roomId timed out after $timeout, outbox: $last")
    }

    /** Sends [text] to [user] in an end-to-end encrypted direct message room ([directRoom]). */
    suspend fun sendDirectText(user: UserId, text: String, timeout: Duration = SEND_TIMEOUT): EventId =
        sendText(directRoom(user, timeout), text, timeout)

    /**
     * The encrypted direct message room with [user]: one the bot already has (`m.direct` account data,
     * which Trixnity keeps up to date), or a new one. A new room is private, marked `is_direct`, has
     * `m.room.encryption` from the start and invites [user]; messages sent before they join are shared
     * with their devices too.
     */
    suspend fun directRoom(user: UserId, timeout: Duration = SEND_TIMEOUT): RoomId = dmLock.withLock {
        existingDirectRoom(user) ?: run {
            val roomId = client.api.room.createRoom(
                invite = setOf(user),
                isDirect = true,
                initialState = listOf(InitialStateEvent(content = EncryptionEventContent(), stateKey = "")),
            ).getOrThrow()
            log.info("created direct room {} with {}", roomId, user)
            // wait until the sync has the room (and its encryption) before sending into it, so the
            // first message is encrypted and the room is recorded in m.direct
            withTimeout(timeout) {
                client.room.getById(roomId).first { it?.encrypted == true }
                client.user.getAccountData(DirectEventContent::class).first { it?.mappings?.get(user)?.contains(roomId) == true }
            }
            roomId
        }
    }

    private suspend fun existingDirectRoom(user: UserId): RoomId? {
        val candidates = client.user.getAccountData(DirectEventContent::class).first()?.mappings?.get(user).orEmpty()
        return candidates.firstOrNull { roomId ->
            val room = client.room.getById(roomId).first()
            val theirs = client.room.getState(roomId, MemberEventContent::class, user.full).first()?.content?.membership
            // a room they left is no use; create a new one instead
            room != null && room.encrypted && room.membership == Membership.JOIN &&
                (theirs == Membership.JOIN || theirs == Membership.INVITE)
        }
    }

    override fun close() {
        client.close()
        engine.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(MatrixBot::class.java)
        val SEND_TIMEOUT: Duration = 30.seconds

        /**
         * Starts the bot: opens its store, logs in on the first start (password, [MatrixBotConfig.deviceId])
         * or reuses the stored session, and waits until the sync loop runs.
         */
        suspend fun start(config: MatrixBotConfig, store: MatrixStore, startTimeout: Duration = 60.seconds): MatrixBot {
            store.migrate()
            val engine = CIO.create()
            val authProviderData = if (store.hasAccount()) null else {
                log.info("first start: logging in as {} on {} with device {}", config.user, config.homeserver, config.deviceId)
                MatrixClientAuthProviderData.classicLogin(
                    baseUrl = config.homeserver,
                    identifier = IdentifierType.User(config.user),
                    password = config.password,
                    deviceId = config.deviceId,
                    initialDeviceDisplayName = "168blocks backend",
                    httpClientEngine = engine,
                ).getOrThrow()
            }
            val client = try {
                MatrixClient.create(
                    repositoriesModule = RepositoriesModule.exposed(store.database()),
                    mediaStoreModule = MediaStoreModule.inMemory(),
                    cryptoDriverModule = CryptoDriverModule.vodozemac(),
                    authProviderData = authProviderData,
                ) {
                    name = "168blocks-bot"
                    httpClientEngine = engine
                }.getOrThrow()
            } catch (e: Throwable) {
                engine.close()
                throw e
            }
            val bot = MatrixBot(client, engine)
            try {
                client.startSync()
                withTimeout(startTimeout) {
                    client.syncState.first { it == SyncState.RUNNING }
                    client.initialSyncDone.filterNotNull().first { it }
                }
            } catch (e: Throwable) {
                bot.close()
                throw e
            }
            log.info("Matrix bot {} (device {}) is running", client.userId, client.deviceId)
            return bot
        }
    }
}

