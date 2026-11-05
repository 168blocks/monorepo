package net.blocks168.e2e

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

// Minecraft protocol primitives (big-endian, VarInt-prefixed strings and lists), shared by StatusPing
// and the protocol-level bot client (net.blocks168.e2e.bot).

/** Builds a packet body. */
class PacketWriter {
    val bytes = ByteArrayOutputStream()

    fun writeVarInt(value: Int) {
        var v = value
        while (true) {
            if (v and 0x7f.inv() == 0) { bytes.write(v); return }
            bytes.write((v and 0x7f) or 0x80)
            v = v ushr 7
        }
    }
    fun writeString(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeVarInt(b.size)
        bytes.write(b)
    }
    fun writeByte(v: Int) = bytes.write(v and 0xff)
    fun writeBoolean(v: Boolean) = bytes.write(if (v) 1 else 0)
    fun writeShort(v: Int) {
        bytes.write((v shr 8) and 0xff)
        bytes.write(v and 0xff)
    }
    fun writeInt(v: Int) {
        writeShort(v shr 16)
        writeShort(v)
    }
    fun writeLong(v: Long) {
        writeInt((v shr 32).toInt())
        writeInt(v.toInt())
    }
    fun writeFloat(v: Float) = writeInt(v.toRawBits())
    fun writeDouble(v: Double) = writeLong(v.toRawBits())
    fun writeUuid(v: UUID) {
        writeLong(v.mostSignificantBits)
        writeLong(v.leastSignificantBits)
    }
    fun writeBytes(b: ByteArray) = bytes.write(b)

    fun toByteArray(): ByteArray = bytes.toByteArray()
}

/** Reads a packet body; throws [EOFException] when reading past its end. */
class PacketReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    val remaining get() = end - pos

    private fun need(n: Int) { if (n < 0 || remaining < n) throw EOFException("need $n bytes, $remaining left") }

    fun readUnsignedByte(): Int { need(1); return buf[pos++].toInt() and 0xff }
    fun readByte(): Int = readUnsignedByte().toByte().toInt()
    fun readBoolean(): Boolean = readUnsignedByte() != 0
    fun readUnsignedShort(): Int = (readUnsignedByte() shl 8) or readUnsignedByte()
    fun readShort(): Int = readUnsignedShort().toShort().toInt()
    fun readInt(): Int = (readUnsignedShort() shl 16) or readUnsignedShort()
    fun readLong(): Long = (readInt().toLong() shl 32) or (readInt().toLong() and 0xffffffffL)
    fun readFloat(): Float = Float.fromBits(readInt())
    fun readDouble(): Double = Double.fromBits(readLong())
    fun readUuid(): UUID = UUID(readLong(), readLong())
    fun readVarInt(): Int {
        var result = 0
        for (i in 0 until 5) {
            val b = readUnsignedByte()
            result = result or ((b and 0x7f) shl (7 * i))
            if (b and 0x80 == 0) return result
        }
        error("VarInt too long")
    }
    fun readBytes(n: Int): ByteArray { need(n); return buf.copyOfRange(pos, pos + n).also { pos += n } }
    fun readString(): String = String(readBytes(readVarInt()), Charsets.UTF_8)
    fun skip(n: Int) { need(n); pos += n }
    fun readRemaining(): ByteArray = readBytes(remaining)
}

fun OutputStream.writePacket(id: Int, body: PacketWriter.() -> Unit) {
    val payload = PacketWriter().apply { writeVarInt(id); body() }.toByteArray()
    val frame = PacketWriter().apply { writeVarInt(payload.size) }.toByteArray()
    write(frame)
    write(payload)
}

fun InputStream.readVarInt(): Int {
    var result = 0
    for (i in 0 until 5) {
        val b = read()
        if (b < 0) throw EOFException()
        result = result or ((b and 0x7f) shl (7 * i))
        if (b and 0x80 == 0) return result
    }
    error("VarInt too long")
}

fun DataInputStream.readString(): String {
    val len = readVarInt()
    return String(ByteArray(len).also(::readFully), Charsets.UTF_8)
}

/** Java's modified UTF-8 with an unsigned short length, as NBT strings use it. */
internal fun PacketReader.readModifiedUtf8(): String {
    val len = readUnsignedShort()
    val raw = readBytes(len)
    return DataInputStream(ByteArrayInputStream(byteArrayOf((len shr 8).toByte(), len.toByte()) + raw)).readUTF()
}
