package net.blocks168.pgecs.schema

import net.blocks168.pgecs.db.Database
import net.blocks168.pgecs.db.TxScope
import net.blocks168.pgecs.testing.TestPostgres
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** One database per test JVM with the base schema and the core components. */
internal object TestDb {
    val db: Database by lazy {
        Database.connect(TestPostgres.config).also { Freeze(it).run(emptyList()) }
    }

    /** A component name no other test uses. */
    fun unique(base: String) = "test:$base:${UUID.randomUUID().toString().take(8)}"
}

internal val NIL: UUID = UUID(0, 0)

internal fun Connection.exec(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { st ->
    args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
    st.execute()
    st.updateCount
}

internal fun Connection.long(sql: String, vararg args: Any?): Long = prepareStatement(sql).use { st ->
    args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
    st.executeQuery().use { rs -> check(rs.next()); rs.getLong(1) }
}

internal fun TxScope.newEntity(): UUID {
    val id = UUID.randomUUID()
    connection.exec("insert into ecs_entity (id) values (?)", id)
    return id
}

internal fun TxScope.put(entity: UUID, rel: String, target: UUID = NIL, value: String? = null) {
    connection.exec("insert into ecs_component (entity, rel, target, value) values (?, ?, ?, ?::jsonb)", entity, rel, target, value)
}

internal fun TxScope.has(entity: UUID, rel: String, target: UUID = NIL): Boolean =
    connection.long("select count(*) from ecs_component where entity = ? and rel = ? and target = ?", entity, rel, target) > 0

internal fun TxScope.exists(entity: UUID): Boolean = connection.long("select count(*) from ecs_entity where id = ?", entity) > 0

internal fun assertSqlState(state: String, block: () -> Unit) {
    val e = assertFailsWith<SQLException> { block() }
    assertEquals(state, e.sqlState, e.message)
}
