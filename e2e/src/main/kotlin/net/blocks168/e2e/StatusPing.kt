package net.blocks168.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minecraft Server List Ping (handshake with next state "status", then status request), the first
 * thing a client does with a server. Talks the same protocol as the game client, so it exercises the
 * whole path a player's connection takes (edge proxy, PROXY protocol, Velocity).
 */
object StatusPing {
    data class Status(
        val versionName: String,
        val protocol: Int,
        val onlinePlayers: Int,
        val maxPlayers: Int,
        /** MOTD as plain text */
        val description: String,
        val raw: JsonObject,
    )

    fun ping(host: String, port: Int, protocolVersion: Int = -1, timeoutMs: Int = 5000): Status {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val out = socket.getOutputStream()
            out.writePacket(0x00) {
                writeVarInt(protocolVersion)
                writeString(host)
                writeShort(port)
                writeVarInt(1) // next state: status
            }
            out.writePacket(0x00) {} // status request
            out.flush()

            val input = DataInputStream(socket.getInputStream())
            val length = input.readVarInt()
            val body = ByteArray(length).also(input::readFully)
            val packet = DataInputStream(body.inputStream())
            val id = packet.readVarInt()
            check(id == 0x00) { "expected status response (0x00), got 0x${id.toString(16)}" }
            return parse(packet.readString())
        }
    }

    fun parse(json: String): Status {
        val obj = Json.parseToJsonElement(json).jsonObject
        val version = obj["version"]?.jsonObject
        val players = obj["players"]?.jsonObject
        return Status(
            versionName = version?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
            protocol = version?.get("protocol")?.jsonPrimitive?.int ?: -1,
            onlinePlayers = players?.get("online")?.jsonPrimitive?.int ?: 0,
            maxPlayers = players?.get("max")?.jsonPrimitive?.int ?: 0,
            description = obj["description"]?.let(::plainText) ?: "",
            raw = obj,
        )
    }

    /** Flattens a chat component (string, object with text/extra, or array) to its text. */
    fun plainText(component: JsonElement): String = when (component) {
        is JsonObject -> buildString {
            component["text"]?.jsonPrimitive?.contentOrNull?.let(::append)
            component["extra"]?.jsonArray?.forEach { append(plainText(it)) }
        }
        is kotlinx.serialization.json.JsonArray -> component.joinToString("") { plainText(it) }
        else -> component.jsonPrimitive.contentOrNull ?: ""
    }
}

// --- protocol primitives (shared with the future protocol-level bot client) ---

internal class PacketWriter {
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
    fun writeShort(v: Int) {
        bytes.write((v shr 8) and 0xff)
        bytes.write(v and 0xff)
    }
}

internal fun OutputStream.writePacket(id: Int, body: PacketWriter.() -> Unit) {
    val payload = PacketWriter().apply { writeVarInt(id); body() }.bytes.toByteArray()
    val frame = PacketWriter().apply { writeVarInt(payload.size) }.bytes.toByteArray()
    write(frame)
    write(payload)
}

internal fun InputStream.readVarInt(): Int {
    var result = 0
    for (i in 0 until 5) {
        val b = read()
        if (b < 0) throw EOFException()
        result = result or ((b and 0x7f) shl (7 * i))
        if (b and 0x80 == 0) return result
    }
    error("VarInt too long")
}

internal fun DataInputStream.readString(): String {
    val len = readVarInt()
    return String(ByteArray(len).also(::readFully), Charsets.UTF_8)
}
