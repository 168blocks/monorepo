package net.blocks168.pgecs.query

import net.blocks168.pgecs.testing.TestPostgres
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * A connection to the test PostgreSQL ([TestPostgres]) with the pgecs tables in a schema of its own,
 * so test classes never see each other's rows. The tables are the ones of docs/pgecs.md, without the
 * triggers (the connection layer owns those; the query engine only reads).
 */
class TestDb(name: String) : AutoCloseable {
    val schema = "q_" + name.lowercase().replace(Regex("[^a-z0-9]"), "_") + "_" + UUID.randomUUID().toString().take(8)
    val connection: Connection = DriverManager.getConnection(
        TestPostgres.config.jdbcUrl,
        java.util.Properties().apply {
            setProperty("user", TestPostgres.config.user)
        },
    )

    init {
        exec("create schema $schema")
        exec("set search_path = $schema")
        exec(
            """
            create table ecs_entity (
              id uuid primary key default uuidv7(),
              created_at timestamptz not null default now(),
              version bigint not null default 0
            );
            insert into ecs_entity (id) values ('$NIL');
            create table ecs_component_type (
              name text primary key,
              entity uuid not null unique references ecs_entity(id),
              kind text not null,
              state text not null default 'active'
            );
            create table ecs_component (
              entity uuid not null references ecs_entity(id) on delete cascade,
              rel text not null references ecs_component_type(name),
              target uuid not null default '$NIL' references ecs_entity(id) on delete cascade,
              value jsonb null,
              updated_at timestamptz not null default now(),
              primary key (entity, rel, target)
            );
            create index ecs_c_rel on ecs_component (rel, target, entity);
            create index ecs_c_target on ecs_component (target, rel, entity) where target <> '$NIL';
            ${HotShapes.SCHEMA};
            """.trimIndent(),
        )
    }

    fun exec(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    /** Registers component type [name] (its entity is random here, UUIDv5 in pgecs). */
    fun type(name: String, kind: String = "tag"): UUID {
        val id = entity()
        connection.prepareStatement("insert into ecs_component_type (name, entity, kind) values (?, ?, ?)").use {
            it.setString(1, name)
            it.setObject(2, id)
            it.setString(3, kind)
            it.executeUpdate()
        }
        return id
    }

    fun entity(): UUID = UUID.randomUUID().also { id ->
        connection.prepareStatement("insert into ecs_entity (id) values (?)").use {
            it.setObject(1, id)
            it.executeUpdate()
        }
    }

    /** Adds component [rel] to [entity], with [target] for a pair and [json] for a value. */
    fun add(entity: UUID, rel: String, target: UUID? = null, json: String? = null) {
        connection.prepareStatement("insert into ecs_component (entity, rel, target, value) values (?, ?, ?, ?::jsonb)").use {
            it.setObject(1, entity)
            it.setString(2, rel)
            it.setObject(3, target ?: NIL)
            it.setString(4, json)
            it.executeUpdate()
        }
    }

    override fun close() {
        runCatching { exec("drop schema $schema cascade") }
        connection.close()
    }

    companion object {
        val NIL: UUID = UUID(0, 0)
    }
}
