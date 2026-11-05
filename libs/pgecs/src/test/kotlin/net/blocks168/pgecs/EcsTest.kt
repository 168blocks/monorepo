package net.blocks168.pgecs

import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.AfterAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import net.blocks168.pgecs.db.Database
import net.blocks168.pgecs.dsl.QueryResultException
import net.blocks168.pgecs.query.QueryEngineConfig
import net.blocks168.pgecs.schema.CoreComponents
import net.blocks168.pgecs.schema.IndexSpec
import net.blocks168.pgecs.serial.InstantAsEpochMillis
import net.blocks168.pgecs.serial.Pattern
import net.blocks168.pgecs.testing.TestPostgres
import org.junit.jupiter.api.TestInstance
import java.sql.SQLException

@Serializable
data class Mail(@Pattern("^[^@]+@[^@]+$") val address: String, @Serializable(InstantAsEpochMillis::class) val verifiedAt: Instant? = null)

@Serializable
data class Score(val points: Long)

object MailComponent : Component<Mail>("test:ecs:mail:v1") {
    init {
        index(IndexSpec("address", listOf("(lower(value->>'address'))"), unique = true))
        validate("no spaces") { !it.address.contains(' ') }
    }
}

object ScoreComponent : Component<Score>("test:ecs:score:v1")
object Member : Tag("test:ecs:member")
object Muted : Tag("test:ecs:muted")
object Likes : Relation("test:ecs:likes")
object Leads : ExclusiveRelation("test:ecs:leads")
object NotRegistered : Tag("test:ecs:not-registered")

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EcsTest {
    private val database = Database.connect(TestPostgres.config)
    private val ecs = Pgecs.on(database) {
        loadModules = false
        engine = QueryEngineConfig(eqsatInstances = 0)
        register(MailComponent, ScoreComponent, Member, Muted, Likes, Leads)
    }.also { it.freeze() }

    @AfterAll
    fun close() {
        ecs.close()
        database.close()
    }

    @Test
    fun `create, read, update and delete`() {
        val acc = ecs.tx {
            create {
                +Member
                this[ScoreComponent] = Score(3)
            }
        }
        ecs.read {
            assertTrue(exists(acc))
            assertTrue(has(acc, Member))
            assertFalse(has(acc, Muted))
            assertEquals(Score(3), get(acc, ScoreComponent))
        }
        ecs.tx {
            update(acc) {
                -Member
                +Muted
                this[ScoreComponent] = Score(get(ScoreComponent)!!.points + 1)
            }
        }
        ecs.read {
            assertFalse(has(acc, Member))
            assertTrue(has(acc, Muted))
            assertEquals(Score(4), get(acc, ScoreComponent))
        }
        ecs.tx { update(acc) { this[ScoreComponent] = null } }
        ecs.read { assertNull(get(acc, ScoreComponent)) }
        ecs.tx { delete(acc) }
        ecs.read { assertFalse(exists(acc)) }
    }

    @Test
    fun `new ids are UUIDv7 and sort by creation time`() {
        val a = Uuid7.next(1_000)
        val b = Uuid7.next(2_000)
        assertEquals(7, a.version())
        assertEquals(2, a.variant())
        assertTrue(WriteBuffer.UUID_ORDER.compare(a, b) < 0)
        val id = ecs.tx { create() }
        assertEquals(7, id.id.version())
    }

    @Test
    fun `exclusive relations replace their pair, others accumulate`() {
        val (x, a, b) = ecs.tx { Triple(create(), create(), create()) }
        ecs.tx {
            update(x) {
                add(Likes, a)
                add(Likes, b)
                add(Leads, a)
                add(Leads, b)
            }
        }
        ecs.read {
            assertEquals(listOf(a, b).sortedBy { it.toString() }, targets(x, Likes))
            assertEquals(b, target(x, Leads))
        }
        ecs.tx { update(x) { set(Leads, a); remove(Likes, b) } }
        ecs.read {
            assertEquals(a, target(x, Leads))
            assertEquals(listOf(a), targets(x, Likes))
        }
        ecs.tx { update(x) { removeAll(Likes); set(Leads, null) } }
        ecs.read {
            assertEquals(emptyList(), targets(x, Likes))
            assertNull(target(x, Leads))
        }
    }

    @Test
    fun `deleting a parent deletes its children`() {
        val (parent, child, grandchild) = ecs.tx {
            val p = create { +Member }
            val c = create { set(ChildOf, p) }
            val g = create { set(ChildOf, c) }
            Triple(p, c, g)
        }
        ecs.tx { delete(parent) }
        ecs.read {
            assertFalse(exists(child))
            assertFalse(exists(grandchild))
        }
    }

    @Test
    fun `writes keep fields that a newer process wrote`() {
        val e = ecs.tx { create { this[ScoreComponent] = Score(1) } }
        // a newer JVM adds a field
        database.tx {
            connection.prepareStatement("update ecs_component set value = '{\"points\": 1, \"bonus\": 7}' where entity = ? and rel = ?").use {
                it.setObject(1, e.id)
                it.setString(2, ScoreComponent.name)
                it.executeUpdate()
            }
        }
        ecs.tx { update(e) { this[ScoreComponent] = Score(2) } }
        val raw = database.read {
            connection.prepareStatement("select value::text from ecs_component where entity = ? and rel = ?").use {
                it.setObject(1, e.id)
                it.setString(2, ScoreComponent.name)
                it.executeQuery().use { rs -> rs.next(); rs.getString(1) }
            }
        }
        assertEquals("{\"bonus\": 7, \"points\": 2}", raw)
        ecs.read { assertEquals(Score(2), get(e, ScoreComponent)) }
    }

    @Test
    fun `unchanged writes log no change`() {
        val e = ecs.tx { create { this[ScoreComponent] = Score(5) } }
        fun changes() = database.read {
            connection.prepareStatement("select count(*) from ecs_change where entity = ?").use {
                it.setObject(1, e.id)
                it.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }
        val before = changes()
        ecs.tx { update(e) { this[ScoreComponent] = Score(5); +Member; +Member } }
        assertEquals(before + 1, changes()) // only the tag
    }

    @Test
    fun `values are checked by JSON Schema in Postgres and by Kotlin rules before writing`() {
        val bad = assertFailsWith<SQLException> { ecs.tx { create { this[MailComponent] = Mail("not-an-address") } } }
        assertEquals("EC005", bad.sqlState)
        assertFailsWith<ComponentValidationException> { ecs.tx { create { this[MailComponent] = Mail("a b@c") } } }
    }

    @Test
    fun `declared unique indexes hold across entities`() {
        val address = "u${UUID.randomUUID()}@example.com"
        ecs.tx { create { this[MailComponent] = Mail(address) } }
        val dup = assertFailsWith<SQLException> { ecs.tx { create { this[MailComponent] = Mail(address.uppercase()) } } }
        assertEquals("23505", dup.sqlState)
    }

    @Test
    fun `queries run on the engine and see the transaction's own writes`() {
        val tag = "q${UUID.randomUUID()}"
        val n = ecs.tx {
            val a = create { +Member; this[MailComponent] = Mail("$tag@a") }
            create { set(ChildOf, a); this[ScoreComponent] = Score(10) }
            create { set(ChildOf, a); this[ScoreComponent] = Score(20) }
            query {
                val acc by entity
                val child by entity
                acc has Member
                acc has MailComponent.where { it.address == "$tag@a" }
                child childOf acc
                child has ScoreComponent.where { it.points > 15 }
                select { child[ScoreComponent].points }
            }.fetch()
        }
        assertEquals(listOf(20L), n)
        // outside a transaction a query opens its own read-only one
        val count = ecs.query {
            val acc by entity
            acc has MailComponent.where { it.address == "$tag@a" }
            select { acc }
        }.count()
        assertEquals(1, count)
        val none = ecs.read {
            query {
                val acc by entity
                acc has MailComponent.where { it.address == "$tag@nowhere" }
                select { acc }
            }.first()
        }
        assertNull(none)
        assertFailsWith<QueryResultException> {
            ecs.read {
                query {
                    val acc by entity
                    acc has MailComponent.where { it.address == "$tag@nowhere" }
                    select { acc }
                }.fetchOne()
            }
        }
    }

    @Test
    fun `a failed savepoint discards its buffered writes`() {
        val e = ecs.tx {
            val e = create { +Member }
            runCatching {
                savepoint {
                    update(e) { +Muted }
                    error("abandon")
                }
            }
            e
        }
        ecs.read {
            assertTrue(has(e, Member))
            assertFalse(has(e, Muted))
        }
    }

    @Test
    fun `nested transactions share the buffer and reads see it`() {
        val e = ecs.tx {
            val e = create { +Member }
            ecs.tx { update(e) { +Muted } }
            ecs.read { assertTrue(has(e, Muted)) }
            e
        }
        ecs.read { assertTrue(has(e, Muted)) }
    }

    @Test
    fun `unregistered types are refused after freeze`() {
        assertFailsWith<UnregisteredComponentException> { ecs.tx { create { +NotRegistered } } }
        assertFailsWith<IllegalStateException> { ecs.registry.register(NotRegistered) }
    }

    @Test
    fun `transactions need a frozen registry`() {
        Pgecs.on(database) { loadModules = false; engine = QueryEngineConfig(eqsatInstances = 0) }.use { fresh ->
            assertFailsWith<IllegalStateException> { fresh.tx { create() } }
        }
    }

    @Test
    fun `generated module registries are found through ServiceLoader`() {
        Pgecs.on(database) { engine = QueryEngineConfig(eqsatInstances = 0) }.use { found ->
            for (t in listOf(MailComponent, ScoreComponent, Member, Likes, Leads, NotRegistered, ChildOf)) {
                assertTrue(found.registry[t.name] === t, "${t.name} not registered")
            }
        }
    }

    @Test
    fun `hooks run in the transaction and tell adds from sets`() {
        Pgecs.on(database) {
            loadModules = false
            engine = QueryEngineConfig(eqsatInstances = 0)
            register(MailComponent, ScoreComponent, Member, Muted, Likes, Leads)
        }.use { hooked ->
            hooked.freeze()
            val log = ArrayList<String>()
            hooked.hooks.onAdd(Member) { e -> log += "add member"; update(e.entity) { this[ScoreComponent] = Score(0) } }
            hooked.hooks.onSet(ScoreComponent) { _, v -> log += "set ${v.points}" }
            hooked.hooks.onRemove(Member) { log += "remove member" }
            hooked.hooks.onRemove(Leads) { e -> log += "unlead ${e.target == null}" }
            hooked.hooks.onAdd(Leads) { log += "lead" }
            val (a, b) = hooked.tx { create() to create() }
            val e = hooked.tx { create { +Member; +Member } }
            hooked.tx { update(e) { this[ScoreComponent] = Score(5); set(Leads, a); set(Leads, b); -Member } }
            assertEquals(listOf("add member", "set 0", "set 5", "lead", "unlead false", "lead", "remove member"), log)
            hooked.read { assertEquals(Score(5), get(e, ScoreComponent)) }
            // a throwing hook rolls the transaction back
            hooked.hooks.onAdd(Muted) { error("no muting") }
            assertFailsWith<IllegalStateException> { hooked.tx { update(e) { +Muted; this[ScoreComponent] = Score(9) } } }
            hooked.read { assertEquals(Score(5), get(e, ScoreComponent)) }
        }
    }

    @Test
    fun `hot query shapes are recorded and pre-compiled at the next freeze`() {
        val kind = "test-${UUID.randomUUID()}"
        fun open() = Pgecs.on(database) {
            loadModules = false
            processKind = kind
            engine = QueryEngineConfig(eqsatInstances = 0)
            register(MailComponent, ScoreComponent, Member, Muted, Likes, Leads)
        }.also { it.freeze() }
        open().use { first ->
            first.query { val m by entity; m has Member; select { m } }.count()
        } // close records the shapes
        open().use { second ->
            val deadline = System.nanoTime() + 10_000_000_000
            while (second.engine.metrics().cachedPlans == 0L && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(second.engine.metrics().cachedPlans > 0, "nothing pre-compiled")
        }
    }

    @Test
    fun `ChildOf's handle and the core spec agree`() {
        assertEquals(CoreComponents.CHILD_OF, ChildOf.toSpec())
    }
}
