package dev.vxcc.b8.e2e.bot

import dev.vxcc.b8.e2e.PacketReader
import dev.vxcc.b8.e2e.PacketWriter
import java.util.UUID
import kotlin.math.roundToLong

// Formats of the Minecraft 26.3 packets the bot parses or sends, taken from the game's own stream
// codecs (net.minecraft.network.protocol.*). Each decoder reads only the fields the bot needs; the
// rest of the packet is skipped by its length.

data class Vec3(val x: Double, val y: Double, val z: Double) {
    fun distanceTo(o: Vec3) = Math.sqrt((x - o.x) * (x - o.x) + (y - o.y) * (y - o.y) + (z - o.z) * (z - o.z))
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    override fun toString() = "(%.3f, %.3f, %.3f)".format(x, y, z)
}

enum class GameMode { SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR }

/** CommonPlayerSpawnInfo, part of the play login and respawn packets. */
data class SpawnInfo(
    val dimensionTypeId: Int,
    /** dimension (world) key, e.g. minecraft:overworld */
    val dimension: String,
    val hashedSeed: Long,
    val gameMode: GameMode,
    val isFlat: Boolean,
)

/** Clientbound play `login`: the first packet after configuration, again after every server switch. */
data class PlayLogin(
    val entityId: Int,
    val hardcore: Boolean,
    val dimensions: List<String>,
    val spawn: SpawnInfo,
)

/** Clientbound `player_position`: the server sets (teleports) the player. */
data class PlayerPosition(
    val teleportId: Int,
    val position: Vec3,
    val yRot: Float,
    val xRot: Float,
    /** Relative flags bit set (X, Y, Z, Y_ROT, X_ROT, DELTA_X, DELTA_Y, DELTA_Z, ROTATE_DELTA) */
    val relatives: Int,
) {
    /** Absolute position and rotation, given the current ones. */
    fun resolve(current: Vec3, yaw: Float, pitch: Float): Triple<Vec3, Float, Float> {
        fun rel(bit: Int) = relatives and (1 shl bit) != 0
        val pos = Vec3(
            if (rel(0)) current.x + position.x else position.x,
            if (rel(1)) current.y + position.y else position.y,
            if (rel(2)) current.z + position.z else position.z,
        )
        val y = if (rel(3)) yaw + yRot else yRot
        val x = (if (rel(4)) pitch + xRot else xRot).coerceIn(-90f, 90f)
        return Triple(pos, y, x)
    }
}

data class ChatMessage(
    val kind: Kind,
    val text: String,
    /** sender of a `player_chat` message */
    val sender: UUID? = null,
    /** sender name of a `disguised_chat` message (unsigned chat that the server sends without a sender UUID) */
    val senderName: String? = null,
) {
    enum class Kind { SYSTEM, PLAYER, DISGUISED }
    val system get() = kind == Kind.SYSTEM
}

object Packets {
    fun readIdentifier(r: PacketReader) = r.readString()

    private fun readSpawnInfo(r: PacketReader): SpawnInfo {
        val dimensionType = r.readVarInt() // Holder<DimensionType> by registry id
        val dimension = readIdentifier(r)
        val seed = r.readLong()
        val gameMode = GameMode.entries[r.readVarInt()]
        r.readVarInt() // previous game type, OPTIONAL_VAR_INT
        r.readBoolean() // debug
        val flat = r.readBoolean()
        // last death location, portal cooldown and sea level follow; not needed
        return SpawnInfo(dimensionType, dimension, seed, gameMode, flat)
    }

    fun readPlayLogin(r: PacketReader): PlayLogin {
        val entityId = r.readInt()
        val hardcore = r.readBoolean()
        val dimensions = List(r.readVarInt()) { readIdentifier(r) }
        r.readVarInt() // max players
        r.readVarInt() // chunk radius
        r.readVarInt() // simulation distance
        r.readBoolean() // reduced debug info
        r.readBoolean() // show death screen
        r.readBoolean() // limited crafting
        return PlayLogin(entityId, hardcore, dimensions, readSpawnInfo(r))
    }

    fun readRespawn(r: PacketReader): SpawnInfo = readSpawnInfo(r)

    private fun readVec3(r: PacketReader) = Vec3(r.readDouble(), r.readDouble(), r.readDouble())

    fun readPlayerPosition(r: PacketReader): PlayerPosition {
        val id = r.readVarInt()
        val pos = readVec3(r)
        readVec3(r) // delta movement
        val yRot = r.readFloat()
        val xRot = r.readFloat()
        return PlayerPosition(id, pos, yRot, xRot, r.readInt())
    }

    data class AddEntity(val entityId: Int, val uuid: UUID, val type: Int, val position: Vec3)

    fun readAddEntity(r: PacketReader) = AddEntity(r.readVarInt(), r.readUuid(), r.readVarInt(), readVec3(r))

    /** `move_entity_pos(_rot)`: entity id and the total offset in 1/4096 block units (VecDelta). */
    data class MoveEntity(val entityId: Int, val dx: Long, val dy: Long, val dz: Long)

    fun readMoveEntity(r: PacketReader): MoveEntity {
        val id = r.readVarInt()
        val steps = r.readVarInt() ushr 1 // bit 0: on ground
        var dx = 0L
        var dy = 0L
        var dz = 0L
        if (steps <= 0) {
            dx = r.readShort().toLong(); dy = r.readShort().toLong(); dz = r.readShort().toLong()
        } else repeat(steps) {
            r.readVarInt() // ticks
            dx += r.readShort(); dy += r.readShort(); dz += r.readShort()
        }
        return MoveEntity(id, dx, dy, dz)
    }

    /** Applies a VecDelta offset to a position the way the client does (VecDeltaCodec). */
    fun applyDelta(base: Vec3, m: MoveEntity): Vec3 {
        fun enc(v: Double) = (v * 4096.0).roundToLong()
        fun axis(b: Double, d: Long) = if (d == 0L) b else (enc(b) + d) / 4096.0
        return Vec3(axis(base.x, m.dx), axis(base.y, m.dy), axis(base.z, m.dz))
    }

    /** `entity_position_sync`: id and end position of its PositionPath. */
    fun readEntityPositionSync(r: PacketReader): Pair<Int, Vec3> {
        val id = r.readVarInt()
        val pos = when (val type = r.readVarInt()) {
            0 -> readVec3(r) // linear
            1 -> { // stepped: list of (position, tick offset); the end position is the last one
                var last: Vec3? = null
                repeat(r.readVarInt()) { last = readVec3(r); r.readVarInt() }
                last
            }
            else -> error("unknown PositionPath type $type")
        }
        return id to (pos ?: error("empty stepped path"))
    }

    /** `teleport_entity`: id, position (absolute or relative per flags). */
    fun readTeleportEntity(r: PacketReader): Triple<Int, PlayerPosition, Boolean> {
        val id = r.readVarInt()
        val pos = readVec3(r)
        readVec3(r)
        val yRot = r.readFloat()
        val xRot = r.readFloat()
        val relatives = r.readInt()
        return Triple(id, PlayerPosition(0, pos, yRot, xRot, relatives), r.readBoolean())
    }

    fun readRemoveEntities(r: PacketReader): List<Int> = List(r.readVarInt()) { r.readVarInt() }

    fun readSystemChat(r: PacketReader) = ChatMessage(ChatMessage.Kind.SYSTEM, Nbt.readComponentText(r))

    fun readPlayerChat(r: PacketReader): ChatMessage {
        r.readVarInt() // global index
        val sender = r.readUuid()
        r.readVarInt() // index
        if (r.readBoolean()) r.skip(256) // signature
        val content = r.readString()
        return ChatMessage(ChatMessage.Kind.PLAYER, content, sender = sender)
    }

    fun readDisguisedChat(r: PacketReader): ChatMessage {
        val text = Nbt.readComponentText(r)
        // ChatType.Bound: chat type holder (registry id + 1, 0 = inline definition), sender name, target
        val name = if (r.readVarInt() != 0) runCatching { Nbt.readComponentText(r) }.getOrNull() else null
        return ChatMessage(ChatMessage.Kind.DISGUISED, text, senderName = name)
    }

    data class KnownPack(val namespace: String, val id: String, val version: String)

    fun readKnownPacks(r: PacketReader) = List(r.readVarInt()) { KnownPack(r.readString(), r.readString(), r.readString()) }

    fun PacketWriter.writeKnownPacks(packs: List<KnownPack>) {
        writeVarInt(packs.size)
        for (p in packs) { writeString(p.namespace); writeString(p.id); writeString(p.version) }
    }

    /** ClientInformation (language, view distance, chat, skin parts, arm, filtering, listing, particles). */
    fun PacketWriter.writeClientInformation(viewDistance: Int) {
        writeString("en_us")
        writeByte(viewDistance)
        writeVarInt(0) // chat visibility: full
        writeBoolean(true) // chat colors
        writeByte(0x7f) // model customisation: all skin parts
        writeVarInt(1) // main hand: right
        writeBoolean(false) // text filtering
        writeBoolean(true) // allows listing
        writeVarInt(2) // particles: minimal
    }

    fun PacketWriter.writeAcceptTeleportation(id: Int, pos: Vec3, yaw: Float, pitch: Float) {
        writeVarInt(id)
        writeDouble(pos.x); writeDouble(pos.y); writeDouble(pos.z)
        writeFloat(yaw); writeFloat(pitch)
    }

    fun PacketWriter.writeMovePlayerPosRot(pos: Vec3, yaw: Float, pitch: Float, onGround: Boolean) {
        writeDouble(pos.x); writeDouble(pos.y); writeDouble(pos.z)
        writeFloat(yaw); writeFloat(pitch)
        writeByte(if (onGround) 1 else 0) // flags: on ground, horizontal collision
    }

    /** Unsigned chat message (offline mode, no chat session). */
    fun PacketWriter.writeChat(message: String, timestamp: Long, salt: Long) {
        writeString(message)
        writeLong(timestamp)
        writeLong(salt)
        writeBoolean(false) // no signature
        writeVarInt(0) // last seen: offset
        writeBytes(ByteArray(3)) // acknowledged: fixed bit set of 20
        writeByte(0) // checksum: ignore
    }

    fun offlineUuid(name: String): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
}
