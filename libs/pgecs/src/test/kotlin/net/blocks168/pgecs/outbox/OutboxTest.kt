package net.blocks168.pgecs.outbox

import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import net.blocks168.pgecs.Capability
import net.blocks168.pgecs.Ecs
import net.blocks168.pgecs.EntityId
import net.blocks168.pgecs.Pgecs
import net.blocks168.pgecs.db.Database
import net.blocks168.pgecs.query.QueryEngineConfig
import net.blocks168.pgecs.serial.MinLength
import net.blocks168.pgecs.testing.TestPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

@Serializable
data class Mail(@MinLength(3) val to: String, val text: String)

@Serializable
data class Kick(val player: String)

object SendMail : Capability<Mail>("test:outbox:smtp", serializer())
object KickPlayer : Capability<Kick>("test:outbox:kick", serializer())

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxTest {
    private val database = Database.connect(TestPostgres.config)
    private val open = Collections.synchronizedList(ArrayList<Ecs>())

    private fun process(config: OutboxConfig = OutboxConfig(baseBackoff = Duration.ZERO, poll = Duration.ofMillis(50))): Ecs =
        Pgecs.on(database) {
            loadModules = false
            engine = QueryEngineConfig(eqsatInstances = 0)
            outbox = config
            register(SendMail, KickPlayer)
        }.also { it.freeze(); open += it }

    @AfterAll
    fun close() {
        open.forEach(Ecs::close)
        database.close()
    }

    private fun exists(ecs: Ecs, e: EntityId) = ecs.read { exists(e) }

    @Test
    fun `an untargeted entry is delivered once, by an endpoint with the capability, after commit`() {
        val ecs = process()
        val got = Collections.synchronizedList(ArrayList<Mail>())
        val p = ecs.outbox.process(UUID.randomUUID(), "p1", "test")
        val mailer = p.endpoint(UUID.randomUUID(), "mailer") { handles(SendMail) { got += it } }
        val kicker = p.endpoint(UUID.randomUUID(), "kicker") { handles(KickPlayer) { } }
        val key = "k-${UUID.randomUUID()}"
        val entry = ecs.tx { outbox(SendMail, Mail("a@b", "hi $key"), idempotencyKey = key) }
        assertEquals(0, ecs.outbox.deliverOnce(kicker))
        while (ecs.outbox.deliverOnce(mailer) > 0) Unit
        assertEquals(listOf("hi $key"), got.map { it.text }.filter { key in it })
        assertFalse(exists(ecs, entry))
    }

    @Test
    fun `a rolled back transaction leaves no entry`() {
        val ecs = process()
        var entry: EntityId? = null
        runCatching { ecs.tx { entry = outbox(SendMail, Mail("a@b", "never")); error("abort") } }
        assertFalse(exists(ecs, entry!!))
    }

    @Test
    fun `misrouted and malformed entries are refused at commit`() {
        val ecs = process()
        val p = ecs.outbox.process(UUID.randomUUID(), "p2", "test")
        val kicker = p.endpoint(UUID.randomUUID(), "kicker") { handles(KickPlayer) { } }
        val misrouted = assertFailsWith<SQLException> { ecs.tx { outbox(SendMail, Mail("a@b", "x"), to = kicker.id) } }
        assertEquals("EC010", misrouted.sqlState)
        val malformed = assertFailsWith<SQLException> { ecs.tx { outbox(SendMail, Mail("ab", "x")) } }
        assertEquals("EC005", malformed.sqlState)
    }

    @Test
    fun `a targeted entry only runs on its endpoint`() {
        val ecs = process()
        val p = ecs.outbox.process(UUID.randomUUID(), "p3", "test")
        val ranOn = Collections.synchronizedList(ArrayList<String>())
        val a = p.endpoint(UUID.randomUUID(), "a") { handles(KickPlayer) { if (it.player == "t") ranOn += "a" } }
        val b = p.endpoint(UUID.randomUUID(), "b") { handles(KickPlayer) { if (it.player == "t") ranOn += "b" } }
        val entry = ecs.tx { outbox(KickPlayer, Kick("t"), to = b.id) }
        while (ecs.outbox.deliverOnce(a) > 0) Unit
        assertTrue(exists(ecs, entry))
        while (ecs.outbox.deliverOnce(b) > 0) Unit
        assertEquals(listOf("b"), ranOn)
        assertFalse(exists(ecs, entry))
    }

    @Test
    fun `failures are retried and then dead-lettered`() {
        val ecs = process(OutboxConfig(baseBackoff = Duration.ZERO, maxAttempts = 3))
        val p = ecs.outbox.process(UUID.randomUUID(), "p4", "test")
        var calls = 0
        val e = p.endpoint(UUID.randomUUID(), "flaky") { handles(KickPlayer) { if (it.player == "fail") { calls++; error("no") } } }
        val entry = ecs.tx { outbox(KickPlayer, Kick("fail"), to = e.id) }
        repeat(5) { ecs.outbox.deliverOnce(e) }
        assertEquals(3, calls)
        ecs.read {
            assertTrue(exists(entry))
            assertTrue(has(entry, OutboxDead))
            assertEquals(3, get(entry, OutboxComponent)!!.attempts)
        }
    }

    @Test
    fun `an endpoint that loses a capability dead-letters entries targeted at it`() {
        val ecs = process()
        val id = UUID.randomUUID()
        val p = ecs.outbox.process(UUID.randomUUID(), "p5", "test")
        val e = p.endpoint(id, "old") { handles(KickPlayer) { } }
        val entry = ecs.tx { outbox(KickPlayer, Kick("x"), to = e.id) }
        // restarted without the kick handler
        val ecs2 = process()
        ecs.outbox.close()
        val p2 = ecs2.outbox.process(UUID.randomUUID(), "p5b", "test")
        p2.endpoint(id, "new") { handles(SendMail) { } }
        ecs2.read { assertTrue(has(entry, OutboxDead)) }
    }

    @Test
    fun `deadlines turn waiting entries dead`() {
        val ecs = process()
        val endpointDown = ecs.outbox.process(UUID.randomUUID(), "p6", "test")
            .endpoint(UUID.randomUUID(), "down") { handles(KickPlayer) { } }
        val entry = ecs.tx { outbox(KickPlayer, Kick("late"), to = endpointDown.id, deadline = Instant.now().minusSeconds(1)) }
        ecs.tx { ecs.outbox.markExpired(this) }
        ecs.read { assertTrue(has(entry, OutboxDead)) }
    }

    @Test
    fun `liveness is the process lock, and a second process with the same id refuses to start`() {
        val id = UUID.randomUUID()
        val a = process()
        a.outbox.process(id, "p7", "test")
        assertTrue(a.outbox.isAlive(id))
        val b = process()
        assertFailsWith<ProcessAlreadyRunningException> { b.outbox.process(id, "p7", "test") }
        a.outbox.close()
        // the server releases the lock when the session ends, shortly after the connection closes
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (b.outbox.isAlive(id) && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(b.outbox.isAlive(id))
        b.outbox.process(id, "p7", "test")
        assertTrue(b.outbox.isAlive(id))
    }

    @Test
    fun `notifications wake a started endpoint`() {
        val ecs = process(OutboxConfig(poll = Duration.ofSeconds(30)))
        val p = ecs.outbox.process(UUID.randomUUID(), "p8", "test")
        val done = CountDownLatch(1)
        val tag = "n-${UUID.randomUUID()}"
        p.endpoint(UUID.randomUUID(), "fast") { handles(SendMail) { if (it.text == tag) done.countDown() } }
        ecs.outbox.start()
        Thread.sleep(200) // let the first (empty) claim pass, so only a notification can wake it in time
        ecs.tx { outbox(SendMail, Mail("a@b", tag)) }
        assertTrue(done.await(10, TimeUnit.SECONDS), "not woken by NOTIFY")
        ecs.outbox.close()
    }
}
