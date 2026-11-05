package net.blocks168.velocity.network

import net.blocks168.pgecs.PgEcs
import net.blocks168.pgecs.PgEcsConfig
import java.sql.Connection

/**
 * The network plugin's tables (schema `network`), as plain SQL migrations in
 * `resources/net/blocks168/velocity/network/migrations`. Kept apart from the plugin's logic
 * ([NetworkStore] holds all queries) so it can move to pgecs once that has entities and components.
 *
 * Edges connect as `blocks_edge`, which may not create tables: the migrations run as the database owner
 * (`blocks`) from the core, via [main] (deploy/compose.yaml: service `network-schema`).
 */
object NetworkSchema {
    /** In order; never edit an applied one, add a new one */
    val MIGRATIONS = listOf("V1__network.sql")

    /** Latest version, which the plugin needs */
    val VERSION get() = MIGRATIONS.size

    /** Applies the missing migrations; returns how many. Safe to run concurrently (advisory lock). */
    fun migrate(connection: Connection): Int {
        val auto = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.createStatement().use { st ->
                st.execute("select pg_advisory_xact_lock(hashtext('network-schema'))")
                st.execute("create schema if not exists network")
                st.execute(
                    "create table if not exists network.migrations (version integer primary key, name text not null, " +
                        "applied_at timestamptz not null default now())",
                )
            }
            val applied = connection.createStatement().use { st ->
                st.executeQuery("select coalesce(max(version), 0) from network.migrations").use { it.next(); it.getInt(1) }
            }
            var count = 0
            for ((i, name) in MIGRATIONS.withIndex()) {
                val version = i + 1
                if (version <= applied) continue
                val sql = NetworkSchema::class.java.getResource("migrations/$name")?.readText()
                    ?: error("migration $name missing from the jar")
                connection.createStatement().use { it.execute(sql) }
                connection.prepareStatement("insert into network.migrations (version, name) values (?, ?)").use {
                    it.setInt(1, version); it.setString(2, name); it.executeUpdate()
                }
                count++
            }
            connection.commit()
            return count
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = auto
        }
    }

    /** Applied version (0: none), readable by the edges */
    fun version(connection: Connection): Int = connection.createStatement().use { st ->
        st.executeQuery("select coalesce(max(version), 0) from network.migrations").use { it.next(); it.getInt(1) }
    }
}

/** Runs the migrations against PGECS_URL as PGECS_USER (the database owner), then exits. */
fun main() {
    PgEcs.connectWithRetry(PgEcsConfig.fromEnv(), timeoutMillis = 120_000).use { ecs ->
        val n = ecs.transaction { NetworkSchema.migrate(it) }
        println("network schema: applied $n migration(s), now at version ${NetworkSchema.VERSION}")
    }
}
