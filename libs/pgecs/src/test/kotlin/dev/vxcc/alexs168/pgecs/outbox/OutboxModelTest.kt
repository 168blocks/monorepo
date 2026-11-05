package dev.vxcc.alexs168.pgecs.outbox

import dev.vxcc.alexs168.pgecs.Capability
import dev.vxcc.alexs168.pgecs.Ecs
import dev.vxcc.alexs168.pgecs.EntityId
import dev.vxcc.alexs168.pgecs.Pgecs
import dev.vxcc.alexs168.pgecs.db.Database
import dev.vxcc.alexs168.pgecs.db.SqlStates
import dev.vxcc.alexs168.pgecs.query.QueryEngineConfig
import dev.vxcc.alexs168.pgecs.testing.TestPostgres
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

@Serializable
data class ModelJob(val key: String)

object ModelCapA : Capability<ModelJob>("test:outbox-model:a", serializer())
object ModelCapB : Capability<ModelJob>("test:outbox-model:b", serializer())

/**
 * Model-based test of the outbox: writers commit or roll back random entries (either capability,
 * untargeted or targeted at an endpoint that handles it, some not before a moment in the future),
 * while one delivery thread per endpoint runs [Outbox.deliverOnce] and handlers fail at random.
 * Then, against what the writers committed:
 *
 * - a rolled-back entry never runs;
 * - an entry runs only on endpoints with its capability, only on its target if it has one, and not
 *   before `notBefore`;
 * - every committed entry either succeeded exactly once and is gone (claims last longer than the test,
 *   so at-least-once is exactly once here), or failed `maxAttempts` times and is dead, kept with its
 *   attempts counted.
 *
 * Seeded: `-Ppgecs.model.seed`, `-Ppgecs.model.steps` (as ObserverModelTest).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxModelTest {
    private val seed = System.getProperty("pgecs.model.seed")?.takeIf { it.isNotBlank() }
        ?.let { if (it == "random") System.nanoTime() else it.toLong() } ?: 168L
    private val steps = System.getProperty("pgecs.model.steps")?.toInt() ?: 300
    private val maxAttempts = 3

    private val database = Database.connect(TestPostgres.config)
    private val open = Collections.synchronizedList(ArrayList<Ecs>())

    @AfterAll
    fun close() {
        open.forEach(Ecs::close)
        database.close()
    }

    private class Entry(val key: String, val capability: Capability<ModelJob>, val target: EntityId?, val notBefore: Instant?, val entity: EntityId)

    private class Call(val key: String, val endpoint: EntityId, val capability: String, val at: Instant, val ok: Boolean)

    @Test
    fun `entries run once where they may, or die after the last attempt`() {
        val rnd = Random(seed)
        val ecs = Pgecs.on(database) {
            loadModules = false
            engine = QueryEngineConfig(eqsatInstances = 0)
            outbox = OutboxConfig(baseBackoff = Duration.ZERO, maxAttempts = maxAttempts, poll = Duration.ofMillis(20))
            register(ModelCapA, ModelCapB)
        }.also { it.freeze(); open += it }

        val calls = Collections.synchronizedList(ArrayList<Call>())
        val process = ecs.outbox.process(UUID.randomUUID(), "model", "test")
        fun endpoint(name: String, vararg caps: Capability<ModelJob>): Outbox.Endpoint {
            val id = UUID.randomUUID()
            val r = Random(rnd.nextLong())
            return process.endpoint(id, name) {
                for (cap in caps) {
                    handles(cap) { job ->
                        val ok = synchronized(r) { r.nextInt(3) != 0 }
                        calls += Call(job.key, EntityId.of(id), cap.name, Instant.now(), ok)
                        if (!ok) error("handler fails")
                    }
                }
            }
        }
        val endpoints = listOf(endpoint("ab", ModelCapA, ModelCapB), endpoint("a", ModelCapA), endpoint("b", ModelCapB))
        val handles = endpoints.associate { e -> e.id to e.handlers.keys.map { it.name }.toSet() }

        val committed = Collections.synchronizedList(ArrayList<Entry>())
        val rolledBack = Collections.synchronizedList(ArrayList<String>())
        val writing = AtomicBoolean(true)
        val gaveUp = AtomicInteger()

        fun write(r: Random) {
            val n = 1 + r.nextInt(3)
            val rollback = r.nextInt(5) == 0
            val planned = List(n) {
                val cap = if (r.nextBoolean()) ModelCapA else ModelCapB
                val target = if (r.nextInt(3) == 0) endpoints.filter { cap.name in handles.getValue(it.id) }.random(r).id else null
                val notBefore = if (r.nextInt(5) == 0) Instant.now().plusMillis(r.nextLong(50, 300)) else null
                Triple("k-${UUID.randomUUID()}", cap, target to notBefore)
            }
            try {
                val entries = ecs.tx {
                    val out = planned.map { (key, cap, tn) ->
                        val (target, notBefore) = tn
                        Entry(key, cap, target, notBefore, outbox(cap, ModelJob(key), to = target, idempotencyKey = key, notBefore = notBefore))
                    }
                    if (rollback) throw RolledBack()
                    out
                }
                committed += entries
            } catch (_: RolledBack) {
                rolledBack += planned.map { it.first }
            } catch (e: Exception) {
                if (!SqlStates.isRetryable(e)) throw e
                gaveUp.incrementAndGet()
                rolledBack += planned.map { it.first }
            }
        }

        val pool = Executors.newFixedThreadPool(endpoints.size + 2)
        val delivery = endpoints.map { e ->
            pool.submit {
                var idle = 0
                // after the writers stop: until nothing is claimable for a while (backoffs and notBefore pass)
                while (writing.get() || idle < 50) {
                    val n = try {
                        ecs.outbox.deliverOnce(e)
                    } catch (ex: Exception) {
                        if (!SqlStates.isRetryable(ex)) throw ex
                        gaveUp.incrementAndGet()
                        1
                    }
                    if (n == 0) {
                        idle++
                        Thread.sleep(5)
                    } else {
                        idle = 0
                    }
                }
            }
        }
        val writers = List(2) { i ->
            val r = Random(rnd.nextLong() + i)
            pool.submit { repeat(steps / 2) { write(r) } }
        }
        writers.forEach { it.get(5, TimeUnit.MINUTES) }
        writing.set(false)
        delivery.forEach { it.get(5, TimeUnit.MINUTES) }
        pool.shutdown()

        // ---- check against the model
        val byKey = calls.groupBy { it.key }
        val where = "seed $seed"
        for (k in rolledBack) assertFalse(k in byKey, "$where: rolled-back entry $k ran")
        val known = committed.map { it.key }.toSet()
        for (k in byKey.keys) assertTrue(k in known, "$where: an entry nobody committed ran: $k")
        for (entry in committed) {
            val runs = byKey[entry.key].orEmpty()
            for (c in runs) {
                assertEquals(entry.capability.name, c.capability, "$where: ${entry.key} ran as another capability")
                assertTrue(entry.capability.name in handles.getValue(c.endpoint), "$where: ${entry.key} ran on an endpoint without the capability")
                if (entry.target != null) assertEquals(entry.target, c.endpoint, "$where: targeted ${entry.key} ran elsewhere")
                // the database clock decides; allow for a little difference to this JVM's
                if (entry.notBefore != null) assertTrue(!c.at.isBefore(entry.notBefore.minusMillis(20)), "$where: ${entry.key} ran before notBefore")
            }
            val successes = runs.count { it.ok }
            val (exists, dead, attempts) = ecs.read {
                Triple(exists(entry.entity), has(entry.entity, OutboxDead), get(entry.entity, OutboxComponent)?.attempts)
            }
            when {
                successes == 1 -> {
                    assertFalse(exists, "$where: ${entry.key} succeeded but its entry is still there")
                    assertTrue(runs.size <= maxAttempts && runs.last().ok, "$where: ${entry.key} ran after it succeeded: ${runs.map { it.ok }}")
                }
                successes == 0 -> {
                    assertTrue(exists && dead, "$where: ${entry.key} never succeeded, but is not dead (exists $exists, runs ${runs.size})")
                    assertEquals(maxAttempts, runs.size, "$where: ${entry.key} is dead after ${runs.size} runs")
                    assertEquals(maxAttempts, attempts, "$where: ${entry.key} counts $attempts attempts")
                }
                else -> fail("$where: ${entry.key} succeeded $successes times")
            }
        }
        val dead = committed.count { e -> byKey[e.key].orEmpty().none { it.ok } }
        println(
            "outbox model: ${committed.size} entries committed, ${rolledBack.size} rolled back, ${calls.size} handler calls, " +
                "$dead dead, ${gaveUp.get()} transactions gave up on serialization failures",
        )
    }

    private class RolledBack : RuntimeException()
}
