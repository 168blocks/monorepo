package dev.vxcc.b8.e2e.bot

import dev.vxcc.b8.e2e.Network
import dev.vxcc.b8.e2e.bot.Packets.writeAcceptTeleportation
import dev.vxcc.b8.e2e.bot.Packets.writeChat
import dev.vxcc.b8.e2e.bot.Packets.writeClientInformation
import dev.vxcc.b8.e2e.bot.Packets.writeKnownPacks
import dev.vxcc.b8.e2e.bot.Packets.writeMovePlayerPosRot
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import dev.vxcc.b8.e2e.bot.PacketIds.Configuration as C
import dev.vxcc.b8.e2e.bot.PacketIds.Login as L
import dev.vxcc.b8.e2e.bot.PacketIds.Play as P

class BotException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A protocol-level Minecraft 26.3 client without rendering (docs/testing.md, "Drivers"): offline-mode
 * login (through an edge proxy and Velocity), configuration, play. It answers keep-alives, pings and
 * teleports, follows server switches (Velocity sends the client back to configuration), tracks its
 * own position, world and game mode and the positions of other players it sees, and records chat.
 *
 * ```kotlin
 * Bot("alice").use { alice ->
 *     alice.connect(network.edge(1))
 *     alice.awaitWorld("minecraft:overworld")
 *     alice.moveTo(alice.position + Vec3(0.0, 0.1, 0.0))
 * }
 * ```
 *
 * One reader thread per bot; the public methods may be called from any thread.
 */
class Bot(
    val name: String,
    /** view distance sent to the server; small keeps the chunk traffic down */
    private val viewDistance: Int = 2,
    private val log: (String) -> Unit = { println(it) },
) : AutoCloseable {
    enum class Phase { DISCONNECTED, LOGIN, CONFIGURATION, PLAY }

    val uuid: UUID = Packets.offlineUuid(name)

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // a plain wait/notify monitor
    private val lock = Object()
    private var socket: Socket? = null
    private var conn: Connection? = null
    private var reader: Thread? = null

    // --- state, guarded by lock ---
    @Volatile var phase = Phase.DISCONNECTED; private set
    /** play logins so far: 1 after joining, +1 for every server switch */
    var joins = 0; private set
    var entityId = -1; private set
    var dimension: String? = null; private set
    var gameMode: GameMode? = null; private set
    var hashedSeed: Long = 0; private set
    private var pos: Vec3? = null
    var yaw = 0f; private set
    var pitch = 0f; private set
    /** teleports received from the server (initial placement, corrections, server switches) */
    var teleports = 0; private set
    var disconnectReason: String? = null; private set
    private val entities = HashMap<Int, Pair<UUID, Vec3>>()
    private val chat = ArrayList<ChatMessage>()
    private var loadedSent = false

    /** current position; throws before the server placed the bot */
    val position: Vec3 get() = synchronized(lock) { pos } ?: throw BotException("$name has no position yet")

    val connected get() = phase != Phase.DISCONNECTED

    /** chat and system messages received so far */
    fun messages(): List<ChatMessage> = synchronized(lock) { chat.toList() }

    /** positions of the other players this bot sees, by UUID */
    fun players(): Map<UUID, Vec3> = synchronized(lock) { entities.values.associate { it } }

    fun connect(endpoint: Network.Endpoint, timeout: Duration = 30.seconds): Bot = connect(endpoint.host, endpoint.port, timeout)

    /** Connects, logs in and returns once the bot is in the world and placed (first teleport). */
    fun connect(host: String, port: Int, timeout: Duration = 30.seconds): Bot {
        check(phase == Phase.DISCONNECTED) { "$name is already connected" }
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeout.inWholeMilliseconds.toInt())
        val c = Connection(s.getInputStream(), s.getOutputStream())
        synchronized(lock) {
            socket = s; conn = c
            phase = Phase.LOGIN
            disconnectReason = null
            joins = 0; teleports = 0; pos = null; dimension = null
            entities.clear()
        }
        c.send(PacketIds.Handshake.INTENTION) {
            writeVarInt(PacketIds.PROTOCOL_VERSION)
            writeString(host)
            writeShort(port)
            writeVarInt(PacketIds.Handshake.INTENT_LOGIN)
        }
        c.send(L.S_HELLO) { writeString(name); writeUuid(uuid) }
        reader = Thread({ readLoop(c) }, "bot-$name").apply { isDaemon = true; start() }
        try {
            await("join", timeout) { joins >= 1 && pos != null }
        } catch (e: BotException) {
            close()
            throw e
        }
        log("[$name] joined $dimension at $pos ($gameMode) via $host:$port")
        return this
    }

    // --- awaiting ---

    /** Waits until [condition] (evaluated under the state lock) holds; fails early on disconnect. */
    fun await(what: String, timeout: Duration = 10.seconds, condition: Bot.() -> Boolean) {
        val end = System.nanoTime() + timeout.inWholeNanoseconds
        synchronized(lock) {
            while (!condition()) {
                if (phase == Phase.DISCONNECTED) throw BotException("$name disconnected while waiting for $what: $disconnectReason")
                val left = (end - System.nanoTime()) / 1_000_000
                if (left <= 0) throw BotException("$name: timeout after $timeout waiting for $what (dimension=$dimension, position=$pos, joins=$joins)")
                lock.wait(left)
            }
        }
    }

    fun awaitWorld(dimension: String, timeout: Duration = 10.seconds) = await("world $dimension", timeout) { this.dimension == dimension && pos != null }

    /** Waits for the [n]th play login, i.e. n-1 server switches. */
    fun awaitJoins(n: Int, timeout: Duration = 30.seconds) = await("join #$n", timeout) { joins >= n && pos != null }

    fun awaitChat(timeout: Duration = 10.seconds, predicate: (ChatMessage) -> Boolean): ChatMessage {
        var found: ChatMessage? = null
        await("chat message", timeout) { found = chat.firstOrNull(predicate); found != null }
        return found!!
    }

    /** Waits until the player with [uuid] is seen within [tolerance] of [at]; returns its position. */
    fun awaitPlayerAt(uuid: UUID, at: Vec3, tolerance: Double = 0.1, timeout: Duration = 10.seconds): Vec3 {
        await("player $uuid at $at", timeout) { entities.values.any { it.first == uuid && it.second.distanceTo(at) <= tolerance } }
        return players().getValue(uuid)
    }

    // --- actions ---

    /**
     * Walks in a straight line to [target], at most [step] blocks per client tick, one tick every
     * 50 ms (vanilla walking speed is about 0.22 blocks per tick). The bot has no physics and no
     * world: it is up to the caller to choose a path the server accepts. Throws if the server
     * corrects the position (sends a teleport) while walking.
     */
    fun moveTo(target: Vec3, step: Double = 0.5, onGround: Boolean = true) {
        val start = position
        val teleportsBefore = synchronized(lock) { teleports }
        val n = maxOf(1, ceil(start.distanceTo(target) / step).toInt())
        for (i in 1..n) {
            val t = i.toDouble() / n
            val p = Vec3(start.x + (target.x - start.x) * t, start.y + (target.y - start.y) * t, start.z + (target.z - start.z) * t)
            synchronized(lock) {
                if (teleports != teleportsBefore) throw BotException("$name: server corrected the position to $pos while walking to $target")
                pos = p
            }
            sendMove(p, onGround)
            Thread.sleep(50)
        }
        // give the server a moment to object
        Thread.sleep(200)
        synchronized(lock) {
            if (teleports != teleportsBefore) throw BotException("$name: server corrected the position to $pos after walking to $target")
        }
    }

    /** Sends a chat message (unsigned; the network runs in offline mode). */
    fun chat(message: String) = sendPlay(P.S_CHAT) {
        writeChat(message, Instant.now().toEpochMilli(), ThreadLocalRandom.current().nextLong())
    }

    /** Runs a command, without the leading slash, e.g. `server survival` (Velocity) or `list`. */
    fun command(command: String) = sendPlay(P.S_CHAT_COMMAND) { writeString(command.removePrefix("/")) }

    fun disconnect() = close()

    override fun close() {
        val s = synchronized(lock) {
            if (phase != Phase.DISCONNECTED && disconnectReason == null) disconnectReason = "closed by the bot"
            socket.also { socket = null }
        }
        s?.close()
        reader?.takeIf { it != Thread.currentThread() }?.join(2000)
        setDisconnected("closed by the bot")
    }

    // --- internals ---

    private fun sendMove(p: Vec3, onGround: Boolean) = synchronized(lock) {
        // one position per client tick: the server disconnects clients that send two before tick end
        sendPlay(P.S_MOVE_PLAYER_POS_ROT) { writeMovePlayerPosRot(p, yaw, pitch, onGround) }
        sendPlay(P.S_CLIENT_TICK_END) {}
    }

    private fun sendPlay(id: Int, body: dev.vxcc.b8.e2e.PacketWriter.() -> Unit) {
        val c = synchronized(lock) {
            if (phase != Phase.PLAY) throw BotException("$name is not in play (phase $phase)")
            conn!!
        }
        c.send(id, body)
    }

    private fun setDisconnected(reason: String) = synchronized(lock) {
        if (phase != Phase.DISCONNECTED) {
            phase = Phase.DISCONNECTED
            if (disconnectReason == null) disconnectReason = reason
            log("[$name] disconnected: $disconnectReason")
        }
        lock.notifyAll()
    }

    private fun readLoop(c: Connection) {
        try {
            while (true) {
                val packet = c.read()
                when (phase) {
                    Phase.LOGIN -> handleLogin(c, packet)
                    Phase.CONFIGURATION -> handleConfiguration(c, packet)
                    Phase.PLAY -> handlePlay(c, packet)
                    Phase.DISCONNECTED -> return
                }
            }
        } catch (e: Exception) {
            val reason = when (e) {
                is EOFException, is SocketException -> "connection closed"
                is IOException -> "I/O error: $e"
                else -> "protocol error in phase $phase: $e"
            }
            if (e !is IOException) log("[$name] $reason\n${e.stackTraceToString()}")
            setDisconnected(reason)
            runCatching { socket?.close() }
        }
    }

    private fun kicked(reason: String) {
        synchronized(lock) { disconnectReason = "kicked: $reason" }
        throw EOFException()
    }

    private fun handleLogin(c: Connection, p: RawPacket) {
        val r = p.body
        when (p.id) {
            L.DISCONNECT -> kicked(jsonText(r.readString()))
            L.HELLO -> throw BotException("server wants encryption (online mode); the bot only supports offline mode")
            L.LOGIN_COMPRESSION -> c.compressionThreshold = r.readVarInt()
            L.CUSTOM_QUERY -> c.send(L.S_CUSTOM_QUERY_ANSWER) { writeVarInt(r.readVarInt()); writeBoolean(false) }
            L.COOKIE_REQUEST -> c.send(L.S_COOKIE_RESPONSE) { writeString(r.readString()); writeBoolean(false) }
            L.LOGIN_FINISHED -> {
                c.send(L.S_LOGIN_ACKNOWLEDGED) {}
                enterConfiguration(c)
            }
        }
    }

    private fun enterConfiguration(c: Connection) {
        synchronized(lock) { phase = Phase.CONFIGURATION }
        c.send(C.S_CLIENT_INFORMATION) { writeClientInformation(viewDistance) }
    }

    private fun handleConfiguration(c: Connection, p: RawPacket) {
        val r = p.body
        when (p.id) {
            C.DISCONNECT -> kicked(Nbt.readComponentText(r))
            C.KEEP_ALIVE -> { val id = r.readLong(); c.send(C.S_KEEP_ALIVE) { writeLong(id) } }
            C.PING -> { val id = r.readInt(); c.send(C.S_PONG) { writeInt(id) } }
            C.COOKIE_REQUEST -> c.send(C.S_COOKIE_RESPONSE) { writeString(r.readString()); writeBoolean(false) }
            // we know exactly the server's packs: it then sends registries without their contents
            C.SELECT_KNOWN_PACKS -> { val packs = Packets.readKnownPacks(r); c.send(C.S_SELECT_KNOWN_PACKS) { writeKnownPacks(packs) } }
            C.RESOURCE_PACK_PUSH -> acceptResourcePack(c, r.readUuid(), C.S_RESOURCE_PACK)
            C.CODE_OF_CONDUCT -> c.send(C.S_ACCEPT_CODE_OF_CONDUCT) {}
            C.FINISH_CONFIGURATION -> {
                synchronized(lock) { phase = Phase.PLAY }
                c.send(C.S_FINISH_CONFIGURATION) {}
            }
        }
    }

    private fun acceptResourcePack(c: Connection, id: UUID, packetId: Int) {
        // accepted, downloaded, successfully loaded: what a client that applied the pack sends
        for (action in intArrayOf(3, 4, 0)) c.send(packetId) { writeUuid(id); writeVarInt(action) }
    }

    private fun handlePlay(c: Connection, p: RawPacket) {
        val r = p.body
        when (p.id) {
            P.KEEP_ALIVE -> { val id = r.readLong(); c.send(P.S_KEEP_ALIVE) { writeLong(id) } }
            P.PING -> { val id = r.readInt(); c.send(P.S_PONG) { writeInt(id) } }
            P.DISCONNECT -> kicked(Nbt.readComponentText(r))
            P.COOKIE_REQUEST -> c.send(P.S_COOKIE_RESPONSE) { writeString(r.readString()); writeBoolean(false) }
            P.RESOURCE_PACK_PUSH -> acceptResourcePack(c, r.readUuid(), P.S_RESOURCE_PACK)
            P.CHUNK_BATCH_FINISHED -> c.send(P.S_CHUNK_BATCH_RECEIVED) { writeFloat(20f) }
            P.START_CONFIGURATION -> {
                // server switch (Velocity) or reconfiguration: back to the configuration phase
                synchronized(lock) { phase = Phase.CONFIGURATION; lock.notifyAll() }
                c.send(P.S_CONFIGURATION_ACKNOWLEDGED) {}
                c.send(C.S_CLIENT_INFORMATION) { writeClientInformation(viewDistance) }
            }
            P.LOGIN -> {
                val login = Packets.readPlayLogin(r)
                synchronized(lock) {
                    entityId = login.entityId
                    applySpawn(login.spawn)
                    joins++
                    lock.notifyAll()
                }
                log("[$name] login #$joins: ${login.spawn.dimension} (${login.spawn.gameMode})")
            }
            P.RESPAWN -> {
                val spawn = Packets.readRespawn(r)
                synchronized(lock) { applySpawn(spawn); lock.notifyAll() }
                log("[$name] respawn in ${spawn.dimension}")
            }
            P.PLAYER_POSITION -> teleported(c, Packets.readPlayerPosition(r))
            P.ADD_ENTITY -> {
                val e = Packets.readAddEntity(r)
                synchronized(lock) { entities[e.entityId] = e.uuid to e.position; lock.notifyAll() }
            }
            P.MOVE_ENTITY_POS, P.MOVE_ENTITY_POS_ROT -> {
                val m = Packets.readMoveEntity(r)
                updateEntity(m.entityId) { Packets.applyDelta(it, m) }
            }
            P.ENTITY_POSITION_SYNC -> {
                val (id, at) = Packets.readEntityPositionSync(r)
                updateEntity(id) { at }
            }
            P.TELEPORT_ENTITY -> {
                val (id, t, _) = Packets.readTeleportEntity(r)
                updateEntity(id) { t.resolve(it, 0f, 0f).first }
            }
            P.REMOVE_ENTITIES -> {
                val ids = Packets.readRemoveEntities(r)
                synchronized(lock) { ids.forEach(entities::remove); lock.notifyAll() }
            }
            P.SYSTEM_CHAT -> addChat(Packets.readSystemChat(r))
            P.PLAYER_CHAT -> addChat(Packets.readPlayerChat(r))
            P.DISGUISED_CHAT -> addChat(Packets.readDisguisedChat(r))
        }
    }

    private fun applySpawn(spawn: SpawnInfo) {
        dimension = spawn.dimension
        gameMode = spawn.gameMode
        hashedSeed = spawn.hashedSeed
        pos = null // until the server places us
        entities.clear()
        loadedSent = false
    }

    private fun updateEntity(id: Int, f: (Vec3) -> Vec3) = synchronized(lock) {
        val e = entities[id] ?: return@synchronized
        entities[id] = e.first to f(e.second)
        lock.notifyAll()
    }

    private fun addChat(m: ChatMessage) {
        synchronized(lock) { chat += m; lock.notifyAll() }
        log("[$name] ${m.kind.name.lowercase()} chat: ${m.senderName?.let { "<$it> " } ?: ""}${m.text}")
    }

    private fun teleported(c: Connection, t: PlayerPosition) {
        synchronized(lock) {
            val (p, y, x) = t.resolve(pos ?: Vec3(0.0, 0.0, 0.0), yaw, pitch)
            pos = p; yaw = y; pitch = x
            teleports++
            // like the vanilla client: confirm, then report the new position, then end the tick
            c.send(P.S_ACCEPT_TELEPORTATION) { writeAcceptTeleportation(t.teleportId, p, y, x) }
            c.send(P.S_MOVE_PLAYER_POS_ROT) { writeMovePlayerPosRot(p, y, x, onGround = false) }
            c.send(P.S_CLIENT_TICK_END) {}
            if (!loadedSent) {
                // the server ignores movement until the client says its world is loaded (or 60 ticks pass)
                c.send(P.S_PLAYER_LOADED) {}
                loadedSent = true
            }
            lock.notifyAll()
        }
    }

    private fun jsonText(json: String) = runCatching {
        dev.vxcc.b8.e2e.StatusPing.plainText(kotlinx.serialization.json.Json.parseToJsonElement(json))
    }.getOrDefault(json)

    override fun toString() = "Bot($name, $phase, $dimension, $pos)"
}
