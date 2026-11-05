package net.blocks168.common

import dev.vxcc.tinyjcbor.Cbor
import dev.vxcc.tinyjcbor.CborDecoder
import dev.vxcc.tinyjcbor.CborEncoder
import dev.vxcc.tinyjcbor.serde.CborDeserializer
import dev.vxcc.tinyjcbor.serde.CborPrim
import dev.vxcc.tinyjcbor.serde.CborSerializer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sent by a Minecraft server to the Velocity proxy over the [CHANNEL] plugin message channel when a
 * player joins it, so the proxy knows which backend (and which version of our mods) it talks to.
 *
 * Example of a message shared across the stack; the wire format is CBOR (tinyjcbor), an array of
 * `[serverId, modVersion, players]`.
 */
data class ServerHello(
    val serverId: String,
    val modVersion: String,
    val players: Int,
) {
    fun encode(): ByteArray = Cbor.encode(ByteOrder.BIG_ENDIAN, this, SERIALIZER)

    companion object {
        /** Plugin message channel (namespace:path) */
        const val CHANNEL = "blocks168:hello"

        val SERIALIZER = CborSerializer<ServerHello> { out: CborEncoder, v: ServerHello ->
            out.writeArray(3)
            out.writeText(v.serverId)
            out.writeText(v.modVersion)
            out.writeUnsigned(v.players.toLong())
        }

        val DESERIALIZER = CborDeserializer<ServerHello> { input: CborDecoder ->
            val len = input.readArrayRaw()
            require(len == 3L) { "ServerHello: expected 3 fields, got $len" }
            ServerHello(
                serverId = input.read(CborPrim.STRING),
                modVersion = input.read(CborPrim.STRING),
                players = Math.toIntExact(input.readUInt()),
            )
        }

        fun decode(bytes: ByteArray): ServerHello = Cbor.decode(ByteBuffer.wrap(bytes), DESERIALIZER)
    }
}
