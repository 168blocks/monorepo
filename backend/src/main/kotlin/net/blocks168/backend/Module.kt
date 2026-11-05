package net.blocks168.backend

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import net.blocks168.api.ApiJson
import net.blocks168.api.ApiPaths
import net.blocks168.api.Counter
import net.blocks168.api.Health
import net.blocks168.pgecs.PgEcs
import java.io.File

class BackendServices(val ecs: PgEcs?, val counter: CounterStore)

fun Application.backendModule(services: BackendServices, frontendDir: File? = null) {
    install(ContentNegotiation) { json(ApiJson) }
    install(CORS) {
        // the frontend's dev servers (wasm browser run, desktop) run on other origins
        anyHost()
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.ContentType)
    }
    routing {
        get(ApiPaths.HEALTH) {
            val db = services.ecs?.let { runCatching { it.ping().version }.getOrNull() }
            val ok = services.ecs == null || db != null
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, Health(if (ok) "ok" else "degraded", db))
        }
        get(ApiPaths.COUNTER) { call.respond(Counter(services.counter.get())) }
        post(ApiPaths.COUNTER_INCREMENT) { call.respond(Counter(services.counter.increment())) }
        if (frontendDir != null) staticFiles("/", frontendDir) { default("index.html") }
    }
}
