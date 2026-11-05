package net.blocks168.e2e.scenario

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.blocks168.e2e.Network
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.TestAbortedException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The scenario runner itself, without a network. */
@Tag("unit")
@ExtendWith(ScenarioExtension::class)
class ScenarioUnitTest {
    private val nowhere = Network(listOf(Network.Endpoint("127.0.0.1", 1)), "http://127.0.0.1:1")

    @Test
    fun uniqueNamesAreValidPlayerNames() {
        val names = (1..2000).map { ScenarioScope.uniqueName("alice-the-great") }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it.length <= 16 && it.matches(Regex("[A-Za-z0-9_]+")) }, names.first())
    }

    @Test
    fun skippedWithoutTheMinecraftServers() {
        assertFailsWith<TestAbortedException> { scenario(env = emptyMap(), network = nowhere) { error("must not run") } }
    }

    @Test
    fun runsPlayersInParallel() {
        val order = mutableListOf<String>()
        scenario(needsServers = false, network = nowhere, env = emptyMap()) {
            launch { delay(100.milliseconds); synchronized(order) { order += "slow" } }
            launch { synchronized(order) { order += "fast" } }
        }
        assertEquals(listOf("fast", "slow"), order)
    }

    @Test
    fun failureWritesDiagnosticsAndClosesDrivers(@TempDir dir: Path) {
        val closed = AtomicBoolean(false)
        val e = assertFailsWith<AssertionError> {
            scenario(needsServers = false, network = nowhere, env = mapOf("BLOCKS_E2E_DIAGNOSTICS" to dir.toString())) {
                register(object : ScenarioDriver {
                    override val name = "fake"
                    override suspend fun diagnostics(out: Diagnostics) = out.text("fake.log", "fake state")
                    override fun close() = closed.set(true)
                })
                log("step one")
                throw AssertionError("boom")
            }
        }
        assertEquals("boom", e.message)
        assertTrue(closed.get(), "driver not closed")
        val bundle = dir.listDirectoryEntries().single()
        val files = bundle.listDirectoryEntries().map { it.name }.toSet()
        assertTrue(files.containsAll(listOf("failure.txt", "timeline.log", "fake.log")), "$files")
        assertContains(bundle.resolve("timeline.log").readText(), "step one")
        assertContains(bundle.resolve("fake.log").readText(), "fake state")
    }

    @Test
    fun timeoutFailsTheScenario(@TempDir dir: Path) {
        assertFailsWith<Throwable> {
            scenario(timeout = 1.seconds, needsServers = false, network = nowhere, env = mapOf("BLOCKS_E2E_DIAGNOSTICS" to dir.toString())) {
                delay(10.seconds)
            }
        }
        assertEquals(1, dir.listDirectoryEntries().size, "no diagnostics for the timeout")
    }

    @Test
    fun botConnectFailureIsReportedWithItsLog(@TempDir dir: Path) {
        assertFailsWith<Exception> {
            scenario(needsServers = false, network = nowhere, env = mapOf("BLOCKS_E2E_DIAGNOSTICS" to dir.toString())) {
                bot("alice").connect(edge(1), retryFor = 0.seconds)
            }
        }
        val files = dir.listDirectoryEntries().single().listDirectoryEntries().map { it.name }
        assertTrue(files.any { it.startsWith("bot-alice_") }, "$files")
    }
}
