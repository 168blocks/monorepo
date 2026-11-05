package net.blocks168.velocity.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

/** Cross-edge actions, sent as JSON over PostgreSQL NOTIFY on [NetworkStore.CHANNEL] to every edge. */
sealed interface NetworkMessage {
    /** Disconnects the player [uuid] (or [name]) on whichever edge has them, except session [exceptSession] */
    data class Kick(val uuid: UUID?, val name: String? = null, val exceptSession: UUID? = null, val reason: String) : NetworkMessage

    /** Moves [name] to backend [server] (like /server) */
    data class Send(val name: String, val server: String) : NetworkMessage

    /** Chat message to every player ([staffOnly]: only staff) */
    data class Broadcast(val message: String, val staffOnly: Boolean, val from: String) : NetworkMessage

    /** Starts or ends draining [edge] */
    data class Drain(val edge: String, val draining: Boolean) : NetworkMessage

    fun encode(): String = JsonObject().apply {
        when (val m = this@NetworkMessage) {
            is Kick -> {
                addProperty("type", "kick")
                m.uuid?.let { addProperty("uuid", it.toString()) }
                m.name?.let { addProperty("name", it) }
                m.exceptSession?.let { addProperty("exceptSession", it.toString()) }
                addProperty("reason", m.reason)
            }
            is Send -> { addProperty("type", "send"); addProperty("name", m.name); addProperty("server", m.server) }
            is Broadcast -> {
                addProperty("type", "broadcast"); addProperty("message", m.message)
                addProperty("staffOnly", m.staffOnly); addProperty("from", m.from)
            }
            is Drain -> { addProperty("type", "drain"); addProperty("edge", m.edge); addProperty("draining", m.draining) }
        }
    }.toString()

    companion object {
        fun decode(json: String): NetworkMessage {
            val o = JsonParser.parseString(json).asJsonObject
            fun str(k: String) = o.get(k)?.takeUnless { it.isJsonNull }?.asString
            fun req(k: String) = str(k) ?: throw IllegalArgumentException("missing $k in $json")
            return when (val type = req("type")) {
                "kick" -> Kick(str("uuid")?.let(UUID::fromString), str("name"), str("exceptSession")?.let(UUID::fromString), req("reason"))
                "send" -> Send(req("name"), req("server"))
                "broadcast" -> Broadcast(req("message"), o.get("staffOnly")?.asBoolean ?: false, req("from"))
                "drain" -> Drain(req("edge"), o.get("draining")?.asBoolean ?: true)
                else -> throw IllegalArgumentException("unknown message type $type")
            }
        }
    }
}
