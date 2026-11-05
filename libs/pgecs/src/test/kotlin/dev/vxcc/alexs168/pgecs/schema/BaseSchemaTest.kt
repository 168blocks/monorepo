package dev.vxcc.alexs168.pgecs.schema

import dev.vxcc.alexs168.pgecs.db.SqlStates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BaseSchemaTest {
    private val db = TestDb.db

    private val tag = TestDb.unique("tag")
    private val value = TestDb.unique("value")
    private val rel = TestDb.unique("rel")
    private val sym = TestDb.unique("sym")
    private val acyc = TestDb.unique("acyc")
    private val panic = TestDb.unique("panic")
    private val withValue = TestDb.unique("rel-with-value")

    init {
        Freeze(db).run(
            listOf(
                ComponentSpec(tag, ComponentKind.TAG),
                ComponentSpec(value, ComponentKind.VALUE, jsonSchema = """{"type":"object","properties":{"n":{"type":"integer"}},"required":["n"]}"""),
                ComponentSpec(rel, ComponentKind.RELATION),
                ComponentSpec(sym, ComponentKind.RELATION, Traits(symmetric = true)),
                ComponentSpec(acyc, ComponentKind.RELATION, Traits(acyclic = true)),
                ComponentSpec(panic, ComponentKind.RELATION, Traits(onDeleteTarget = Cleanup.PANIC)),
                ComponentSpec(withValue, ComponentKind.RELATION, jsonSchema = """{"type":"integer"}"""),
            ),
        )
    }

    @Test
    fun `setup is idempotent and makes the database serializable`() {
        assertEquals(SchemaSetup.LATEST, SchemaSetup.ensure(db))
        assertEquals(SchemaSetup.LATEST, SchemaSetup.ensure(db))
        db.openDedicatedConnection().use { c ->
            c.createStatement().use { st ->
                st.executeQuery("select setconfig from pg_db_role_setting s join pg_database d on d.oid = s.setdatabase where d.datname = current_database()")
                    .use { rs -> assertTrue(rs.next()); assertTrue("default_transaction_isolation=serializable" in rs.getString(1)) }
            }
        }
    }

    @Test
    fun `writes outside SERIALIZABLE are refused`() {
        db.openDedicatedConnection().use { c ->
            c.autoCommit = false
            c.createStatement().use { it.execute("set transaction isolation level read committed") }
            assertSqlState(SqlStates.NOT_SERIALIZABLE) { c.exec("insert into ecs_entity default values") }
            c.rollback()
            c.createStatement().use { it.execute("set transaction isolation level repeatable read") }
            assertSqlState(SqlStates.NOT_SERIALIZABLE) { c.exec("delete from ecs_component where false") }
            c.rollback()
        }
        assertSqlState(SqlStates.RESERVED_ENTITY) { db.tx { connection.exec("truncate ecs_component") } }
    }

    @Test
    fun `reserved entities cannot be deleted`() {
        assertSqlState(SqlStates.RESERVED_ENTITY) { db.tx { connection.exec("delete from ecs_entity where id = ?", NIL) } }
        assertSqlState(SqlStates.RESERVED_ENTITY) { db.tx { connection.exec("delete from ecs_entity where id = ?", ComponentIds.of(tag)) } }
        assertSqlState(SqlStates.RESERVED_ENTITY) { db.tx { connection.exec("update ecs_entity set id = gen_random_uuid() where id = ?", NIL) } }
        assertSqlState(SqlStates.RESERVED_ENTITY) { db.tx { put(NIL, tag) } }
    }

    @Test
    fun `the catalog is written only by freeze`() {
        assertSqlState(SqlStates.CATALOG_WRITE) { db.tx { connection.exec("update ecs_component_type set state = 'retired' where name = ?", tag) } }
    }

    @Test
    fun `kinds and schemas are enforced`() {
        db.tx {
            val e = newEntity()
            val t = newEntity()
            put(e, tag)
            put(e, value, value = """{"n": 1}""")
            put(e, rel, t)
            put(e, withValue, t, "5")
        }
        db.tx {
            val e = newEntity()
            val t = newEntity()
            assertSqlState(SqlStates.KIND_MISMATCH) { savepoint { put(e, tag, value = "1") } }
            assertSqlState(SqlStates.KIND_MISMATCH) { savepoint { put(e, tag, t) } }
            assertSqlState(SqlStates.KIND_MISMATCH) { savepoint { put(e, value) } }
            assertSqlState(SqlStates.KIND_MISMATCH) { savepoint { put(e, rel) } }
            assertSqlState(SqlStates.KIND_MISMATCH) { savepoint { put(e, rel, t, "1") } }
            assertSqlState(SqlStates.KIND_MISMATCH) { savepoint { put(e, withValue, t) } }
            assertSqlState(SqlStates.SCHEMA_VIOLATION) { savepoint { put(e, value, value = """{"n": "x"}""") } }
            assertSqlState(SqlStates.SCHEMA_VIOLATION) { savepoint { put(e, value, value = """{}""") } }
            assertSqlState(SqlStates.SCHEMA_VIOLATION) { savepoint { put(e, withValue, t, "\"s\"") } }
            put(e, value, value = """{"n": 2}""")
            assertSqlState(SqlStates.SCHEMA_VIOLATION) {
                savepoint { connection.exec("update ecs_component set value = '{\"n\": 1.5}' where entity = ? and rel = ?", e, value) }
            }
            assertSqlState(SqlStates.KIND_MISMATCH) {
                savepoint { connection.exec("update ecs_component set target = ? where entity = ? and rel = ?", t, e, value) }
            }
        }
    }

    @Test
    fun `component writes bump the entity version and fill the change log`() {
        val e = db.tx { newEntity().also { put(it, value, value = """{"n": 1}""") } }
        db.tx { connection.exec("update ecs_component set value = '{\"n\": 2}' where entity = ? and rel = ?", e, value) }
        db.tx { connection.exec("delete from ecs_component where entity = ? and rel = ?", e, value) }
        db.read {
            assertEquals(3L, connection.long("select version from ecs_entity where id = ?", e))
            val ops = connection.prepareStatement("select op, old_value->>'n' from ecs_change where entity = ? order by id").use { st ->
                st.setObject(1, e)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add("${rs.getString(1)}:${rs.getString(2)}") } }
            }
            assertEquals(listOf("insert:null", "update:1", "delete:2"), ops)
        }
    }

    @Test
    fun `child-of deletes children recursively and forbids cycles`() {
        val (root, child, grandchild) = db.tx {
            val r = newEntity()
            val c = newEntity()
            val g = newEntity()
            put(c, CoreComponents.CHILD_OF.name, r)
            put(g, CoreComponents.CHILD_OF.name, c)
            Triple(r, c, g)
        }
        assertSqlState(SqlStates.CYCLE) { db.tx { put(root, CoreComponents.CHILD_OF.name, grandchild) } }
        // exclusive: a second parent is refused by the unique index
        assertSqlState(SqlStates.UNIQUE_VIOLATION) { db.tx { put(child, CoreComponents.CHILD_OF.name, newEntity()) } }
        db.tx { connection.exec("delete from ecs_entity where id = ?", root) }
        db.read {
            assertFalse(exists(child))
            assertFalse(exists(grandchild))
        }
    }

    @Test
    fun `acyclic relations reject cycles`() {
        db.tx {
            val a = newEntity()
            val b = newEntity()
            val c = newEntity()
            put(a, acyc, b)
            put(b, acyc, c)
            assertSqlState(SqlStates.CYCLE) { savepoint { put(c, acyc, a) } }
            assertSqlState(SqlStates.CYCLE) { savepoint { put(a, acyc, a) } }
            put(a, acyc, c) // a diamond is fine
        }
    }

    @Test
    fun `symmetric relations are added and removed in both directions`() {
        val (a, b) = db.tx { newEntity() to newEntity() }
        db.tx { put(a, sym, b) }
        db.read { assertTrue(has(b, sym, a)) }
        db.tx { connection.exec("delete from ecs_component where entity = ? and rel = ? and target = ?", b, sym, a) }
        db.read {
            assertFalse(has(a, sym, b))
            assertFalse(has(b, sym, a))
        }
    }

    @Test
    fun `onDeleteTarget panic refuses deleting the target, remove removes the pair`() {
        val (a, b, c) = db.tx {
            val a = newEntity()
            val b = newEntity()
            val c = newEntity()
            put(a, panic, b)
            put(a, rel, c)
            Triple(a, b, c)
        }
        assertSqlState(SqlStates.DELETE_PANIC) { db.tx { connection.exec("delete from ecs_entity where id = ?", b) } }
        db.tx { connection.exec("delete from ecs_entity where id = ?", c) }
        db.read {
            assertTrue(exists(a))
            assertFalse(has(a, rel, c))
        }
    }
}
