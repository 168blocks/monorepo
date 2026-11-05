package dev.vxcc.alexs168.pgecs.schema

import dev.vxcc.alexs168.pgecs.db.SqlStates
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FreezeTest {
    private val db = TestDb.db
    private val freeze = Freeze(db)

    private val emailV1 = """{"type":"object","properties":{"address":{"type":"string"}},"required":["address"],"additionalProperties":true}"""
    private val emailV1Plus = """{"type":"object","properties":{"address":{"type":"string"},"verifiedAt":{"type":"integer"}},"required":["address"],"additionalProperties":true}"""
    private val emailRetyped = """{"type":"object","properties":{"address":{"type":"integer"}},"required":["address"],"additionalProperties":true}"""

    private fun email(name: String, schema: String = emailV1, unique: Boolean = true) = ComponentSpec(
        name = name,
        kind = ComponentKind.VALUE,
        jsonSchema = schema,
        indexes = if (unique) listOf(IndexSpec("address", listOf("(lower(value->>'address'))"), unique = true)) else emptyList(),
    )

    private fun historyRows() = db.read { connection.long("select count(*) from ecs_schema_history") }

    private fun state(name: String) = db.read {
        connection.prepareStatement("select state from ecs_component_type where name = ?").use { st ->
            st.setString(1, name)
            st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
    }

    @Test
    fun `creates components with indexes, and a rerun changes nothing`() {
        val name = TestDb.unique("email")
        val first = freeze.run(listOf(email(name)))
        assertEquals(listOf(name), first.created)
        assertEquals(listOf(Freeze.indexName(name, IndexSpec("address", listOf("x"), unique = true))), first.indexesBuilt)
        assertEquals("active", first.catalog.getValue(name).state)
        assertEquals(ComponentIds.of(name), first.catalog.getValue(name).entity)

        db.tx { put(newEntity(), name, value = """{"address":"A@x"}""") }
        assertSqlState(SqlStates.UNIQUE_VIOLATION) { db.tx { put(newEntity(), name, value = """{"address":"a@X"}""") } }

        val history = historyRows()
        val again = freeze.run(listOf(email(name)))
        assertTrue(name in again.unchanged)
        assertTrue(again.created.isEmpty() && again.upgraded.isEmpty() && again.indexesBuilt.isEmpty())
        assertEquals(history, historyRows())
    }

    @Test
    fun `additive schema changes apply in place, others are refused`() {
        val name = TestDb.unique("email")
        freeze.run(listOf(email(name)))
        db.tx { put(newEntity(), name, value = """{"address":"b@x"}""") }

        val up = freeze.run(listOf(email(name, emailV1Plus)))
        assertEquals(listOf(name), up.upgraded)
        db.tx { put(newEntity(), name, value = """{"address":"c@x","verifiedAt":5}""") }
        assertSqlState(SqlStates.SCHEMA_VIOLATION) { db.tx { put(newEntity(), name, value = """{"address":"d@x","verifiedAt":"no"}""") } }

        // an older process (without verifiedAt) still starts and leaves the schema alone
        val old = freeze.run(listOf(email(name)))
        assertEquals(listOf(name), old.newerInDatabase)
        assertTrue(old.upgraded.isEmpty())

        val e = assertFailsWith<FreezeException> { freeze.run(listOf(email(name, emailRetyped))) }
        assertTrue(e.problems.single().contains("new version"), e.message)
        assertFailsWith<FreezeException> { freeze.run(listOf(ComponentSpec(name, ComponentKind.TAG))) }
    }

    @Test
    fun `a unique index over duplicate data stops freeze and names the entities`() {
        val name = TestDb.unique("email")
        freeze.run(listOf(email(name, unique = false)))
        val (a, b) = db.tx {
            val a = newEntity().also { put(it, name, value = """{"address":"same@x"}""") }
            val b = newEntity().also { put(it, name, value = """{"address":"SAME@x"}""") }
            a to b
        }
        val e = assertFailsWith<FreezeException> { freeze.run(listOf(email(name))) }
        assertTrue(a.toString() in e.message!! && b.toString() in e.message!!, e.message)
        val idx = Freeze.indexName(name, IndexSpec("address", listOf("x"), unique = true))
        db.read {
            assertEquals(0L, connection.long("select count(*) from pg_class where relname = ?", idx))
            assertEquals(1L, connection.long("select count(*) from ecs_schema_index where name = ? and state = 'failed'", idx))
        }
        // writes to the existing component continue; fix the data and rerun
        db.tx { connection.exec("delete from ecs_entity where id = ?", b) }
        val ok = freeze.run(listOf(email(name)))
        assertEquals(listOf(idx), ok.indexesBuilt)
    }

    @Test
    fun `new components stay pending until their indexes are valid`() {
        val base = TestDb.unique("dup")
        val rel = "$base:rel"
        // a relation that becomes exclusive over data with two targets per entity cannot be frozen
        freeze.run(listOf(ComponentSpec(rel, ComponentKind.RELATION)))
        db.tx { val e = newEntity(); put(e, rel, newEntity()); put(e, rel, newEntity()) }
        assertFailsWith<FreezeException> { freeze.run(listOf(ComponentSpec(rel, ComponentKind.RELATION, Traits(exclusive = true)))) }
        assertEquals("active", state(rel)) // existing components keep working
        db.read {
            assertEquals("{}", connection.prepareStatement("select traits::text from ecs_component_type where name = ?").use {
                it.setString(1, rel); it.executeQuery().use { rs -> rs.next(); rs.getString(1) }
            })
        }

        // a brand-new component together with a failing one: the new one is left pending
        val fresh = "$base:fresh"
        assertFailsWith<FreezeException> {
            freeze.run(listOf(ComponentSpec(rel, ComponentKind.RELATION, Traits(exclusive = true)), ComponentSpec(fresh, ComponentKind.TAG)))
        }
        assertEquals("pending", state(fresh))
        assertSqlState(SqlStates.COMPONENT_NOT_ACTIVE) { db.tx { put(newEntity(), fresh) } }

        // without the failing change, the rerun finishes the pending one
        val done = freeze.run(listOf(ComponentSpec(rel, ComponentKind.RELATION), ComponentSpec(fresh, ComponentKind.TAG)))
        assertEquals(listOf(fresh), done.upgraded)
        assertEquals("active", state(fresh))
    }

    @Test
    fun `validation rules are checked by Postgres, added ones against stored data, and are additive`() {
        val name = TestDb.unique("ruled")
        val noSpaces = RuleSpec("no spaces", "(strpos(value->>'address', ' ') = 0)")
        val short = RuleSpec("short", "(length(value->>'address') < 10)")
        freeze.run(listOf(email(name, unique = false).copy(rules = listOf(noSpaces))))
        db.tx { put(newEntity(), name, value = """{"address":"ok@x"}""") }
        assertSqlState(SqlStates.RULE_VIOLATION) { db.tx { put(newEntity(), name, value = """{"address":"a b@x"}""") } }

        // a new rule that stored data breaks stops freeze and names the entity
        val long = db.tx { newEntity().also { put(it, name, value = """{"address":"long-address@x"}""") } }
        val e = assertFailsWith<FreezeException> { freeze.run(listOf(email(name, unique = false).copy(rules = listOf(noSpaces, short)))) }
        assertTrue(long.toString() in e.message!! && "short" in e.message!!, e.message)
        db.tx { put(newEntity(), name, value = """{"address":"long-address-2@x"}""") } // the old rules still apply
        db.tx { connection.exec("delete from ecs_component where rel = ? and length(value->>'address') >= 10", name) }
        assertEquals(listOf(name), freeze.run(listOf(email(name, unique = false).copy(rules = listOf(noSpaces, short)))).upgraded)
        assertSqlState(SqlStates.RULE_VIOLATION) { db.tx { put(newEntity(), name, value = """{"address":"long-address@x"}""") } }

        // an older process (one rule) leaves both in place; changing a rule needs a new version
        assertEquals(listOf(name), freeze.run(listOf(email(name, unique = false).copy(rules = listOf(noSpaces)))).newerInDatabase)
        val changed = assertFailsWith<FreezeException> {
            freeze.run(listOf(email(name, unique = false).copy(rules = listOf(noSpaces, short.copy(sql = "(length(value->>'address') < 20)")))))
        }
        assertTrue("changes rule 'short'" in changed.message!!, changed.message)
    }

    @Test
    fun `traits added later are checked against data`() {
        val rel = TestDb.unique("later")
        freeze.run(listOf(ComponentSpec(rel, ComponentKind.RELATION)))
        db.tx { val a = newEntity(); val b = newEntity(); put(a, rel, b); put(b, rel, a) }
        val e = assertFailsWith<FreezeException> { freeze.run(listOf(ComponentSpec(rel, ComponentKind.RELATION, Traits(acyclic = true)))) }
        assertTrue("cycle" in e.message!!, e.message)
        // symmetric holds for that data
        assertEquals(listOf(rel), freeze.run(listOf(ComponentSpec(rel, ComponentKind.RELATION, Traits(symmetric = true)))).upgraded)
    }

    @Test
    fun `retire deletes the rows and blocks writes`() {
        val name = TestDb.unique("old")
        freeze.run(listOf(ComponentSpec(name, ComponentKind.TAG)))
        val e = db.tx { newEntity().also { put(it, name) } }
        val r = freeze.run(emptyList(), retire = setOf(name))
        assertEquals(listOf(name), r.retired)
        assertEquals("retired", r.catalog.getValue(name).state)
        db.read { assertTrue(exists(e) && !has(e, name)) }
        assertSqlState(SqlStates.COMPONENT_NOT_ACTIVE) { db.tx { put(e, name) } }
        assertFailsWith<FreezeException> { freeze.run(listOf(ComponentSpec(name, ComponentKind.TAG))) }
    }

    @Test
    fun `invalid specs are refused before touching the database`() {
        val problems = assertFailsWith<FreezeException> {
            freeze.run(
                listOf(
                    ComponentSpec("pgecs:mine", ComponentKind.TAG),
                    ComponentSpec("no-colon", ComponentKind.TAG),
                    ComponentSpec("test:v", ComponentKind.VALUE),
                    ComponentSpec("test:t", ComponentKind.TAG, Traits(exclusive = true)),
                    ComponentSpec("test:i", ComponentKind.TAG, indexes = listOf(IndexSpec("Bad Key", listOf("entity")))),
                ),
            )
        }.problems
        assertEquals(5, problems.size, problems.joinToString("\n"))
        val badSchema = TestDb.unique("bad-schema")
        assertFailsWith<FreezeException> { freeze.run(listOf(ComponentSpec(badSchema, ComponentKind.VALUE, jsonSchema = """{"type": 5}"""))) }
    }

    @Test
    fun `concurrent freezes serialize on the schema lock`() {
        val names = (1..3).map { TestDb.unique("conc") }
        val specs = names.map { email(it) }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val reports = (1..4).map { pool.submit(Callable { freeze.run(specs) }) }.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(names.toSet(), reports.flatMap { it.created + it.upgraded }.toSet())
            reports.forEach { r -> assertTrue(names.all { r.catalog.getValue(it).state == "active" }) }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `index names fit PostgreSQL's limit`() {
        val long = "b8:" + "x".repeat(80) + ":v1"
        val n = Freeze.indexName(long, IndexSpec("k", listOf("entity")))
        assertTrue(n.length <= 63 && n.startsWith("ecs_i_b8_xxx"), n)
        assertEquals(
            "create unique index concurrently if not exists ecs_u_b8_iam_email_v1__address on ecs_component using btree " +
                "((lower(value->>'address'))) where rel = 'b8:iam:email:v1'",
            Freeze.ddl("b8:iam:email:v1", IndexSpec("address", listOf("(lower(value->>'address'))"), unique = true)),
        )
    }

    @Test
    fun `component ids are UUIDv5`() {
        val id = ComponentIds.of("b8:iam:email:v1")
        assertEquals(5, id.version())
        assertEquals(id, ComponentIds.of("b8:iam:email:v1"))
        // RFC 9562 test vector: uuid5(DNS namespace, "www.example.com")
        assertEquals(
            "2ed6657d-e927-568b-95e1-2665a8aea6a2",
            ComponentIds.uuid5(java.util.UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), "www.example.com").toString(),
        )
    }
}
