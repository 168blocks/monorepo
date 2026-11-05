package dev.vxcc.b8.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Paths of the backend HTTP API (relative to the backend's base URL). */
object ApiPaths {
    const val HEALTH = "/api/health"
    const val COUNTER = "/api/counter"
    const val COUNTER_INCREMENT = "/api/counter/increment"
}

/** `GET /api/counter`, `POST /api/counter/increment` */
@Serializable
data class Counter(val value: Long)

/** `GET /api/health` */
@Serializable
data class Health(val status: String, val database: String?)

/** JSON settings both sides use. */
val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
