package net.blocks168.dns

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/**
 * The current [Snapshot] of the edges, refreshed from [EdgeStore] by [refresh]. If the database cannot
 * be read, the last snapshot keeps being served (DNS keeps answering through a database outage; edges
 * then do not change health until it is back). The SOA serial moves whenever the answers could change
 * (an edge appears, disappears or changes health), to the current Unix time or at least one higher.
 */
class Edges(private val load: () -> List<Edge>, private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val log = LoggerFactory.getLogger(Edges::class.java)
    private val current = AtomicReference(Snapshot(emptyList(), clock()))

    @Volatile
    var lastError: String? = null
        private set

    val snapshot: Snapshot get() = current.get()

    fun refresh(): Snapshot {
        val edges = try {
            load().also {
                if (lastError != null) log.info("edges readable again")
                lastError = null
            }
        } catch (e: Exception) {
            if (lastError == null) log.warn("cannot read the edges, serving the last snapshot: {}", e.toString())
            lastError = e.toString()
            return current.get()
        }
        return current.updateAndGet { old ->
            if (old.edges == edges) old else Snapshot(edges, maxOf(old.serial + 1, clock()))
        }
    }

    companion object {
        fun from(store: EdgeStore, stale: Duration) = Edges({ store.load(stale) })
    }
}

/**
 * PowerDNS remote backend, HTTP connector in JSON POST mode
 * (`remote-connection-string=http:url=http://127.0.0.1:8053/dns,post,post_json`): every call is a POST
 * of `{"method": ..., "parameters": {...}}` to `/dns`, answered with `{"result": ...}`. Only what an
 * authoritative, non-DNSSEC zone needs is implemented (`lookup`, `getDomainInfo`, `getAllDomains`,
 * `getDomainMetadata`); everything else answers `false`, which PowerDNS takes as "not supported".
 *
 * `GET /health` reports the snapshot (edges and their health) for operators and tests.
 */
fun Application.dnsModule(config: DnsConfig, answers: ZoneAnswers, edges: Edges) {
    val log = LoggerFactory.getLogger("net.blocks168.dns.RemoteBackend")
    val json = Json { ignoreUnknownKeys = true }
    val zoneName = "${config.zone}."

    fun domainInfo(serial: Long) = buildJsonObject {
        put("id", 1)
        put("zone", zoneName)
        put("kind", "native")
        put("serial", serial)
        put("masters", JsonArray(emptyList()))
        put("notified_serial", serial)
        put("last_check", 0)
    }

    fun handle(method: String, params: JsonObject): JsonElement {
        val snapshot = edges.snapshot
        fun param(name: String) = params[name]?.jsonPrimitive?.contentOrNull
        return when (method) {
            "lookup" -> {
                val qname = param("qname") ?: return JsonPrimitive(false)
                if (!answers.inZone(qname)) return JsonArray(emptyList())
                val client = ClientSubnet.parse(param("real-remote"))?.takeUnless { it.subnet.prefix == 0 }
                    ?: ClientSubnet.parse(param("remote"))?.takeUnless { it.subnet.address.isAnyLocalAddress }
                buildJsonArray {
                    for (r in answers.lookup(qname, param("qtype") ?: "ANY", client, snapshot)) {
                        add(
                            buildJsonObject {
                                put("qname", r.qname)
                                put("qtype", r.qtype)
                                put("content", r.content)
                                put("ttl", r.ttl)
                                put("auth", 1)
                                if (r.scopeMask > 0) put("scopeMask", r.scopeMask)
                            },
                        )
                    }
                }
            }
            "getDomainInfo" -> if (param("name")?.lowercase()?.removeSuffix(".") == config.zone) domainInfo(snapshot.serial) else JsonPrimitive(false)
            "getAllDomains" -> JsonArray(listOf(domainInfo(snapshot.serial)))
            "getDomainMetadata" -> JsonArray(emptyList())
            else -> JsonPrimitive(false)
        }
    }

    routing {
        post("/dns") {
            val result = try {
                val body = json.parseToJsonElement(call.receiveText()).jsonObject
                val method = body["method"]?.jsonPrimitive?.contentOrNull ?: ""
                val params = body["parameters"] as? JsonObject ?: JsonObject(emptyMap())
                handle(method, params)
            } catch (e: Exception) {
                log.warn("bad remote backend request: {}", e.toString())
                JsonPrimitive(false)
            }
            call.respondText(JsonObject(mapOf("result" to result)).toString(), ContentType.Application.Json)
        }
        get("/health") {
            val s = edges.snapshot
            val body = buildJsonObject {
                put("zone", config.zone)
                put("serial", s.serial)
                put("database", edges.lastError?.let { JsonPrimitive(it) } ?: JsonPrimitive("ok"))
                put(
                    "edges",
                    buildJsonArray {
                        for (e in s.edges) {
                            add(
                                buildJsonObject {
                                    put("name", e.name)
                                    put("region", e.region)
                                    put("address", e.address.hostAddress)
                                    put("port", e.port)
                                    put("healthy", e.healthy)
                                },
                            )
                        }
                    },
                )
            }
            val status = if (edges.lastError == null) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respondText(body.toString(), ContentType.Application.Json, status)
        }
    }
}
