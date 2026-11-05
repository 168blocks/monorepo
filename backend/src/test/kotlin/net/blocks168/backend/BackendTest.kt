package net.blocks168.backend

import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import net.blocks168.api.ApiJson
import net.blocks168.api.ApiPaths
import net.blocks168.api.Counter
import net.blocks168.api.Health
import net.blocks168.pgecs.PgEcs
import net.blocks168.pgecs.testing.TestPostgres
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BackendTest {
    @Test
    fun `counter persists in postgres`() {
        PgEcs.connect(TestPostgres.config).use { ecs ->
            val store = PgCounterStore(ecs).also { it.migrate() }
            testApplication {
                application { backendModule(BackendServices(ecs, store)) }
                val client = createClient { install(ContentNegotiation) { json(ApiJson) } }

                val health = client.get(ApiPaths.HEALTH)
                assertEquals(HttpStatusCode.OK, health.status)
                assertNotNull(health.body<Health>().database)

                val before = client.get(ApiPaths.COUNTER).body<Counter>().value
                assertEquals(before + 1, client.post(ApiPaths.COUNTER_INCREMENT).body<Counter>().value)
                assertEquals(before + 1, client.get(ApiPaths.COUNTER).body<Counter>().value)
            }
            // a second store on the same database sees the value
            assertEquals(store.get(), PgCounterStore(ecs).get())
        }
    }
}
