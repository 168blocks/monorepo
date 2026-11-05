package net.blocks168.pgecs.schema

import net.blocks168.pgecs.db.Database
import net.blocks168.pgecs.db.SqlStates
import java.sql.Connection
import java.sql.SQLException

/**
 * Creates and upgrades pgecs' base schema (tables, triggers, database settings) from the versioned
 * scripts in `resources/net/blocks168/pgecs/schema/`. These are library-internal migrations, not
 * component changes: components only ever change through [Freeze].
 *
 * Every process may call [ensure] at startup. When the schema is current it only reads `ecs_meta`, so it
 * works with roles that may not change the schema (the edge proxies). Otherwise it takes the schema
 * advisory lock and applies the missing scripts, one `SERIALIZABLE` transaction each; that needs the
 * database owner (role `blocks` in deploy/compose.yaml).
 */
object SchemaSetup {
    /** Key of the session-level advisory lock that setup and freeze hold, so only one JVM runs them. */
    const val SCHEMA_LOCK_KEY: Long = 0x7067_6563_735f_7363L // "pgecs_sc"

    /** Base schema scripts, in order: version `i + 1` is `MIGRATIONS[i]`. */
    private val MIGRATIONS = listOf("V1__base.sql", "V2__systems.sql", "V3__outbox.sql", "V4__rules.sql")

    /** The base schema version this pgecs build creates. */
    val LATEST: Int get() = MIGRATIONS.size

    /** Brings the base schema of [db] to [LATEST] and returns the version found in the database afterwards. */
    fun ensure(db: Database): Int {
        val current = db.openDedicatedConnection().use(::version)
        if (current >= LATEST) return current
        return db.openDedicatedConnection().use { c ->
            withSchemaLock(c, db.config.timeouts.schemaLock) { migrate(c) }
        }
    }

    /** Version recorded in `ecs_meta`, 0 when pgecs was never set up in this database. */
    fun version(c: Connection): Int = c.createStatement().use { st ->
        val exists = st.executeQuery("select to_regclass('ecs_meta') is not null").use { it.next(); it.getBoolean(1) }
        if (!exists) return 0
        st.executeQuery("select value from ecs_meta where key = 'schema_version'").use { rs ->
            if (rs.next()) rs.getString(1).toInt() else 0
        }
    }

    /**
     * Runs [block] holding the schema advisory lock on [c] (a dedicated autocommit connection), waiting
     * at most [timeout] for it.
     */
    internal fun <T> withSchemaLock(c: Connection, timeout: java.time.Duration, block: () -> T): T {
        // Polled with pg_try_advisory_lock rather than waiting in pg_advisory_lock: a session blocked in
        // a statement holds a snapshot, and CREATE INDEX CONCURRENTLY in the lock holder waits for every
        // older snapshot, which would deadlock with the waiter.
        val deadline = System.nanoTime() + timeout.toNanos()
        var pause = 50L
        while (true) {
            val locked = c.createStatement().use { st ->
                st.executeQuery("select pg_try_advisory_lock($SCHEMA_LOCK_KEY)").use { it.next(); it.getBoolean(1) }
            }
            if (locked) break
            if (System.nanoTime() >= deadline) {
                throw SQLException("pgecs: another process held the schema lock for longer than $timeout", SqlStates.LOCK_NOT_AVAILABLE)
            }
            Thread.sleep(pause)
            pause = (pause * 2).coerceAtMost(1_000)
        }
        try {
            return block()
        } finally {
            if (!c.isClosed) {
                runCatching { c.createStatement().use { it.execute("select pg_advisory_unlock($SCHEMA_LOCK_KEY)") } }
            }
        }
    }

    /** Applies missing scripts on [c], which holds the schema lock. Returns the resulting version. */
    internal fun migrate(c: Connection): Int {
        c.createStatement().use { st ->
            val exists = st.executeQuery("select to_regclass('ecs_meta') is not null").use { it.next(); it.getBoolean(1) }
            if (!exists) st.execute("create table ecs_meta (key text primary key, value text not null)")
        }
        var current = version(c)
        while (current < LATEST) {
            val script = MIGRATIONS[current]
            val sql = SchemaSetup::class.java.getResourceAsStream(script)?.use { String(it.readAllBytes()) }
                ?: error("pgecs schema script $script is missing from the class path")
            val next = current + 1
            inSerializableTx(c) {
                c.createStatement().use { st ->
                    st.execute(sql)
                    st.execute(
                        "insert into ecs_meta (key, value) values ('schema_version', '$next') " +
                            "on conflict (key) do update set value = excluded.value",
                    )
                }
            }
            current = next
        }
        return current
    }

    /**
     * Runs [block] in a `SERIALIZABLE` transaction on the autocommit connection [c] (setup and freeze
     * work on their own connection, outside the pool), retrying on serialization failures.
     */
    internal fun <T> inSerializableTx(c: Connection, maxAttempts: Int = 5, block: () -> T): T {
        var attempt = 0
        while (true) {
            attempt++
            c.autoCommit = false
            try {
                c.createStatement().use { it.execute("set transaction isolation level serializable, read write") }
                val result = block()
                c.commit()
                return result
            } catch (t: Throwable) {
                runCatching { c.rollback() }
                if (!SqlStates.isRetryable(t) || attempt >= maxAttempts) throw t
                Thread.sleep((5L shl attempt).coerceAtMost(500))
            } finally {
                c.autoCommit = true
            }
        }
    }
}
