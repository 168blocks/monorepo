package dev.vxcc.alexs168.pgecs

import java.time.Duration

/**
 * Where pgecs finds its database and how it uses it.
 *
 * [jdbcUrl] is a PostgreSQL JDBC URL, e.g. `jdbc:postgresql://localhost:5432/blocks`. There are no
 * passwords: the server decides by where a connection comes from which [user] (role) it may use
 * (pg_hba.conf, see nix/postgres-entrypoint.sh).
 *
 * pgecs needs session-level features (advisory locks, `LISTEN`, server-side prepared statements), so
 * [jdbcUrl] must point at PostgreSQL directly or at a PgBouncer in session mode, never in transaction
 * pooling mode.
 */
data class PgEcsConfig(
    val jdbcUrl: String,
    val user: String,
    /** Shown in `pg_stat_activity.application_name`; name the process, e.g. `backend-1`. */
    val applicationName: String = "pgecs",
    val pool: Pool = Pool(),
    val timeouts: Timeouts = Timeouts(),
    val retry: Retry = Retry(),
) {
    /**
     * The connection pool (HikariCP) behind `tx` and `read`. Each transaction borrows one connection
     * for its whole duration. Size it against the server's `max_connections` (docs/pgecs.md,
     * "Connection budget"): every process also opens one dedicated connection for locks and, briefly
     * at startup, one for freeze.
     */
    data class Pool(
        val maxSize: Int = 4,
        /** Idle connections kept open; `null` keeps [maxSize] (a fixed-size pool, HikariCP's advice). */
        val minIdle: Int? = null,
        /** Connections are replaced after this long; keep it below any server or network idle cutoff. */
        val maxLifetime: Duration = Duration.ofMinutes(30),
        val idleTimeout: Duration = Duration.ofMinutes(10),
        /** Idle connections are pinged this often, so dead ones are noticed before a transaction gets them. */
        val keepalive: Duration = Duration.ofMinutes(2),
        /**
         * pgjdbc switches a statement to a server-side prepared statement after it ran this many times
         * on one connection. The query cache relies on that; 0 disables server-side preparation.
         */
        val prepareThreshold: Int = 5,
        /** pgjdbc's per-connection cache of prepared statements: entries ... */
        val preparedStatementCacheQueries: Int = 256,
        /** ... and size in MiB. */
        val preparedStatementCacheSizeMiB: Int = 5,
    ) {
        init {
            require(maxSize >= 1) { "pool maxSize must be at least 1" }
            require(minIdle == null || minIdle in 0..maxSize) { "pool minIdle must be in 0..maxSize" }
        }
    }

    /**
     * Timeouts. Statement, lock and idle-in-transaction timeouts are PostgreSQL session settings, sent
     * when a connection opens, so they also hold for SQL that other modules run on pgecs connections.
     * A statement or lock timeout fails the statement with SQLSTATE 57014 or 55P03, which aborts the
     * transaction (it is not retried). [Duration.ZERO] disables a timeout.
     */
    data class Timeouts(
        /** TCP connect and authentication. */
        val connect: Duration = Duration.ofSeconds(10),
        /** Waiting for a free pooled connection before `tx` / `read` fail. */
        val acquire: Duration = Duration.ofSeconds(30),
        /** PostgreSQL `statement_timeout` for pooled connections. */
        val statement: Duration = Duration.ofSeconds(30),
        /** PostgreSQL `lock_timeout`: waiting for a row or table lock. */
        val lock: Duration = Duration.ofSeconds(10),
        /**
         * PostgreSQL `idle_in_transaction_session_timeout`: the server ends a session whose
         * transaction sits idle this long (a stuck caller), releasing its locks.
         */
        val idleInTransaction: Duration = Duration.ofSeconds(60),
        /**
         * Waiting for the schema lock in setup and freeze, which another process may hold while it
         * builds indexes.
         */
        val schemaLock: Duration = Duration.ofMinutes(10),
    ) {
        /**
         * Client-side socket read timeout, a last resort when the server or network hangs: [statement]
         * plus a margin, so the server always gives up first. `null` when statements are unbounded.
         */
        val socket: Duration?
            get() = if (statement.isZero) null else statement.plus(Duration.ofSeconds(30))
    }

    /**
     * Retries of transactions that failed with a serialization failure (SQLSTATE 40001) or a deadlock
     * (40P01). Attempt `n` (from 1) waits a random time in `[0, min(maxBackoff, baseBackoff * 2^(n-1))]`
     * before attempt `n + 1` ("full jitter").
     */
    data class Retry(
        val maxAttempts: Int = 5,
        val baseBackoff: Duration = Duration.ofMillis(5),
        val maxBackoff: Duration = Duration.ofMillis(500),
    ) {
        init {
            require(maxAttempts >= 1) { "retry maxAttempts must be at least 1" }
        }
    }

    companion object {
        /**
         * Reads `PGECS_URL` and `PGECS_USER` (or the given [env] map), as set by deploy/compose.yaml.
         * Optional: `PGECS_POOL_SIZE`, `PGECS_APPLICATION_NAME`.
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): PgEcsConfig = PgEcsConfig(
            jdbcUrl = env["PGECS_URL"] ?: error("PGECS_URL is not set"),
            user = env["PGECS_USER"] ?: error("PGECS_USER is not set"),
            applicationName = env["PGECS_APPLICATION_NAME"] ?: "pgecs",
            pool = env["PGECS_POOL_SIZE"]?.let { Pool(maxSize = it.toInt()) } ?: Pool(),
        )
    }
}
