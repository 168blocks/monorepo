package dev.vxcc.b8.e2e.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import dev.vxcc.b8.e2e.Network
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * A running real game client (see [GameClients]), driven over the RPC of `mods/e2e-client`
 * (protocol: `RpcHandler` there). Every call blocks until the client answered.
 */
class GameClient internal constructor(
    val name: String,
    val runDir: Path,
    private val process: Process,
    private val compositor: HeadlessSway,
) : AutoCloseable {
    private var socket: Socket? = null
    private lateinit var reader: BufferedReader
    private lateinit var writer: OutputStreamWriter
    private var nextId = 1
    private var screenshots = 0

    class RpcException(message: String) : RuntimeException(message)

    data class Position(val x: Double, val y: Double, val z: Double, val yaw: Double, val pitch: Double)

    internal fun attach(port: Int) {
        val s = Socket(InetAddress.getLoopbackAddress(), port)
        socket = s
        reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
        writer = OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)
    }

    /** Sends one request; returns its result or throws [RpcException] with the client's error. */
    @Synchronized
    fun call(op: String, params: JsonObject = JsonObject(emptyMap())): JsonElement {
        val id = nextId++
        val request = JsonObject(params + mapOf("id" to JsonPrimitive(id), "op" to JsonPrimitive(op)))
        writer.write(Json.encodeToString(JsonObject.serializer(), request))
        writer.write("\n")
        writer.flush()
        val line = reader.readLine() ?: throw RpcException("$name: connection closed (client exited?)\n${logTail()}")
        val response = Json.parseToJsonElement(line).jsonObject
        if (response["ok"]?.jsonPrimitive?.boolean != true) {
            throw RpcException("$name: $op failed: ${response["error"]?.jsonPrimitive?.contentOrNull}")
        }
        return response["result"] ?: JsonPrimitive(null as String?)
    }

    fun status(): JsonObject = call("status").jsonObject

    fun connect(endpoint: Network.Endpoint) = apply {
        call("connect", buildJsonObject { put("host", endpoint.host); put("port", endpoint.port) })
    }

    fun disconnect() = apply { call("disconnect") }

    /**
     * [connect]s and [awaitWorld]s. When the client gets disconnected for a reason that [retryOn] matches
     * (by default the proxy's "try again later" while a server is still starting), it retries until
     * [timeoutMs] is over.
     */
    fun join(
        endpoint: Network.Endpoint,
        dimension: String? = null,
        timeoutMs: Long = 180_000,
        retryOn: Regex = Regex("try again later", RegexOption.IGNORE_CASE),
    ): JsonObject {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val before = joins()
            connect(endpoint)
            try {
                return awaitWorld(dimension, afterJoins = before, timeoutMs = (end - System.currentTimeMillis()).coerceAtLeast(1000))
            } catch (e: RpcException) {
                val msg = e.message ?: ""
                if ("disconnected" !in msg || !retryOn.containsMatchIn(msg) || System.currentTimeMillis() > end) throw e
                disconnect()
                Thread.sleep(3000)
            }
        }
    }

    /**
     * Waits until the client is in a world (optionally [dimension], e.g. `minecraft:overworld`) with its
     * player, no screen open and (with [rendered]) the world around it on screen (see [awaitRendered]).
     */
    fun awaitWorld(dimension: String? = null, rendered: Boolean = true, afterJoins: Int = 0, timeoutMs: Long = 120_000): JsonObject =
        call("awaitWorld", buildJsonObject {
            dimension?.let { put("dimension", it) }
            put("afterJoins", afterJoins)
            put("rendered", rendered)
            put("timeoutMs", timeoutMs)
        }).jsonObject

    /**
     * Waits until the world around the player is on screen: the chunks within [radius] (default: one
     * less than the render distance) loaded, their visible sections compiled and faded in, at least
     * [minSections] sections with geometry, and all that unchanged for [frames] rendered frames. Call it
     * after anything that changes the view ([look], moving) and before a [screenshot]. Returns the
     * client's render statistics.
     */
    fun awaitRendered(radius: Int? = null, minSections: Int = 1, frames: Int = 5, timeoutMs: Long = 60_000): JsonObject =
        call("awaitRendered", buildJsonObject {
            radius?.let { put("radius", it) }
            put("minSections", minSections)
            put("frames", frames)
            put("timeoutMs", timeoutMs)
        }).jsonObject

    /**
     * [awaitRendered], then a [screenshot] that is not blank. A blank one is retried (waiting for the
     * render again) until [timeoutMs] is over; the failure names every attempt with the client's render
     * statistics, so a flaky blank frame is diagnosable from the test report alone.
     */
    fun renderedScreenshot(label: String, minSections: Int = 1, timeoutMs: Long = 60_000): Screenshot {
        val end = System.currentTimeMillis() + timeoutMs
        val attempts = mutableListOf<String>()
        var attempt = 0
        while (true) {
            val render = awaitRendered(minSections = minSections, timeoutMs = (end - System.currentTimeMillis()).coerceAtLeast(1000))
            val shot = screenshot(if (attempt == 0) label else "$label-retry$attempt")
            if (!shot.isBlank()) return shot
            attempts += "${shot.file}: ${shot.describe()}; render $render"
            attempt++
            if (System.currentTimeMillis() > end) {
                throw AssertionError("$name: screenshot '$label' stayed blank (${attempts.size} attempts):\n" +
                    attempts.joinToString("\n") + "\nstatus: ${status()}")
            }
            Thread.sleep(500)
        }
    }

    /** Waits until another entity named [entityName] (e.g. another player) is in the client's world. */
    fun awaitEntity(entityName: String, timeoutMs: Long = 60_000): Position =
        position(call("awaitEntity", buildJsonObject { put("name", entityName); put("timeoutMs", timeoutMs) }).jsonObject)

    /** Waits for a chat or system message containing [text] (numbered from [since]); returns its index. */
    fun awaitChat(text: String, since: Int = 0, timeoutMs: Long = 60_000): Int =
        call("awaitChat", buildJsonObject { put("contains", text); put("since", since); put("timeoutMs", timeoutMs) })
            .jsonObject["index"]!!.jsonPrimitive.int

    /** How often the client entered a world so far (first join, then each proxy server switch). */
    fun joins(): Int = status()["joins"]!!.jsonPrimitive.int

    /** Switches to another backend server through Velocity's `/server` and waits for its world. */
    fun switchServer(server: String, dimension: String? = null, timeoutMs: Long = 120_000): JsonObject {
        val before = joins()
        command("server $server")
        return awaitWorld(dimension, afterJoins = before, timeoutMs = timeoutMs)
    }

    fun chat(text: String) = apply { call("chat", buildJsonObject { put("text", text) }) }

    fun command(command: String) = apply { call("command", buildJsonObject { put("command", command) }) }

    fun look(yaw: Double, pitch: Double) = apply { call("look", buildJsonObject { put("yaw", yaw); put("pitch", pitch) }) }

    /**
     * Holds the keys [keys] (forward, back, left, right, jump, sneak, sprint, attack, use, or a key mapping
     * name like `key.inventory`) for [ms] milliseconds, then releases them.
     */
    fun hold(vararg keys: String, ms: Long) = apply {
        call("input", buildJsonObject {
            putJsonObject("keys") { keys.forEach { put(it, true) } }
            put("ms", ms)
        })
    }

    /** Shows or hides the HUD (like F1), e.g. for screenshots that only show the world. */
    fun hud(visible: Boolean) = apply { call("hud", buildJsonObject { put("hidden", !visible) }) }

    fun position(): Position = position(status()["player"]?.jsonObject ?: throw RpcException("$name: no player"))

    /** Takes a screenshot into the run directory (`screenshots/<label>.png`). */
    fun screenshot(label: String = "shot-${++screenshots}"): Screenshot {
        val file = runDir.resolve("screenshots").resolve("$label.png").toAbsolutePath()
        call("screenshot", buildJsonObject { put("path", file.toString()) })
        return Screenshot.read(file)
    }

    /** Last lines of the client's log, for failure messages. */
    fun logTail(lines: Int = 40): String {
        val log = runDir.resolve("client.log")
        return if (log.exists()) log.readLines().takeLast(lines).joinToString("\n") else "(no log)"
    }

    /** Asks the game to quit, then stops the process and its compositor. */
    override fun close() {
        if (socket != null && process.isAlive) runCatching { call("quit") }
        runCatching { socket?.close() }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        }
        compositor.close()
    }

    private fun position(o: JsonObject) = Position(
        o["x"]!!.jsonPrimitive.double, o["y"]!!.jsonPrimitive.double, o["z"]!!.jsonPrimitive.double,
        o["yaw"]!!.jsonPrimitive.double, o["pitch"]!!.jsonPrimitive.double,
    )
}
