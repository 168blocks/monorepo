package net.blocks168.frontend

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.serialization.kotlinx.json.json
import net.blocks168.api.ApiJson
import net.blocks168.api.ApiPaths
import net.blocks168.api.Counter

/** Talks to the backend at [baseUrl] (empty: the origin the web page came from). */
class BackendClient(private val baseUrl: String, engine: HttpClient = HttpClient()) {
    private val http = engine.config { install(ContentNegotiation) { json(ApiJson) } }

    suspend fun counter(): Counter = http.get(baseUrl + ApiPaths.COUNTER).body()
    suspend fun increment(): Counter = http.post(baseUrl + ApiPaths.COUNTER_INCREMENT).body()
}
