package net.blocks168.pgecs.system

import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import net.blocks168.pgecs.ChildOf
import net.blocks168.pgecs.Component
import net.blocks168.pgecs.Ecs
import net.blocks168.pgecs.EntityId
import net.blocks168.pgecs.Pgecs
import net.blocks168.pgecs.Tag
import net.blocks168.pgecs.db.Database
import net.blocks168.pgecs.query.QueryEngineConfig
import net.blocks168.pgecs.serial.InstantAsEpochMillis
import net.blocks168.pgecs.testing.TestPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

object ExpiresComponent : Component<Instant>("test:sys:expires:v1", InstantAsEpochMillis)

@Serializable
data class Ban(val reason: String)

object BanComponent : Component<Ban>("test:sys:ban:v1")
object Account : Tag("test:sys:account")
object Notified : Tag("test:sys:notified")
object Work : Tag("test:sys:work")

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SystemsTest {
    private val database = Database.connect(TestPostgres.config)
    private val open = Collections.synchronizedList(ArrayList<Ecs>())

    /** A process: its own Ecs with its own process id, on the shared database. */
    private fun process(lease: Duration = Duration.ofSeconds(30)): Ecs = Pgecs.on(database) {
        loadModules = false
        engine = QueryEngineConfig(eqsatInstances = 0)
        scheduler = SchedulerConfig(lease = lease, poll = Duration.ofMillis(20))
        register(ExpiresComponent, BanComponent, Account, Notified, Work)
    }.also { it.freeze(); open += it }

    @AfterAll
    fun close() {
        open.forEach(Ecs::close)
        database.close()
    }

    private fun unique(base: String) = "test:$base:${UUID.randomUUID().toString().take(8)}"

    private fun expirySystem(ecs: Ecs, name: String, batch: Int = 2) = ecs.systems.system(name, Duration.ofMinutes(5), batch, {
        val e by entity
        e has ExpiresComponent.where { it < now() }
        select { e }
    }) { rows -> rows.forEach { delete(it) } }

    @Test
    fun `expiry deletes expired entities and their children, in batches`() {
        val ecs = process()
        val past = Instant.now().minusSeconds(60)
        val (expired, kept, child) = ecs.tx {
            val expired = (1..5).map { create { this[ExpiresComponent] = past } }
            val kept = create { this[ExpiresComponent] = Instant.now().plusSeconds(3600) }
            val child = create { set(ChildOf, expired[0]) }
            Triple(expired, kept, child)
        }
        val s = expirySystem(ecs, unique("expire"))
        assertTrue(ecs.systems.runOnce(s.name))
        ecs.read {
            expired.forEach { assertFalse(exists(it), "$it should be gone") }
            assertFalse(exists(child))
            assertTrue(exists(kept))
        }
        assertTrue(s.batches.get() >= 3, "5 rows in batches of 2: ${s.batches.get()}")
        // not due again for five minutes
        assertFalse(ecs.systems.runOnce(s.name))
    }

    @Test
    fun `one run at a time, the lease keeps a second process out, and helpers share batches`() {
        val a = process()
        val b = process()
        val name = unique("work")
        val seen = Collections.synchronizedList(ArrayList<EntityId>())
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        fun register(ecs: Ecs, slow: Boolean) = ecs.systems.system(name, Duration.ofMinutes(5), 1, {
            val e by entity
            e has Work
            select { e }
        }) { rows ->
            if (slow) {
                started.countDown()
                proceed.await(10, TimeUnit.SECONDS)
            }
            rows.forEach { seen += it; update(it) { -Work } }
        }
        val items = a.tx { (1..6).map { create { +Work } } }
        register(a, slow = true)
        register(b, slow = false)
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        val leader = pool.submit<Boolean> { a.systems.runOnce(name) }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        // b cannot start a run, but helps the one a leads; skip locked keeps it off a's locked row
        assertTrue(b.systems.runOnce(name))
        proceed.countDown()
        assertTrue(leader.get(10, TimeUnit.SECONDS))
        // (a batch can be retried after a serialization failure, so seen may hold an item twice)
        assertEquals(items.toSet(), seen.toSet())
        a.read { items.forEach { assertFalse(has(it, Work)) } }
        pool.close()
    }

    @Test
    fun `an expired lease lets another process take over`() {
        val a = process(lease = Duration.ofMillis(200))
        val b = process(lease = Duration.ofMillis(200))
        val name = unique("takeover")
        val runs = Collections.synchronizedList(ArrayList<String>())
        a.systems.task(name, Duration.ofMillis(1)) { runs += "a" }
        b.systems.task(name, Duration.ofMillis(1)) { runs += "b" }
        // a claims the lease and "crashes" before finishing: simulate by claiming through SQL
        database.tx {
            connection.prepareStatement(
                "update ecs_component set value = value || jsonb_build_object('leaseOwner', 'dead', 'leaseUntil', (extract(epoch from now()) * 1000)::bigint + 150) " +
                    "where entity = ? and rel = 'pgecs:system:v1'",
            ).use { it.setObject(1, a.systems.entityOf(name).id); it.executeUpdate() }
        }
        assertFalse(b.systems.runOnce(name))
        Thread.sleep(300)
        assertTrue(b.systems.runOnce(name))
        assertEquals(listOf("b"), runs)
    }

    @Test
    fun `a removal observer sees the entity as it was, once, after commit`() {
        val ecs = process()
        val name = unique("notify-ban-ended")
        val told = Collections.synchronizedList(ArrayList<Pair<EntityId, String>>())
        val obs = ecs.systems.observer(name, Observe.removed(BanComponent)) { changes ->
            for (ban in changes) {
                val account = ban.old[ChildOf] ?: continue
                told += account to ban.old[BanComponent]!!.reason
                update(account) { +Notified }
            }
        }
        val (account, ban) = ecs.tx {
            val acc = create { +Account }
            val ban = create { this[BanComponent] = Ban("spam"); set(ChildOf, acc) }
            acc to ban
        }
        // adding a ban is not a removal
        assertFalse(ecs.systems.runOnce(name))
        ecs.tx { delete(ban) }
        assertTrue(ecs.systems.runOnce(name))
        assertEquals(listOf(account to "spam"), told)
        ecs.read { assertTrue(has(account, Notified)) }
        // the cursor moved in the same transaction: nothing again
        assertFalse(ecs.systems.runOnce(name))
        assertEquals(1, told.size)
        assertEquals(1, obs.batches.get())
    }

    @Test
    fun `deleting a parent is seen as removal of its children's components`() {
        val ecs = process()
        val name = unique("cascade")
        val removed = Collections.synchronizedList(ArrayList<EntityId>())
        ecs.systems.observer(name, Observe.removed(BanComponent)) { changes -> changes.forEach { removed += it.entity } }
        val (acc, ban) = ecs.tx {
            val acc = create { +Account }
            acc to create { this[BanComponent] = Ban("x"); set(ChildOf, acc) }
        }
        ecs.tx { delete(acc) }
        assertTrue(ecs.systems.runOnce(name))
        assertEquals(listOf(ban), removed)
    }

    @Test
    fun `observers get added and updated changes grouped per transaction and entity, in commit order`() {
        val ecs = process()
        val name = unique("changes")
        val log = Collections.synchronizedList(ArrayList<String>())
        ecs.systems.observer(name, Observe.added(BanComponent), Observe.updated(BanComponent), batchSize = 1) { changes ->
            for (c in changes) {
                log += when {
                    c.added(BanComponent) -> "added"
                    c.updated(BanComponent) -> "updated from ${c.old[BanComponent]!!.reason}"
                    else -> "other"
                }
            }
        }
        val e = ecs.tx { create { this[BanComponent] = Ban("a") } }
        ecs.tx { update(e) { this[BanComponent] = Ban("b") } }
        ecs.tx { update(e) { this[BanComponent] = Ban("c") } }
        while (ecs.systems.runOnce(name)) Unit
        assertEquals(listOf("added", "updated from a", "updated from b"), log)
    }

    @Test
    fun `change views tell keys apart`() {
        val t1 = EntityId.of(UUID.randomUUID())
        val t2 = EntityId.of(UUID.randomUUID())
        val c = EntityChange(
            EntityId.of(UUID.randomUUID()), 1,
            listOf(
                ChangeRow(ChildOf.name, t1, ChangeOp.UPDATE, null),
                ChangeRow(ChildOf.name, t2, ChangeOp.INSERT, null),
                ChangeRow(ChildOf.name, t2, ChangeOp.DELETE, null),
            ),
        )
        assertFalse(c.removed(ChildOf), "t1 is kept, t2 never existed before")
        assertFalse(c.added(ChildOf))
        assertTrue(c.updated(ChildOf))
        assertEquals(listOf(t1), c.old.targets(ChildOf))
    }

    @Test
    fun `a new observer starts after what was already committed`() {
        val ecs = process()
        ecs.tx { create { this[BanComponent] = Ban("old") } }
        val name = unique("late")
        val seen = Collections.synchronizedList(ArrayList<String>())
        ecs.systems.observer(name, Observe.added(BanComponent)) { cs -> cs.forEach { seen += "x" } }
        assertFalse(ecs.systems.runOnce(name))
        ecs.tx { create { this[BanComponent] = Ban("new") } }
        assertTrue(ecs.systems.runOnce(name))
        assertEquals(1, seen.size)
    }

    @Test
    fun `an observer failure rolls back its writes and keeps the cursor`() {
        val ecs = process()
        val name = unique("fails")
        var fail = true
        val marked = ecs.tx { create { +Account } }
        ecs.systems.observer(name, Observe.added(BanComponent)) {
            update(marked) { +Notified }
            if (fail) error("boom")
        }
        ecs.tx { create { this[BanComponent] = Ban("x") } }
        runCatching { ecs.systems.runOnce(name) }
        ecs.read { assertFalse(has(marked, Notified)) }
        fail = false
        assertTrue(ecs.systems.runOnce(name))
        ecs.read { assertTrue(has(marked, Notified)) }
    }

    @Test
    fun `the scheduler runs systems in the background`() {
        val ecs = process()
        val name = unique("bg")
        val done = CountDownLatch(1)
        ecs.systems.observer(name, Observe.added(BanComponent)) { done.countDown() }
        ecs.systems.start()
        ecs.tx { create { this[BanComponent] = Ban("bg") } }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        ecs.systems.close()
    }

    @Test
    fun `retention prunes only what every observer has passed`() {
        val ecs = process()
        val name = unique("retention")
        ecs.systems.observer(name, Observe.added(BanComponent)) { }
        val e = ecs.tx { create { this[BanComponent] = Ban("r") } }
        // pretend the row is old
        database.tx { connection.createStatement().use { it.executeUpdate("update ecs_change set at = now() - interval '30 days' where entity = '${e.id}'") } }
        fun rows() = database.read {
            connection.createStatement().use { st -> st.executeQuery("select count(*) from ecs_change where entity = '${e.id}'").use { it.next(); it.getLong(1) } }
        }
        ecs.tx { ecs.systems.pruneChanges(this) }
        assertEquals(1, rows(), "the observer has not passed it yet")
        // other tests' observers hold retention back too; move them all past it
        database.tx {
            connection.createStatement().use {
                it.executeUpdate(
                    "update ecs_component set value = value || jsonb_build_object('cursorXid', pg_snapshot_xmin(pg_current_snapshot())::text::bigint) " +
                        "where rel = 'pgecs:system:v1' and value->>'kind' = 'observer'",
                )
            }
        }
        ecs.tx { ecs.systems.pruneChanges(this) }
        assertEquals(0, rows())
    }
}
