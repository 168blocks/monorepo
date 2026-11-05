package net.blocks168.pgecs.testing

import net.blocks168.pgecs.PgEcsConfig
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A throwaway PostgreSQL server for tests, from `initdb`/`postgres` on the PATH (the Nix dev shell
 * provides them, with pg_jsonschema, see flake.nix). One server per test JVM, started on first use and
 * stopped when the JVM exits.
 *
 * Set `PGECS_TEST_URL` (with `PGECS_TEST_USER`, `PGECS_TEST_PASSWORD`) to use an existing server
 * instead.
 */
object TestPostgres {
    const val USER = "pgecs"
    const val DATABASE = "postgres"

    val config: PgEcsConfig by lazy { System.getenv("PGECS_TEST_URL")?.let(::external) ?: start() }

    private fun external(url: String) = PgEcsConfig(
        jdbcUrl = url,
        user = System.getenv("PGECS_TEST_USER") ?: USER,
        password = System.getenv("PGECS_TEST_PASSWORD") ?: "",
    )

    private fun start(): PgEcsConfig {
        val dir = Files.createTempDirectory("pgecs-test-")
        val data = dir.resolve("data")
        val port = ServerSocket(0).use { it.localPort }

        run(dir, initdbWrapper() + listOf("initdb", "-D", "$data", "-U", USER, "--auth=trust", "--no-sync", "-E", "UTF8", "--no-locale"))

        // started directly (not through pg_ctl, which also needs /bin/sh), stopped with the JVM
        val server = ProcessBuilder(
            "postgres", "-D", "$data", "-p", "$port", "-k", "$dir",
            "-c", "listen_addresses=127.0.0.1", "-c", "fsync=off", "-c", "full_page_writes=off",
        ).redirectErrorStream(true).redirectOutput(dir.resolve("log").toFile()).start()
        Runtime.getRuntime().addShutdownHook(Thread {
            server.destroy()
            server.waitFor(10, TimeUnit.SECONDS)
            dir.toFile().deleteRecursively()
        })
        awaitPort(port, server, dir)
        return PgEcsConfig("jdbc:postgresql://127.0.0.1:$port/$DATABASE", USER, "")
    }

    /**
     * initdb runs helpers through `/bin/sh -c -- ...` (glibc's popen). Some hosts' /bin/sh rejects the
     * `--`; there, run initdb under proot with Nix's bash bound to /bin/sh.
     */
    private fun initdbWrapper(): List<String> {
        val ok = runCatching {
            ProcessBuilder("/bin/sh", "-c", "--", "true").redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)
        if (ok) return emptyList()
        val bash = System.getenv("BLOCKS_BASH") ?: error("/bin/sh is unusable and BLOCKS_BASH is not set; use the Nix dev shell")
        return listOf("proot", "-b", "$bash:/bin/sh")
    }

    private fun awaitPort(port: Int, server: Process, dir: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            check(server.isAlive) { "postgres exited:\n" + dir.resolve("log").toFile().readText() }
            if (runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess) return
            Thread.sleep(100)
        }
        error("postgres did not start:\n" + dir.resolve("log").toFile().readText())
    }

    private fun run(dir: Path, command: List<String>) {
        val process = try {
            ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            throw IllegalStateException(
                "cannot run ${command[0]}: run the tests inside the Nix dev shell (./dev ./gradlew test)", e,
            )
        }
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) {
            "${command.joinToString(" ")} failed:\n$output"
        }
    }
}
