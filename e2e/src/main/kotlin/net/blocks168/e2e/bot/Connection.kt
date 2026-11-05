package net.blocks168.e2e.bot

import net.blocks168.e2e.PacketReader
import net.blocks168.e2e.PacketWriter
import net.blocks168.e2e.readVarInt
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** A received packet: its id and a reader positioned at the start of its body. */
class RawPacket(val id: Int, val body: PacketReader)

/**
 * Minecraft packet framing over a byte stream: VarInt length prefix and, once the server enabled it
 * (login compression), zlib compression of packets at or above the threshold. Symmetric, so the same
 * class serves the bot and the fake servers in the unit tests. No encryption: the bot only talks to
 * offline-mode servers.
 */
class Connection(input: InputStream, output: OutputStream) {
    private val input = DataInputStream(BufferedInputStream(input))
    private val output = BufferedOutputStream(output)
    private val inflater = Inflater()
    private val deflater = Deflater()

    /** Compression threshold; negative means compression is off. */
    @Volatile var compressionThreshold = -1

    fun read(): RawPacket {
        val length = input.readVarInt()
        if (length <= 0 || length > MAX_FRAME) throw IOException("bad frame length $length")
        val frame = ByteArray(length).also(input::readFully)
        var data = frame
        var offset = 0
        if (compressionThreshold >= 0) {
            val r = PacketReader(frame)
            val dataLength = r.readVarInt()
            offset = length - r.remaining
            if (dataLength != 0) {
                if (dataLength > MAX_DATA) throw IOException("bad uncompressed length $dataLength")
                data = ByteArray(dataLength)
                inflater.reset()
                inflater.setInput(frame, offset, length - offset)
                var n = 0
                while (n < dataLength) {
                    val k = inflater.inflate(data, n, dataLength - n)
                    if (k == 0 && (inflater.finished() || inflater.needsInput())) break
                    n += k
                }
                if (n != dataLength) throw IOException("inflated $n bytes, expected $dataLength")
                offset = 0
            }
        }
        val reader = PacketReader(data, offset, data.size)
        return RawPacket(reader.readVarInt(), reader)
    }

    fun send(id: Int, body: PacketWriter.() -> Unit) {
        val payload = PacketWriter().apply { writeVarInt(id); body() }.toByteArray()
        sendRaw(payload)
    }

    @Synchronized
    fun sendRaw(payload: ByteArray) {
        val threshold = compressionThreshold
        val frame = PacketWriter()
        if (threshold < 0) {
            frame.writeVarInt(payload.size)
            frame.writeBytes(payload)
        } else if (payload.size < threshold) {
            val inner = PacketWriter().apply { writeVarInt(0); writeBytes(payload) }.toByteArray()
            frame.writeVarInt(inner.size)
            frame.writeBytes(inner)
        } else {
            deflater.reset()
            deflater.setInput(payload)
            deflater.finish()
            val compressed = PacketWriter()
            val chunk = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                compressed.bytes.write(chunk, 0, n)
            }
            val inner = PacketWriter().apply { writeVarInt(payload.size); writeBytes(compressed.toByteArray()) }.toByteArray()
            frame.writeVarInt(inner.size)
            frame.writeBytes(inner)
        }
        output.write(frame.toByteArray())
        output.flush()
    }

    companion object {
        const val MAX_FRAME = 1 shl 21 // 2 MiB, the vanilla limit
        const val MAX_DATA = 1 shl 23 // 8 MiB, the vanilla limit for uncompressed data
    }
}
