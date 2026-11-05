package dev.vxcc.b8.e2e.scenario

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

/**
 * Where a failed scenario writes what helps to understand it. Each entry becomes one file of the
 * bundle (and one attachment of the JUnit report).
 */
class Diagnostics internal constructor(val dir: Path) {
    fun text(name: String, content: String) {
        dir.resolve(safe(name)).writeText(content)
    }

    fun file(name: String, source: Path) {
        Files.copy(source, dir.resolve(safe(name)))
    }

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    companion object {
        /**
         * Writes the bundle of a failed scenario: timeline, each driver's diagnostics (bot logs, ...),
         * agent snapshots and the logs of the network's containers. Returns its directory.
         */
        internal suspend fun collect(s: ScenarioScope, failure: Throwable, test: ExtensionContext?): Path = withContext(Dispatchers.IO) {
            val root = Path.of(s.env["BLOCKS_E2E_DIAGNOSTICS"] ?: "build/scenario-diagnostics")
            val testName = test?.let { t -> t.testClass.map { it.simpleName + "." }.orElse("") + t.testMethod.map { it.name }.orElse(t.displayName) }
            val dir = root.resolve(dirName(testName ?: s.title) + "-" + System.currentTimeMillis()).toAbsolutePath().createDirectories()
            val out = Diagnostics(dir)
            out.text("failure.txt", failure.stackTraceToString())
            out.text("timeline.log", s.timeline().joinToString("\n"))
            for (d in s.drivers()) {
                runCatching { withTimeoutOrNull(10.seconds) { d.diagnostics(out) } }
                    .onFailure { out.text("driver-${d.name}-error.txt", it.stackTraceToString()) }
            }
            agentSnapshots(s, out)
            containerLogs(s.env, out)
            test?.publishDirectory("scenario-diagnostics") { target ->
                Files.list(dir).use { files -> files.forEach { Files.copy(it, target.resolve(it.fileName)) } }
            }
            dir
        }

        private fun dirName(id: String) = id.replace(Regex("[^A-Za-z0-9._-]+"), "_").takeLast(120)

        private suspend fun agentSnapshots(s: ScenarioScope, out: Diagnostics) {
            for ((name, agent) in s.serverAgents()) {
                val snapshot = runCatching {
                    withTimeoutOrNull(5.seconds) { "status: ${agent.status()}\nworlds: ${agent.worlds()}\nplayers:\n" + agent.players().joinToString("\n") }
                }.getOrElse { "agent unreachable: $it" }
                out.text("agent-$name.txt", snapshot ?: "agent timed out")
            }
            s.proxyAgent()?.let { proxy ->
                val snapshot = runCatching {
                    withTimeoutOrNull(5.seconds) { "status: ${proxy.status()}\nservers: ${proxy.servers()}\nplayers:\n" + proxy.players().joinToString("\n") }
                }.getOrElse { "agent unreachable: $it" }
                out.text("agent-velocity.txt", snapshot ?: "agent timed out")
            }
        }

        /**
         * `podman logs` (tail) of every container of this checkout's compose project
         * (`BLOCKS_COMPOSE_PROJECT`, tools/instance.sh). All of them, not a guess at the involved
         * ones: the topology changes (edges, servers) and there are only a handful.
         */
        private fun containerLogs(env: Map<String, String>, out: Diagnostics) {
            val project = env["BLOCKS_COMPOSE_PROJECT"] ?: return
            val names = exec("podman", "ps", "-a", "--filter", "label=io.podman.compose.project=$project", "--format", "{{.Names}}")
                ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: run { out.text("containers.txt", "podman not available"); return }
            for (name in names) {
                out.text("container-$name.log", exec("podman", "logs", "--tail", "300", name) ?: "podman logs failed")
            }
        }

        private fun exec(vararg cmd: String): String? = runCatching {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null"))).start()
            val text = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            if (p.exitValue() != 0) "exit ${p.exitValue()}: $text" else text
        }.getOrNull()
    }
}

/**
 * Makes the current test's JUnit context available to [scenario], which publishes a failed
 * scenario's diagnostics through it (they show up in the Gradle test report as attachments).
 * Applied by [NetworkScenario].
 */
class ScenarioExtension : BeforeEachCallback, AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) = current.set(context)
    override fun afterEach(context: ExtensionContext) = current.remove()

    companion object {
        private val current = ThreadLocal<ExtensionContext>()
        internal fun current(): ExtensionContext? = current.get()
    }
}
