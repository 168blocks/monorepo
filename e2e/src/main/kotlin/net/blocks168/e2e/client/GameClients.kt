package net.blocks168.e2e.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Starts real Minecraft clients (Fabric dev client with `mods/e2e-client`) for end-to-end tests, each
 * under its own headless sway (no window on the desktop, no input devices, Mesa software rendering),
 * with its own run directory and username. Nothing listens on fixed ports: the client's RPC binds a
 * free port and reports it through a file in its run directory.
 *
 * Needs the dev shell (`BLOCKS_CLIENT_ENV`, `BLOCKS_SWAY` from flake.nix) and the launch description
 * from `gradlew :mods:e2e-client:clientLaunch` (`BLOCKS_E2E_CLIENT_LAUNCH`; the e2e test task sets it).
 *
 * ```
 * GameClients.fromEnv().use { clients ->
 *     val bob = clients.start("bob")
 *     bob.connect(network.edge(1))
 *     bob.awaitWorld()
 *     bob.screenshot()
 * }
 * ```
 */
class GameClients(
    private val launch: LaunchSpec,
    /** `blocks-client-env` (flake.nix): runs a command with the client libraries and Mesa */
    private val clientEnv: String,
    private val sway: String,
    /** parent of the clients' run directories */
    val baseDir: Path,
    private val width: Int = 854,
    private val height: Int = 480,
) : AutoCloseable {
    private val clients = mutableListOf<GameClient>()

    /** Launch description written by `:mods:e2e-client:clientLaunch` */
    @Serializable
    data class LaunchSpec(
        val java: String,
        val jvmArgs: List<String>,
        val classpath: List<String>,
        val mainClass: String,
        val args: List<String>,
    )

    /**
     * Starts a client logged in (offline mode) as [name] and waits until its RPC answers (the title
     * screen is up). Run directory: `<baseDir>/<name>` (kept between runs; logs and screenshots end
     * up there).
     */
    fun start(name: String, startupTimeoutSeconds: Long = 300): GameClient {
        require(Regex("[A-Za-z0-9_]{3,16}").matches(name)) { "not a valid Minecraft username: $name" }
        val runDir = baseDir.resolve(name).toAbsolutePath().also { it.createDirectories() }
        writeOptions(runDir.resolve("options.txt"))
        val portFile = runDir.resolve("e2e-rpc.port").also { it.deleteIfExists() }

        val compositor = HeadlessSway.start(sway, clientEnv, runDir.resolve("sway"), width, height)
        val process = try {
            val command = buildList {
                add(clientEnv)
                add(launch.java)
                add("-Xmx2G")
                addAll(launch.jvmArgs)
                add("-cp")
                add(launch.classpath.joinToString(java.io.File.pathSeparator))
                add(launch.mainClass)
                addAll(launch.args)
                addAll(listOf("--username", name, "--width", "$width", "--height", "$height"))
            }
            ProcessBuilder(command).directory(runDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(runDir.resolve("client.log").toFile())
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
                .apply {
                    val env = environment()
                    for (k in listOf("DISPLAY", "WAYLAND_SOCKET", "SWAYSOCK", "LD_LIBRARY_PATH", "LD_PRELOAD")) env.remove(k)
                    env.putAll(compositor.clientEnvironment)
                    env["SDL_VIDEO_DRIVER"] = "wayland"
                    env["ALSOFT_DRIVERS"] = "null" // no sound server
                    env["BLOCKS_E2E_RPC_PORT_FILE"] = portFile.toString()
                }
                .start()
        } catch (e: Exception) {
            compositor.close()
            throw e
        }

        val client = GameClient(name, runDir, process, compositor)
        try {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(startupTimeoutSeconds)
            while (!portFile.exists()) {
                check(process.isAlive) { "client $name exited (${process.exitValue()}) during startup; log: ${runDir.resolve("client.log")}\n${client.logTail()}" }
                check(System.nanoTime() < end) { "client $name did not start its RPC within ${startupTimeoutSeconds}s; log: ${runDir.resolve("client.log")}\n${client.logTail()}" }
                Thread.sleep(250)
            }
            client.attach(portFile.readText().trim().toInt())
            // the RPC starts early; wait until resources are loaded and the title screen is up
            client.call("awaitReady", buildJsonObject { put("timeoutMs", TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()).coerceAtLeast(1000)) })
        } catch (e: Throwable) {
            client.close()
            throw e
        }
        synchronized(clients) { clients += client }
        return client
    }

    /** Starts [names] in parallel. */
    fun startAll(vararg names: String): List<GameClient> =
        names.map { n -> java.util.concurrent.CompletableFuture.supplyAsync { start(n) } }.map { it.join() }

    override fun close() {
        val all = synchronized(clients) { clients.toList().also { clients.clear() } }
        all.forEach { runCatching { it.close() } }
    }

    /** Headless-friendly settings: no narrator, onboarding, sound, pause on focus loss; small and steady. */
    private fun writeOptions(file: Path) {
        val defaults = linkedMapOf(
            "narrator" to "0",
            "onboardAccessibility" to "false",
            "tutorialStep" to "none",
            "skipMultiplayerWarning" to "true",
            "joinedFirstServer" to "true",
            "pauseOnLostFocus" to "false",
            "soundCategory_master" to "0.0",
            "enableVsync" to "false",
            "maxFps" to "30",
            "inactivityFpsLimit" to "\"afk\"",
            "preferredGraphicsBackend" to "\"opengl\"",
            "renderDistance" to "4",
            "simulationDistance" to "5",
            "renderClouds" to "\"false\"",
            "fullscreen" to "false",
        )
        val existing = if (file.exists()) file.readText().lines().filter { it.isNotBlank() } else emptyList()
        val kept = existing.filter { line -> line.substringBefore(':') !in defaults }
        file.writeText((kept + defaults.map { (k, v) -> "$k:$v" }).joinToString("\n", postfix = "\n"))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Whether the environment can start clients (dev shell + launch description). */
        fun available(env: Map<String, String> = System.getenv()): Boolean =
            listOf("BLOCKS_CLIENT_ENV", "BLOCKS_SWAY", "BLOCKS_E2E_CLIENT_LAUNCH").all { !env[it].isNullOrEmpty() } &&
                Files.exists(Path.of(env["BLOCKS_E2E_CLIENT_LAUNCH"]!!))

        fun fromEnv(env: Map<String, String> = System.getenv()): GameClients {
            fun need(k: String) = env[k]?.takeIf { it.isNotEmpty() } ?: error("$k is not set: run through the dev shell and the e2e test task (tools/network.sh test)")
            val launch = json.decodeFromString(LaunchSpec.serializer(), Path.of(need("BLOCKS_E2E_CLIENT_LAUNCH")).readText())
            return GameClients(
                launch = launch,
                clientEnv = need("BLOCKS_CLIENT_ENV"),
                sway = need("BLOCKS_SWAY"),
                baseDir = Path.of(env["BLOCKS_E2E_CLIENT_DIR"] ?: "build/e2e-clients"),
            )
        }
    }
}
