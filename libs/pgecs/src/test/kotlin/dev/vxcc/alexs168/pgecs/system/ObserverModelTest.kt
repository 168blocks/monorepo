package dev.vxcc.alexs168.pgecs.system

import dev.vxcc.alexs168.pgecs.ChildOf
import dev.vxcc.alexs168.pgecs.Component
import dev.vxcc.alexs168.pgecs.Ecs
import dev.vxcc.alexs168.pgecs.EcsTx
import dev.vxcc.alexs168.pgecs.EntityId
import dev.vxcc.alexs168.pgecs.Pgecs
import dev.vxcc.alexs168.pgecs.Tag
import dev.vxcc.alexs168.pgecs.db.Database
import dev.vxcc.alexs168.pgecs.query.QueryEngineConfig
import dev.vxcc.alexs168.pgecs.testing.TestPostgres
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@Serializable
data class ModelValue(val v: Int)

object ModelVal : Component<ModelValue>("test:model:val:v1")
object ModelTag : Tag("test:model:tag")

@Serializable
data class ModelCount(val n: Int)

/** Not observed: what the flaky observer writes. */
object ModelMarker : Component<ModelCount>("test:model:marker:v1")

/**
 * Model-based tests of observers: random transactions (create with components and a parent, set,
 * remove, tag, untag, delete with its children; committed or rolled back) against an in-memory
 * model of the entities, observers run at random points (one of them failing at random), retention
 * pruning in between. Then every observed change must be explained by the model:
 *
 * - each committed (transaction, entity) that changed an observed component is delivered exactly
 *   once, in `(xid, entity)` order, to every observer, and nothing else is: no rolled-back
 *   transaction, no entity the transaction did not write;
 * - `added`, `removed`, `updated` and `old` agree with the entity before and after the transaction;
 * - a failing observer's writes roll back with its cursor: its marker counts each delivered group once;
 * - the entities read back as the model says.
 *
 * The same runs with concurrent writers (each on its own entities, so the model stays exact) that
 * hold their transactions open for a while, while the observers poll: what `xmin` protects.
 *
 * Seeded: `-Dpgecs.model.seed=<n>` (Gradle: `-Ppgecs.model.seed`), `-Dpgecs.model.steps=<n>`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObserverModelTest {
    private val seed = System.getProperty("pgecs.model.seed")?.takeIf { it.isNotBlank() }
        ?.let { if (it == "random") System.nanoTime() else it.toLong() } ?: 168L
    private val steps = System.getProperty("pgecs.model.steps")?.toInt() ?: 300

    private val database = Database.connect(TestPostgres.config)
    private val open = Collections.synchronizedList(ArrayList<Ecs>())

    private fun process(): Ecs = Pgecs.on(database) {
        loadModules = false
        engine = QueryEngineConfig(eqsatInstances = 0)
        scheduler = SchedulerConfig(poll = Duration.ofMillis(20), changeRetention = Duration.ZERO)
        register(ModelVal, ModelTag, ModelMarker)
    }.also { it.freeze(); open += it }

    @AfterAll
    fun close() {
        open.forEach(Ecs::close)
        database.close()
    }

    // ---- the model ------------------------------------------------------------------------------

    /** An entity as the model has it; null when it does not exist. */
    private data class State(val value: Int?, val tag: Boolean, val parent: EntityId?)

    /** What one committed transaction did to one entity. */
    private data class Effect(val before: State?, val after: State?, val written: Boolean)

    private class Committed(val xid: Long, val effects: Map<EntityId, Effect>)

    /** What an observer was given, flattened while its transaction ran. */
    private data class Seen(
        val xid: Long,
        val entity: EntityId,
        val added: Boolean,
        val removed: Boolean,
        val updated: Boolean,
        val oldValue: Int?,
        val tagAdded: Boolean,
        val tagRemoved: Boolean,
        val hadTag: Boolean,
    )

    private fun seen(c: EntityChange) = Seen(
        c.xid, c.entity,
        c.added(ModelVal), c.removed(ModelVal), c.updated(ModelVal), c.old[ModelVal]?.v,
        c.added(ModelTag), c.removed(ModelTag), c.old.had(ModelTag),
    )

    /**
     * One writer: random transactions on its own entities. [entities] is the model, updated only after
     * a commit; [log] gets every committed transaction.
     */
    private inner class Writer(val ecs: Ecs, val rnd: Random, val hold: Boolean) {
        val entities = LinkedHashMap<EntityId, State>()
        val log = ArrayList<Committed>()
        var rolledBack = 0
        var failed = 0

        private fun descendants(e: EntityId, state: Map<EntityId, State>): List<EntityId> =
            listOf(e) + state.filterValues { it.parent == e }.keys.flatMap { descendants(it, state) }

        fun step() {
            val ops = 1 + rnd.nextInt(4)
            val rollback = rnd.nextInt(6) == 0
            // planned on a copy; applied to the model only once committed
            val result = try {
                ecs.tx {
                    val state = LinkedHashMap(entities)
                    val before = LinkedHashMap<EntityId, State?>()
                    val written = HashSet<EntityId>()
                    fun touch(e: EntityId) {
                        if (e !in before) before[e] = state[e]
                    }
                    repeat(ops) { op(this, state, ::touch, written) }
                    if (hold && rnd.nextInt(3) == 0) Thread.sleep(rnd.nextLong(1, 15))
                    val xid = connection.prepareStatement("select pg_current_xact_id()::text::bigint").use { st ->
                        st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                    }
                    if (rollback) throw RolledBack()
                    Triple(xid, state, before.mapValues { (e, b) -> Effect(b, state[e], e in written) })
                }
            } catch (_: RolledBack) {
                rolledBack++
                return
            } catch (e: Exception) {
                // gave up on serialization failures: rolled back as well
                if (!dev.vxcc.alexs168.pgecs.db.SqlStates.isRetryable(e)) throw e
                failed++
                return
            }
            val (xid, state, effects) = result
            entities.clear()
            entities.putAll(state)
            log += Committed(xid, effects)
        }

        private fun op(tx: EcsTx, state: MutableMap<EntityId, State>, touch: (EntityId) -> Unit, written: MutableSet<EntityId>) {
            val existing = state.keys.toList()
            when (if (existing.isEmpty()) 0 else rnd.nextInt(7)) {
                0 -> {
                    val value = if (rnd.nextBoolean()) rnd.nextInt(3) else null
                    val tag = rnd.nextBoolean()
                    val parent = if (existing.isNotEmpty() && rnd.nextInt(3) == 0) existing.random(rnd) else null
                    val e = tx.create {
                        if (value != null) this[ModelVal] = ModelValue(value)
                        if (tag) +ModelTag
                        if (parent != null) set(ChildOf, parent)
                    }
                    touch(e)
                    written += e
                    state[e] = State(value, tag, parent)
                }
                1, 2 -> {
                    val e = existing.random(rnd)
                    val v = rnd.nextInt(3)
                    touch(e)
                    written += e
                    tx.update(e) { this[ModelVal] = ModelValue(v) }
                    state[e] = state.getValue(e).copy(value = v)
                }
                3 -> {
                    val e = existing.random(rnd)
                    touch(e)
                    written += e
                    tx.update(e) { this[ModelVal] = null }
                    state[e] = state.getValue(e).copy(value = null)
                }
                4 -> {
                    val e = existing.random(rnd)
                    val t = rnd.nextBoolean()
                    touch(e)
                    written += e
                    tx.update(e) { if (t) +ModelTag else -ModelTag }
                    state[e] = state.getValue(e).copy(tag = t)
                }
                5 -> {
                    // remove and add again in one transaction
                    val e = existing.random(rnd)
                    val v = rnd.nextInt(3)
                    touch(e)
                    written += e
                    tx.update(e) { this[ModelVal] = null }
                    tx.flush()
                    tx.update(e) { this[ModelVal] = ModelValue(v) }
                    state[e] = state.getValue(e).copy(value = v)
                }
                else -> {
                    val e = existing.random(rnd)
                    // deleting a parent deletes its children (ChildOf)
                    for (d in descendants(e, state)) {
                        touch(d)
                        written += d
                        state.remove(d)
                    }
                    tx.delete(e)
                }
            }
        }
    }

    private class RolledBack : RuntimeException()

    /** Two observers of the same changes: one plain, one that fails at random and counts what it saw. */
    private inner class Observers(val ecs: Ecs, rnd: Random) {
        val name = "test:model:${UUID.randomUUID().toString().take(8)}"
        val plain = Collections.synchronizedList(ArrayList<Seen>())
        val flaky = Collections.synchronizedList(ArrayList<Seen>())
        val failures = java.util.concurrent.atomic.AtomicInteger()
        private val failRnd = Random(rnd.nextLong())
        val counter: EntityId = ecs.tx { create { this[ModelMarker] = ModelCount(0) } }

        init {
            val on = arrayOf(Observe.any(ModelVal), Observe.any(ModelTag))
            // a retried transaction runs the action again: keep only what the committed attempt saw
            ecs.systems.observer("$name:plain", *on, batchSize = 1 + rnd.nextInt(5)) { changes -> plainPending.set(changes.map(::seen)) }
            ecs.systems.observer("$name:flaky", *on, batchSize = 1 + rnd.nextInt(5)) { changes ->
                val n = get(counter, ModelMarker)!!.n
                update(counter) { this[ModelMarker] = ModelCount(n + changes.size) }
                if (synchronized(failRnd) { failRnd.nextInt(4) } == 0) {
                    failures.incrementAndGet()
                    error("flaky observer fails")
                }
                // only recorded once the transaction is sure to commit: after this block
                flakyPending.set(changes.map(::seen))
            }
        }

        private val flakyPending = ThreadLocal<List<Seen>>()
        private val plainPending = ThreadLocal<List<Seen>>()

        fun runOnce(): Boolean {
            val a = try {
                ecs.systems.runOnce("$name:plain").also { plainPending.get()?.let { plain += it } }
            } finally {
                plainPending.remove()
            }
            val b = try {
                ecs.systems.runOnce("$name:flaky").also { flakyPending.get()?.let { flaky += it } }
            } catch (e: Exception) {
                if (generateSequence<Throwable>(e) { it.cause }.none { it.message == "flaky observer fails" }) throw e
                true
            } finally {
                flakyPending.remove()
            }
            return a || b
        }

        fun drain() {
            var idle = 0
            while (idle < 3) if (runOnce()) idle = 0 else idle++
        }
    }

    // ---- checks ---------------------------------------------------------------------------------

    private fun key(s: Seen) = s.xid to s.entity.id.toString()

    private fun verify(label: String, writers: List<Writer>, obs: Observers) {
        val committed = writers.flatMap { it.log }.associateBy { it.xid }
        for ((what, seen) in listOf("plain" to obs.plain.toList(), "flaky" to obs.flaky.toList())) {
            val where = "$label, $what observer (seed $seed)"
            // order and uniqueness
            val keys = seen.map(::key)
            assertEquals(keys.distinct(), keys, "$where: a group was delivered twice")
            val sorted = keys.sortedWith(compareBy<Pair<Long, String>> { it.first }.thenBy { it.second })
            assertEquals(sorted, keys, "$where: groups out of (xid, entity) order")

            val delivered = seen.associateBy { it.xid to it.entity }
            for (s in seen) {
                val tx = committed[s.xid] ?: fail("$where: delivered a transaction that did not commit: $s")
                val effect = tx.effects[s.entity] ?: fail("$where: delivered an entity the transaction did not write: $s")
                check(where, s, effect)
            }
            for (tx in committed.values) {
                for ((e, effect) in tx.effects) {
                    val changed = effect.before?.value != effect.after?.value || (effect.before?.tag ?: false) != (effect.after?.tag ?: false)
                    if (changed && (tx.xid to e) !in delivered) fail("$where: missed xid ${tx.xid} entity $e: $effect")
                }
            }
        }
        val marker = obs.ecs.read { get(obs.counter, ModelMarker)!!.n }
        assertEquals(obs.flaky.size, marker, "$label: the flaky observer's writes of failed runs were not rolled back (seed $seed)")
        for (w in writers) {
            obs.ecs.read {
                for ((e, s) in w.entities) {
                    assertTrue(exists(e), "$label: $e should exist")
                    assertEquals(s.value, get(e, ModelVal)?.v, "$label: value of $e")
                    assertEquals(s.tag, has(e, ModelTag), "$label: tag of $e")
                }
            }
        }
    }

    private fun check(where: String, s: Seen, e: Effect) {
        val before = e.before?.value
        val after = e.after?.value
        val msg = "$where: $s for $e"
        assertEquals(before == null && after != null, s.added, "added: $msg")
        assertEquals(before != null && after == null, s.removed, "removed: $msg")
        if (s.updated) assertTrue(before != null && after != null, "updated without a value before and after: $msg")
        if (before != null && after != null && before != after) assertTrue(s.updated, "value changed but not updated: $msg")
        // old values are known for the rows the transaction changed
        if (s.oldValue != null) assertEquals(before, s.oldValue, "old value: $msg")
        if (before != null && before != after) assertEquals(before, s.oldValue, "old value: $msg")
        val tagBefore = e.before?.tag ?: false
        val tagAfter = e.after?.tag ?: false
        assertEquals(!tagBefore && tagAfter, s.tagAdded, "tag added: $msg")
        assertEquals(tagBefore && !tagAfter, s.tagRemoved, "tag removed: $msg")
        if (s.hadTag) assertTrue(tagBefore, "had the tag: $msg")
    }

    // ---- tests ----------------------------------------------------------------------------------

    @Test
    fun `observers see exactly the committed changes, in order, as the model says`() {
        val rnd = Random(seed)
        val ecs = process()
        val obs = Observers(ecs, rnd)
        val w = Writer(ecs, Random(rnd.nextLong()), hold = false)
        repeat(steps) {
            when (rnd.nextInt(10)) {
                in 0..6 -> w.step()
                in 7..8 -> obs.runOnce()
                else -> ecs.tx { ecs.systems.pruneChanges(this) }
            }
        }
        obs.drain()
        verify("sequential", listOf(w), obs)
        assertTrue(w.log.size > steps / 3, "only ${w.log.size} transactions committed")
        println("sequential: ${w.log.size} committed, ${w.rolledBack} rolled back, ${obs.plain.size} groups observed, ${obs.failures} observer failures")
    }

    @Test
    fun `observers keep up with concurrent writers that hold transactions open`() {
        val rnd = Random(seed + 1)
        val ecs = process()
        val obs = Observers(ecs, rnd)
        val writers = List(4) { Writer(ecs, Random(rnd.nextLong()), hold = true) }
        val done = AtomicBoolean()
        val pool = Executors.newFixedThreadPool(writers.size + 1)
        val gaveUp = java.util.concurrent.atomic.AtomicInteger()
        val polling = pool.submit {
            while (!done.get()) {
                // like the scheduler: a run that gave up on serialization failures is tried again later
                try {
                    obs.runOnce()
                    ecs.tx { ecs.systems.pruneChanges(this) }
                } catch (e: Exception) {
                    if (!dev.vxcc.alexs168.pgecs.db.SqlStates.isRetryable(e)) throw e
                    gaveUp.incrementAndGet()
                }
            }
        }
        val work = writers.map { w -> pool.submit { repeat(steps / 2) { w.step() } } }
        work.forEach { it.get(5, TimeUnit.MINUTES) }
        done.set(true)
        polling.get(1, TimeUnit.MINUTES)
        pool.shutdown()
        obs.drain()
        verify("concurrent", writers, obs)
        println(
            "concurrent: ${writers.sumOf { it.log.size }} committed, ${writers.sumOf { it.rolledBack }} rolled back, " +
                "${writers.sumOf { it.failed }} gave up, ${obs.plain.size} groups observed, ${obs.failures} observer failures, " +
                "${gaveUp.get()} observer or pruning runs gave up on serialization failures",
        )
    }
}
