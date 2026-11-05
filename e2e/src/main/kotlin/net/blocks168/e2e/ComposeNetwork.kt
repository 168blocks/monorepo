package net.blocks168.e2e

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The local compose network's containers (deploy/compose.yaml), for tests that look at it from inside:
 * from a container in some network namespace (core's, an edge's, the `wan` network). Needs podman (the
 * dev shell's) and `BLOCKS_COMPOSE_PROJECT`; [fromEnv] is null against another deployment.
 */
class ComposeNetwork(val project: String, val runtimeImage: String, val postgresImage: String) {
    data class Result(val exitCode: Int, val output: String) {
        val ok get() = exitCode == 0
    }

    /** Container of compose service [service] (podman-compose naming) */
    fun container(service: String) = "${project}_${service}_1"

    /** The `wan` network: edges and core's WireGuard port, no tunnel */
    val wan get() = "${project}_wan"

    /** The network namespace of [service], for `podman run --network` */
    fun namespaceOf(service: String) = "container:${container(service)}"

    fun exec(service: String, vararg command: String, timeout: Duration = 60.seconds) =
        podman(listOf("exec", container(service)) + command, timeout)

    /** Runs [command] in a throwaway container of [image] in [network] (a network name or [namespaceOf]). */
    fun run(network: String, image: String, vararg command: String, entrypoint: String? = null, timeout: Duration = 60.seconds) =
        podman(
            listOf("run", "--rm", "--network", network) +
                // podman-compose puts the services in a pod; joining one's namespace needs the same pod
                podOf(network) +
                (entrypoint?.let { listOf("--entrypoint", it) } ?: emptyList()) +
                image + command,
            timeout,
        )

    private fun podOf(network: String): List<String> {
        if (!network.startsWith("container:")) return emptyList()
        val pod = podman(listOf("inspect", "--format", "{{.Pod}}", network.removePrefix("container:")), 30.seconds)
        check(pod.ok) { pod.output }
        return if (pod.output.isEmpty()) emptyList() else listOf("--pod", pod.output)
    }

    /** Whether a TCP connection to [host]:[port] succeeds from a container in [network] */
    fun canConnect(network: String, host: String, port: Int): Boolean =
        run(network, runtimeImage, "bash", "-c", "timeout 3 bash -c '</dev/tcp/$host/$port'").ok

    fun podman(args: List<String>, timeout: Duration): Result {
        val out = File.createTempFile("podman", ".log")
        try {
            val process = ProcessBuilder(listOf("podman") + args)
                .redirectErrorStream(true)
                .redirectOutput(out)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
            if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                error("podman ${args.joinToString(" ")}: timed out after $timeout")
            }
            return Result(process.exitValue(), out.readText().trim())
        } finally {
            out.delete()
        }
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ComposeNetwork? {
            val project = env["BLOCKS_COMPOSE_PROJECT"] ?: return null
            return ComposeNetwork(
                project,
                env["BLOCKS_RUNTIME_IMAGE"] ?: "localhost/168blocks/runtime:latest",
                env["BLOCKS_POSTGRES_IMAGE"] ?: "localhost/168blocks/postgres:latest",
            )
        }
    }
}
