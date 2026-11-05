package dev.vxcc.b8.backend.matrix

import io.ktor.http.Url
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A throwaway Matrix homeserver for tests: tuwunel (`BLOCKS_MATRIX_TEST_HOMESERVER`, from the Nix dev
 * shell) on a free port with a temporary database, server name `localhost`, no federation. The given
 * users are created at startup through its admin console, all with [PASSWORD]. Close it to stop it.
 */
class TestHomeserver(vararg users: String) : AutoCloseable {
    private val dir: Path = Files.createTempDirectory("matrix-test-")
    private val port = ServerSocket(0).use { it.localPort }
    private val process: Process
    val baseUrl = Url("http://127.0.0.1:$port")

    init {
        val binary = System.getenv("BLOCKS_MATRIX_TEST_HOMESERVER")?.takeIf { it.isNotBlank() }
            ?: error("BLOCKS_MATRIX_TEST_HOMESERVER is not set: run the tests inside the Nix dev shell (./dev ./gradlew test)")
        val command = mutableListOf(
            binary,
            "-O", "server_name=\"$SERVER_NAME\"",
            "-O", "database_path=\"$dir/db\"",
            "-O", "address=[\"127.0.0.1\"]",
            "-O", "port=[$port]",
            "-O", "allow_federation=false",
            "-O", "allow_registration=false",
        )
        users.forEach { command += listOf("--execute", "users create-user $it $PASSWORD") }
        process = ProcessBuilder(command).directory(dir.toFile())
            .redirectErrorStream(true).redirectOutput(dir.resolve("log").toFile())
            .start()
        awaitReady(users.toList())
    }

    fun userId(localpart: String) = "@$localpart:$SERVER_NAME"

    private fun awaitReady(users: List<String>) {
        val http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            check(process.isAlive) { "homeserver exited:\n${log()}" }
            // ready once every user can log in (the admin commands that create them have run)
            if (users.all { canLogIn(http, it) }) return
            Thread.sleep(200)
        }
        error("homeserver did not start:\n${log()}")
    }

    private fun canLogIn(http: HttpClient, user: String): Boolean = runCatching {
        val body = """{"type":"m.login.password","identifier":{"type":"m.id.user","user":"$user"},"password":"$PASSWORD"}"""
        val request = HttpRequest.newBuilder(URI("$baseUrl/_matrix/client/v3/login"))
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    }.getOrDefault(false)

    private fun log(): String = runCatching { Files.readString(dir.resolve("log")) }.getOrDefault("")

    override fun close() {
        process.destroy()
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
        dir.toFile().deleteRecursively()
    }

    companion object {
        const val SERVER_NAME = "localhost"
        const val PASSWORD = "test-password-1"
    }
}
