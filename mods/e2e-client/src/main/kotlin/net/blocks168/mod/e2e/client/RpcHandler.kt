package net.blocks168.mod.e2e.client

import com.mojang.blaze3d.platform.NativeImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.world.entity.Entity
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The RPC protocol: one JSON object per line in each direction, one response per request, in order.
 *
 *     -> {"id": 1, "op": "connect", "host": "127.0.0.1", "port": 25501}
 *     <- {"id": 1, "ok": true, "result": {...}}            or {"id": 1, "ok": false, "error": "..."}
 *
 * Operations (`timeoutMs` defaults to 60000 for the `await…` ones):
 *
 * | op | parameters | result |
 * | --- | --- | --- |
 * | `status` | | screen, dimension, player, window size, fps, joins (worlds entered so far) |
 * | `awaitReady` | timeoutMs | the game finished loading and shows the title screen |
 * | `connect` | host, port | starts joining the server (title screen → connect screen) |
 * | `disconnect` | | back to the title screen |
 * | `awaitWorld` | dimension?, afterJoins? (0), rendered? (true), timeoutMs | in a world (entered more than `afterJoins` times, e.g. after a server switch) with a player, all visible sections built |
 * | `awaitEntity` | name, timeoutMs | another entity with that name is in the client's world: its position |
 * | `awaitChat` | contains, since? (0), timeoutMs | the first matching chat line at or after `since` |
 * | `chatLog` | since? (0) | lines since `since`, `next` index |
 * | `chat` | text | sends a chat message |
 * | `command` | command | sends a command (without `/`) |
 * | `look` | yaw, pitch | turns the player |
 * | `input` | keys {forward, back, left, right, jump, sneak, sprint, attack, use: bool}, ms? | holds/releases keys; with `ms` releases them after that time |
 * | `hud` | hidden | shows or hides the HUD (F1) |
 * | `screenshot` | path | writes the frame as PNG: path, width, height |
 * | `quit` | | stops the game |
 *
 * Waits fail early when the client ends up on the disconnected screen, with its message.
 */
object RpcHandler {
    private val log = LoggerFactory.getLogger("blocks168-e2e-client")
    private val json = Json { encodeDefaults = true }
    private const val DEFAULT_TIMEOUT_MS = 60_000L

    fun serve(socket: Socket) {
        socket.use {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val response = handle(line)
                writer.write(json.encodeToString(JsonObject.serializer(), response))
                writer.write("\n")
                writer.flush()
            }
        }
    }

    private fun handle(line: String): JsonObject {
        var id: JsonElement = JsonNull
        return try {
            val req = Json.parseToJsonElement(line).jsonObject
            id = req["id"] ?: JsonNull
            val op = req.str("op") ?: throw IllegalArgumentException("missing op")
            val result = dispatch(op, req)
            buildJsonObject {
                put("id", id)
                put("ok", true)
                put("result", result)
            }
        } catch (e: Throwable) {
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            log.warn("e2e RPC request failed: {}", line, cause)
            buildJsonObject {
                put("id", id)
                put("ok", false)
                put("error", cause.toString())
            }
        }
    }

    private fun dispatch(op: String, req: JsonObject): JsonElement = when (op) {
        "status" -> onMain { status(it) }
        "awaitReady" -> await(req) { mc -> if (mc.gui.overlay() == null && mc.gui.screen() is TitleScreen) status(mc) else null }
        "connect" -> onMain { mc ->
            check(mc.level == null) { "already in a world; disconnect first" }
            val address = "${req.str("host") ?: "127.0.0.1"}:${req.int("port") ?: 25565}"
            val server = ServerData("e2e", address, ServerData.Type.OTHER)
            ConnectScreen.startConnecting(TitleScreen(), mc, ServerAddress.parseString(address), server, false, null)
            JsonPrimitive(address)
        }
        "disconnect" -> onMain { mc ->
            if (mc.level != null) mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("e2e disconnect"))
            mc.gui.setScreen(TitleScreen())
            JsonPrimitive("ok")
        }
        "awaitWorld" -> {
            val dimension = req.str("dimension")
            val rendered = req.bool("rendered") ?: true
            val afterJoins = req.int("afterJoins") ?: 0
            await(req) { mc ->
                if (E2eClient.joins.get() <= afterJoins) return@await null
                val level = mc.level ?: return@await null
                val player = mc.player ?: return@await null
                if (mc.gui.screen() != null) return@await null // loading / receiving level screens
                if (dimension != null && level.dimension().identifier().toString() != dimension) return@await null
                if (!level.hasChunkAt(player.blockPosition())) return@await null
                if (rendered && !mc.levelRenderer.hasRenderedAllSections()) return@await null
                status(mc)
            }
        }
        "awaitEntity" -> {
            val name = req.str("name") ?: throw IllegalArgumentException("missing name")
            await(req) { mc ->
                val level = mc.level ?: return@await null
                level.entitiesForRendering().firstOrNull { it !== mc.player && it.name.string == name }?.let(::entity)
            }
        }
        "awaitChat" -> {
            val contains = req.str("contains") ?: throw IllegalArgumentException("missing contains")
            val since = req.int("since") ?: 0
            await(req, failOnDisconnect = false) {
                ChatLog.since(since).first.firstOrNull { contains in it.value }?.let { line ->
                    buildJsonObject {
                        put("index", line.index)
                        put("text", line.value)
                    }
                }
            }
        }
        "chatLog" -> {
            val (lines, next) = ChatLog.since(req.int("since") ?: 0)
            buildJsonObject {
                put("lines", buildJsonArray {
                    for (l in lines) add(buildJsonObject { put("index", l.index); put("text", l.value) })
                })
                put("next", next)
            }
        }
        "chat" -> onMain { mc ->
            connection(mc).sendChat(req.str("text") ?: "")
            JsonPrimitive("ok")
        }
        "command" -> onMain { mc ->
            connection(mc).sendCommand((req.str("command") ?: "").removePrefix("/"))
            JsonPrimitive("ok")
        }
        "look" -> onMain { mc ->
            val player = mc.player ?: error("no player")
            req.double("yaw")?.let { player.yRot = it.toFloat() }
            req.double("pitch")?.let { player.xRot = it.toFloat() }
            entity(player)
        }
        "input" -> {
            val keys = req["keys"]?.jsonObject ?: throw IllegalArgumentException("missing keys")
            val pressed = keys.mapValues { it.value.jsonPrimitive.boolean }
            onMain { mc -> pressed.forEach { (k, down) -> key(mc, k).isDown = down }; JsonNull }
            req.long("ms")?.let { ms ->
                Thread.sleep(ms)
                onMain { mc -> pressed.keys.forEach { key(mc, it).isDown = false }; JsonNull }
            }
            JsonPrimitive("ok")
        }
        "hud" -> onMain { mc ->
            val hidden = req.bool("hidden") ?: true
            if (mc.gui.hud.isHidden != hidden) mc.gui.hud.toggle()
            JsonPrimitive(mc.gui.hud.isHidden)
        }
        "screenshot" -> screenshot(Path.of(req.str("path") ?: throw IllegalArgumentException("missing path")))
        "quit" -> onMain { mc -> mc.stop(); JsonPrimitive("ok") }
        else -> throw IllegalArgumentException("unknown op $op")
    }

    private fun key(mc: Minecraft, name: String): KeyMapping = with(mc.options) {
        when (name) {
            "forward" -> keyUp
            "back" -> keyDown
            "left" -> keyLeft
            "right" -> keyRight
            "jump" -> keyJump
            "sneak" -> keyShift
            "sprint" -> keySprint
            "attack" -> keyAttack
            "use" -> keyUse
            else -> keyMappings.firstOrNull { it.name == name } ?: throw IllegalArgumentException("unknown key $name")
        }
    }

    private fun connection(mc: Minecraft) = mc.connection ?: error("not connected")

    private fun status(mc: Minecraft): JsonObject = buildJsonObject {
        val screen = mc.gui.screen()
        put("screen", screen?.javaClass?.name)
        put("screenTitle", screen?.title?.string)
        put("dimension", mc.level?.dimension()?.identifier()?.toString())
        put("player", mc.player?.let(::entity) ?: JsonNull)
        put("width", mc.window.width)
        put("height", mc.window.height)
        put("fps", mc.fps)
        put("joins", E2eClient.joins.get())
    }

    private fun entity(e: Entity): JsonObject = buildJsonObject {
        put("name", e.name.string)
        put("type", e.type.toString())
        put("x", e.x)
        put("y", e.y)
        put("z", e.z)
        put("yaw", e.yRot)
        put("pitch", e.xRot)
    }

    private fun screenshot(path: Path): JsonElement {
        val done = CompletableFuture<JsonElement>()
        Minecraft.getInstance().execute {
            try {
                Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget()) { image: NativeImage ->
                    try {
                        image.use {
                            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
                            it.writeToFile(path)
                            done.complete(buildJsonObject {
                                put("path", path.toAbsolutePath().toString())
                                put("width", it.width)
                                put("height", it.height)
                            })
                        }
                    } catch (t: Throwable) {
                        done.completeExceptionally(t)
                    }
                }
            } catch (t: Throwable) {
                done.completeExceptionally(t)
            }
        }
        return done.get(30, TimeUnit.SECONDS)
    }

    /** Polls [check] on the render thread until it returns a value. */
    private fun await(req: JsonObject, failOnDisconnect: Boolean = true, check: (Minecraft) -> JsonElement?): JsonElement {
        val timeout = req.long("timeoutMs") ?: DEFAULT_TIMEOUT_MS
        val end = System.nanoTime() + timeout * 1_000_000
        var last: JsonObject
        while (true) {
            val (result, state) = onMain { mc ->
                val screen = mc.gui.screen()
                if (failOnDisconnect && screen is DisconnectedScreen) {
                    error("disconnected: ${screen.narrationMessage.string}")
                }
                check(mc) to status(mc)
            }
            if (result != null) return result
            last = state
            if (System.nanoTime() > end) throw IllegalStateException("timed out after ${timeout}ms; client state: $last")
            Thread.sleep(50)
        }
    }

    /** Runs on the render thread and waits for the result. */
    private fun <T> onMain(block: (Minecraft) -> T): T {
        val mc = Minecraft.getInstance()
        val future = CompletableFuture<T>()
        mc.execute {
            try {
                future.complete(block(mc))
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future.get(30, TimeUnit.SECONDS)
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.let { this[key]!!.jsonPrimitive.int }
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.let { this[key]!!.jsonPrimitive.long }
    private fun JsonObject.double(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.let { this[key]!!.jsonPrimitive.double }
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.let { this[key]!!.jsonPrimitive.boolean }
}
