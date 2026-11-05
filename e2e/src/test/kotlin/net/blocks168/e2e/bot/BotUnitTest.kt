package net.blocks168.e2e.bot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.blocks168.e2e.PacketReader
import net.blocks168.e2e.PacketWriter
import org.junit.jupiter.api.Tag
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import net.blocks168.e2e.bot.PacketIds.Configuration as C
import net.blocks168.e2e.bot.PacketIds.Login as L
import net.blocks168.e2e.bot.PacketIds.Play as P

@Tag("unit")
class BotUnitTest {
    @Test
    fun primitivesRoundTrip() {
        val uuid = UUID.randomUUID()
        val bytes = PacketWriter().apply {
            writeVarInt(300); writeString("hé"); writeBoolean(true); writeByte(-2); writeShort(-3)
            writeInt(-123456); writeLong(Long.MIN_VALUE + 7); writeFloat(1.5f); writeDouble(-64.25); writeUuid(uuid)
        }.toByteArray()
        val r = PacketReader(bytes)
        assertEquals(300, r.readVarInt())
        assertEquals("hé", r.readString())
        assertEquals(true, r.readBoolean())
        assertEquals(-2, r.readByte())
        assertEquals(-3, r.readShort())
        assertEquals(-123456, r.readInt())
        assertEquals(Long.MIN_VALUE + 7, r.readLong())
        assertEquals(1.5f, r.readFloat())
        assertEquals(-64.25, r.readDouble())
        assertEquals(uuid, r.readUuid())
        assertEquals(0, r.remaining)
        assertFailsWith<java.io.EOFException> { r.readByte() }
    }

    @Test
    fun framingWithAndWithoutCompression() {
        for (threshold in listOf(-1, 0, 256)) {
            val out = ByteArrayOutputStream()
            val writer = Connection(java.io.InputStream.nullInputStream(), out).apply { compressionThreshold = threshold }
            val big = ByteArray(10_000) { (it % 7).toByte() }
            writer.send(5) { writeString("small") }
            writer.send(6) { writeBytes(big) }
            val reader = Connection(out.toByteArray().inputStream(), ByteArrayOutputStream()).apply { compressionThreshold = threshold }
            reader.read().let { assertEquals(5, it.id); assertEquals("small", it.body.readString()) }
            reader.read().let { assertEquals(6, it.id); assertTrue(big.contentEquals(it.body.readRemaining())) }
        }
    }

    @Test
    fun nbtComponentText() {
        // {"text":"Hello ", "extra":[{"translate":"key","with":["a"]}]} in network NBT
        val w = PacketWriter()
        fun name(s: String) { w.writeShort(s.length); w.writeBytes(s.toByteArray()) }
        w.writeByte(10)
        w.writeByte(8); name("text"); name("Hello ")
        w.writeByte(9); name("extra"); w.writeByte(10); w.writeInt(1)
        w.writeByte(8); name("translate"); name("key")
        w.writeByte(9); name("with"); w.writeByte(8); w.writeInt(1); name("a")
        w.writeByte(0)
        w.writeByte(0)
        assertEquals("Hello key[a]", Nbt.readComponentText(PacketReader(w.toByteArray())))
        // plain string tag
        val s = PacketWriter().apply { writeByte(8); writeShort(2); writeBytes("hi".toByteArray()) }
        assertEquals("hi", Nbt.readComponentText(PacketReader(s.toByteArray())))
    }

    @Test
    fun packetIdsMatchTheGameReport() {
        val report = Json.parseToJsonElement(javaClass.getResource("/packets-26.3.json")!!.readText()).jsonObject
        fun id(phase: String, dir: String, name: String) =
            report.getValue(phase).jsonObject.getValue(dir).jsonObject.getValue("minecraft:$name").jsonObject.getValue("protocol_id").jsonPrimitive.int
        val expected = mapOf(
            L.DISCONNECT to id("login", "clientbound", "login_disconnect"),
            L.HELLO to id("login", "clientbound", "hello"),
            L.LOGIN_FINISHED to id("login", "clientbound", "login_finished"),
            L.LOGIN_COMPRESSION to id("login", "clientbound", "login_compression"),
            L.CUSTOM_QUERY to id("login", "clientbound", "custom_query"),
            L.COOKIE_REQUEST to id("login", "clientbound", "cookie_request"),
            L.S_HELLO to id("login", "serverbound", "hello"),
            L.S_CUSTOM_QUERY_ANSWER to id("login", "serverbound", "custom_query_answer"),
            L.S_LOGIN_ACKNOWLEDGED to id("login", "serverbound", "login_acknowledged"),
            L.S_COOKIE_RESPONSE to id("login", "serverbound", "cookie_response"),
        ).toList() + listOf(
            C.COOKIE_REQUEST to id("configuration", "clientbound", "cookie_request"),
            C.DISCONNECT to id("configuration", "clientbound", "disconnect"),
            C.FINISH_CONFIGURATION to id("configuration", "clientbound", "finish_configuration"),
            C.KEEP_ALIVE to id("configuration", "clientbound", "keep_alive"),
            C.PING to id("configuration", "clientbound", "ping"),
            C.RESOURCE_PACK_PUSH to id("configuration", "clientbound", "resource_pack_push"),
            C.SELECT_KNOWN_PACKS to id("configuration", "clientbound", "select_known_packs"),
            C.CODE_OF_CONDUCT to id("configuration", "clientbound", "code_of_conduct"),
            C.S_CLIENT_INFORMATION to id("configuration", "serverbound", "client_information"),
            C.S_COOKIE_RESPONSE to id("configuration", "serverbound", "cookie_response"),
            C.S_FINISH_CONFIGURATION to id("configuration", "serverbound", "finish_configuration"),
            C.S_KEEP_ALIVE to id("configuration", "serverbound", "keep_alive"),
            C.S_PONG to id("configuration", "serverbound", "pong"),
            C.S_RESOURCE_PACK to id("configuration", "serverbound", "resource_pack"),
            C.S_SELECT_KNOWN_PACKS to id("configuration", "serverbound", "select_known_packs"),
            C.S_ACCEPT_CODE_OF_CONDUCT to id("configuration", "serverbound", "accept_code_of_conduct"),
            P.ADD_ENTITY to id("play", "clientbound", "add_entity"),
            P.CHUNK_BATCH_FINISHED to id("play", "clientbound", "chunk_batch_finished"),
            P.COOKIE_REQUEST to id("play", "clientbound", "cookie_request"),
            P.DISCONNECT to id("play", "clientbound", "disconnect"),
            P.DISGUISED_CHAT to id("play", "clientbound", "disguised_chat"),
            P.ENTITY_POSITION_SYNC to id("play", "clientbound", "entity_position_sync"),
            P.KEEP_ALIVE to id("play", "clientbound", "keep_alive"),
            P.LOGIN to id("play", "clientbound", "login"),
            P.MOVE_ENTITY_POS to id("play", "clientbound", "move_entity_pos"),
            P.MOVE_ENTITY_POS_ROT to id("play", "clientbound", "move_entity_pos_rot"),
            P.PING to id("play", "clientbound", "ping"),
            P.PLAYER_CHAT to id("play", "clientbound", "player_chat"),
            P.PLAYER_POSITION to id("play", "clientbound", "player_position"),
            P.REMOVE_ENTITIES to id("play", "clientbound", "remove_entities"),
            P.RESOURCE_PACK_PUSH to id("play", "clientbound", "resource_pack_push"),
            P.RESPAWN to id("play", "clientbound", "respawn"),
            P.START_CONFIGURATION to id("play", "clientbound", "start_configuration"),
            P.SYSTEM_CHAT to id("play", "clientbound", "system_chat"),
            P.TELEPORT_ENTITY to id("play", "clientbound", "teleport_entity"),
            P.S_ACCEPT_TELEPORTATION to id("play", "serverbound", "accept_teleportation"),
            P.S_CHAT_COMMAND to id("play", "serverbound", "chat_command"),
            P.S_CHAT to id("play", "serverbound", "chat"),
            P.S_CHUNK_BATCH_RECEIVED to id("play", "serverbound", "chunk_batch_received"),
            P.S_CLIENT_TICK_END to id("play", "serverbound", "client_tick_end"),
            P.S_CONFIGURATION_ACKNOWLEDGED to id("play", "serverbound", "configuration_acknowledged"),
            P.S_COOKIE_RESPONSE to id("play", "serverbound", "cookie_response"),
            P.S_KEEP_ALIVE to id("play", "serverbound", "keep_alive"),
            P.S_MOVE_PLAYER_POS to id("play", "serverbound", "move_player_pos"),
            P.S_MOVE_PLAYER_POS_ROT to id("play", "serverbound", "move_player_pos_rot"),
            P.S_PLAYER_LOADED to id("play", "serverbound", "player_loaded"),
            P.S_PONG to id("play", "serverbound", "pong"),
            P.S_RESOURCE_PACK to id("play", "serverbound", "resource_pack"),
        )
        for ((ours, game) in expected) assertEquals(game, ours)
        assertEquals(0, id("handshake", "serverbound", "intention"))
    }

    @Test
    fun offlineUuidIsTheVanillaOne() {
        assertEquals(UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f"), Packets.offlineUuid("Notch"))
    }

    @Test
    fun relativeTeleportAndEntityDeltas() {
        val t = PlayerPosition(1, Vec3(1.0, 64.0, -2.0), 90f, 10f, relatives = 0b101) // X and Z relative
        val (p, yaw, pitch) = t.resolve(Vec3(10.0, 0.0, 10.0), 0f, 0f)
        assertEquals(Vec3(11.0, 64.0, 8.0), p)
        assertEquals(90f, yaw)
        assertEquals(10f, pitch)

        val moved = Packets.applyDelta(Vec3(0.5, 64.0, 0.5), Packets.MoveEntity(1, 4096, 0, -2048))
        assertEquals(Vec3(1.5, 64.0, 0.0), moved)

        // a stepped move (properties = steps << 1 | onGround): deltas add up
        val w = PacketWriter().apply {
            writeVarInt(7); writeVarInt((2 shl 1) or 1)
            writeVarInt(1); writeShort(4096); writeShort(0); writeShort(0)
            writeVarInt(2); writeShort(4096); writeShort(410); writeShort(0)
        }
        val m = Packets.readMoveEntity(PacketReader(w.toByteArray()))
        assertEquals(Packets.MoveEntity(7, 8192, 410, 0), m)
    }

    /**
     * The whole client state machine against a scripted fake server: compressed login, login plugin
     * query, configuration, play login, teleport, keep-alive, chat, and a Velocity-style server switch.
     */
    @Test
    fun fullSessionAgainstAFakeServer() {
        ServerSocket(0).use { server ->
            val received = LinkedBlockingQueue<Pair<String, RawPacket>>()
            val serverConn = arrayOfNulls<Connection>(1)
            val done = java.util.concurrent.CountDownLatch(1)
            var serverError: Throwable? = null
            val t = thread(isDaemon = true) {
              try {
                server.accept().use { socket ->
                    val c = Connection(socket.getInputStream(), socket.getOutputStream())
                    serverConn[0] = c
                    fun expect(id: Int, what: String): RawPacket {
                        val p = c.read()
                        assertEquals(id, p.id, "expected $what")
                        received += what to p
                        return p
                    }
                    val hs = expect(0, "handshake")
                    assertEquals(PacketIds.PROTOCOL_VERSION, hs.body.readVarInt())
                    val hello = expect(L.S_HELLO, "hello")
                    assertEquals("alice", hello.body.readString())
                    c.send(L.LOGIN_COMPRESSION) { writeVarInt(64) }
                    c.compressionThreshold = 64
                    c.send(L.CUSTOM_QUERY) { writeVarInt(42); writeString("velocity:player_info"); writeByte(1) }
                    assertEquals(42, expect(L.S_CUSTOM_QUERY_ANSWER, "query answer").body.readVarInt())
                    c.send(L.LOGIN_FINISHED) { writeUuid(Packets.offlineUuid("alice")); writeString("alice"); writeVarInt(0) }
                    expect(L.S_LOGIN_ACKNOWLEDGED, "login ack")

                    fun configure(dimension: String, mode: GameMode) {
                        expect(C.S_CLIENT_INFORMATION, "client information")
                        c.send(C.SELECT_KNOWN_PACKS) { writeVarInt(1); writeString("minecraft"); writeString("core"); writeString("26.3") }
                        val packs = expect(C.S_SELECT_KNOWN_PACKS, "known packs").body
                        assertEquals(1, packs.readVarInt())
                        assertEquals("minecraft", packs.readString())
                        c.send(C.KEEP_ALIVE) { writeLong(7) }
                        assertEquals(7, expect(C.S_KEEP_ALIVE, "config keep-alive").body.readLong())
                        c.send(7) { writeBytes(ByteArray(300)) } // registry data: skipped, compressed
                        c.send(C.FINISH_CONFIGURATION) {}
                        expect(C.S_FINISH_CONFIGURATION, "finish configuration")
                        c.send(P.LOGIN) {
                            writeInt(17); writeBoolean(false)
                            writeVarInt(1); writeString(dimension)
                            writeVarInt(500); writeVarInt(8); writeVarInt(6)
                            writeBoolean(false); writeBoolean(true); writeBoolean(false)
                            writeVarInt(0); writeString(dimension); writeLong(1234)
                            writeVarInt(mode.ordinal); writeVarInt(0); writeBoolean(false); writeBoolean(false)
                            writeBoolean(false); writeVarInt(0); writeVarInt(63)
                            writeBoolean(false); writeBoolean(false)
                        }
                        c.send(P.PLAYER_POSITION) {
                            writeVarInt(5)
                            writeDouble(0.5); writeDouble(70.0); writeDouble(-3.5)
                            writeDouble(0.0); writeDouble(0.0); writeDouble(0.0)
                            writeFloat(45f); writeFloat(0f); writeInt(0)
                        }
                        val accept = expect(P.S_ACCEPT_TELEPORTATION, "accept teleport").body
                        assertEquals(5, accept.readVarInt())
                        assertEquals(0.5, accept.readDouble())
                        expect(P.S_MOVE_PLAYER_POS_ROT, "position after teleport")
                        expect(P.S_CLIENT_TICK_END, "tick end")
                    }
                    configure("minecraft:overworld", GameMode.ADVENTURE)
                    expect(P.S_PLAYER_LOADED, "player loaded")
                    c.send(P.KEEP_ALIVE) { writeLong(99) }
                    assertEquals(99, expect(P.S_KEEP_ALIVE, "play keep-alive").body.readLong())
                    c.send(P.SYSTEM_CHAT) { writeByte(8); writeShort(5); writeBytes("hello".toByteArray()); writeBoolean(false) }
                    assertEquals("hi there", expect(P.S_CHAT, "chat").body.readString())
                    assertEquals("server survival", expect(P.S_CHAT_COMMAND, "command").body.readString())
                    // server switch
                    c.send(P.START_CONFIGURATION) {}
                    expect(P.S_CONFIGURATION_ACKNOWLEDGED, "configuration ack")
                    configure("minecraft:the_nether", GameMode.SURVIVAL)
                    expect(P.S_PLAYER_LOADED, "player loaded")
                    done.await(10, TimeUnit.SECONDS)
                }
              } catch (e: Throwable) {
                serverError = e
              }
            }
            try {
            Bot("alice", log = {}).use { bot ->
                bot.connect("127.0.0.1", server.localPort, 10.seconds)
                bot.awaitWorld("minecraft:overworld")
                assertEquals(GameMode.ADVENTURE, bot.gameMode)
                assertEquals(Vec3(0.5, 70.0, -3.5), bot.position)
                assertEquals(17, bot.entityId)
                assertEquals("hello", bot.awaitChat { it.system }.text)
                bot.chat("hi there")
                bot.command("/server survival")
                bot.awaitJoins(2, 10.seconds)
                bot.awaitWorld("minecraft:the_nether")
                assertEquals(GameMode.SURVIVAL, bot.gameMode)
                done.countDown()
            }
            } catch (e: Throwable) {
                serverError?.let { e.addSuppressed(it); it.printStackTrace() }
                throw e
            }
            t.join(5000)
            serverError?.let { throw it }
            assertNotNull(received.find { it.first == "configuration ack" }, "server switch not acknowledged")
        }
    }

    @Test
    fun kickDuringLoginIsReported() {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                server.accept().use { s ->
                    val c = Connection(s.getInputStream(), s.getOutputStream())
                    c.read(); c.read()
                    c.send(L.DISCONNECT) { writeString("""{"text":"go away"}""") }
                    Thread.sleep(500)
                }
            }
            val e = assertFailsWith<BotException> { Bot("bob", log = {}).connect("127.0.0.1", server.localPort, 5.seconds) }
            assertTrue("kicked: go away" in e.message!!, e.message)
        }
    }
}
