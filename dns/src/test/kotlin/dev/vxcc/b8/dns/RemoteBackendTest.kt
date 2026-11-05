package dev.vxcc.b8.dns

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals

/** The PowerDNS remote backend protocol (HTTP connector, `post,post_json`). */
class RemoteBackendTest {
    private val config = DnsConfig.parse(
        """
        zone 168blocks.test
        nameserver ns1 192.0.2.53
        region eu us
        region us eu
        default-region eu
        prefix 198.51.100.0/24 us
        """.trimIndent(),
    )
    private val edges = Edges({
        listOf(
            Edge("edge-1", "eu", InetAddress.getByName("192.0.2.1"), 25565, true),
            Edge("edge-2", "us", InetAddress.getByName("192.0.2.2"), 25565, true),
        )
    }, { 77 }).also { it.refresh() } // serial 78: the first load changed the (empty) snapshot

    private fun call(method: String, parameters: String): JsonElement {
        var result: JsonElement? = null
        testApplication {
            application { dnsModule(config, ZoneAnswers(config, PrefixTable(config.prefixes)), edges) }
            // PowerDNS sends JSON as text/javascript
            val response = client.post("/dns") { setBody("""{"method":"$method","parameters":$parameters}""") }
            assertEquals(HttpStatusCode.OK, response.status)
            result = Json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]
        }
        return result!!
    }

    private fun lookup(qname: String, qtype: String, realRemote: String, remote: String = "10.9.9.9") = call(
        "lookup",
        """{"qtype":"$qtype","qname":"$qname","remote":"$remote","local":"0.0.0.0","real-remote":"$realRemote","zone-id":-1}""",
    ).jsonArray.map { it.jsonObject }

    @Test
    fun `lookup with and without client subnet`() {
        val us = lookup("play.168blocks.test.", "ANY", "198.51.100.0/24")
        assertEquals(listOf("A" to "192.0.2.2", "TXT" to "\"client region us, answering us\""), us.map { it["qtype"]!!.jsonPrimitive.content to it["content"]!!.jsonPrimitive.content })
        assertEquals(24, us[0]["scopeMask"]!!.jsonPrimitive.int)
        assertEquals("play.168blocks.test.", us[0]["qname"]!!.jsonPrimitive.content)
        assertEquals(60, us[0]["ttl"]!!.jsonPrimitive.int)
        // the resolver's own address (no ECS): not in the table, default region
        val plain = lookup("play.168blocks.test.", "A", "10.9.9.9/32")
        assertEquals(listOf("192.0.2.1"), plain.map { it["content"]!!.jsonPrimitive.content })

        val soa = lookup("168blocks.test.", "SOA", "0.0.0.0/0", remote = "0.0.0.0").single()
        assertEquals("ns1.168blocks.test. hostmaster.168blocks.test. 78 3600 600 604800 60", soa["content"]!!.jsonPrimitive.content)
        assertEquals(emptyList(), lookup("other.example.", "SOA", "0.0.0.0/0"))
    }

    @Test
    fun `domain info and unsupported methods`() {
        val info = call("getDomainInfo", """{"name":"168blocks.test."}""").jsonObject
        assertEquals("168blocks.test.", info["zone"]!!.jsonPrimitive.content)
        assertEquals(78, info["serial"]!!.jsonPrimitive.int)
        assertEquals(JsonPrimitive(false), call("getDomainInfo", """{"name":"example.com."}"""))
        assertEquals(1, call("getAllDomains", """{"include_disabled":false}""").jsonArray.size)
        assertEquals(0, call("getDomainMetadata", """{"name":"168blocks.test.","kind":"PRESIGNED"}""").jsonArray.size)
        assertEquals(JsonPrimitive(false), call("getDomainKeys", """{"name":"168blocks.test."}"""))
        assertEquals(JsonPrimitive(false), call("lookup", "{}"))
    }

    @Test
    fun health() = testApplication {
        application { dnsModule(config, ZoneAnswers(config, PrefixTable(config.prefixes)), edges) }
        val r = client.get("/health")
        assertEquals(HttpStatusCode.OK, r.status)
        val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(2, body["edges"]!!.jsonArray.size)
        assertEquals("ok", body["database"]!!.jsonPrimitive.content)
    }
}
