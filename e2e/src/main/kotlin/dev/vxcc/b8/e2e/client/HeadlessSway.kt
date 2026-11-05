package dev.vxcc.b8.e2e.client

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A private headless sway session for one game client: `WLR_BACKENDS=headless` (no window on any
 * display), no input devices, software rendering (pixman), its own `XDG_RUNTIME_DIR` (a short temporary
 * directory, as Wayland socket paths are length-limited), so it never touches the user's session.
 */
class HeadlessSway private constructor(
    private val process: Process,
    private val runtimeDir: Path,
    /** what a client needs to connect to this compositor */
    val clientEnvironment: Map<String, String>,
) : AutoCloseable {
    override fun close() {
        process.destroy()
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        runtimeDir.toFile().deleteRecursively()
    }

    companion object {
        fun start(sway: String, clientEnv: String, logDir: Path, width: Int, height: Int, timeoutSeconds: Long = 30): HeadlessSway {
            logDir.createDirectories()
            val runtimeDir = Files.createTempDirectory(
                "blocks168-sway-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
            val config = logDir.resolve("sway.conf")
            config.writeText(
                """
                output HEADLESS-1 resolution ${width}x$height position 0 0
                default_border none
                xwayland disable
                """.trimIndent() + "\n",
            )
            val process = ProcessBuilder(clientEnv, sway, "--unsupported-gpu", "-c", config.toString())
                .redirectErrorStream(true)
                .redirectOutput(logDir.resolve("sway.log").toFile())
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
                .apply {
                    val env = environment()
                    for (k in listOf("WAYLAND_DISPLAY", "WAYLAND_SOCKET", "DISPLAY", "SWAYSOCK", "I3SOCK")) env.remove(k)
                    env["XDG_RUNTIME_DIR"] = runtimeDir.toString()
                    env["WLR_BACKENDS"] = "headless"
                    env["WLR_LIBINPUT_NO_DEVICES"] = "1"
                    env["WLR_RENDERER"] = "pixman"
                }
                .start()
            // the first free wayland-N in the fresh runtime directory
            fun socket() = runtimeDir.toFile().list()?.firstOrNull { Regex("wayland-\\d+").matches(it) }
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (socket() == null) {
                if (!process.isAlive || System.nanoTime() > end) {
                    process.destroyForcibly()
                    runtimeDir.toFile().deleteRecursively()
                    error("headless sway did not start; see ${logDir.resolve("sway.log")}")
                }
                Thread.sleep(100)
            }
            return HeadlessSway(
                process,
                runtimeDir,
                mapOf("XDG_RUNTIME_DIR" to runtimeDir.toString(), "WAYLAND_DISPLAY" to socket()!!),
            )
        }
    }
}
