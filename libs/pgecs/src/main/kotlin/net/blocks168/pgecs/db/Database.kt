package net.blocks168.pgecs.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.blocks168.pgecs.PgEcsConfig
import org.postgresql.PGConnection
import org.postgresql.core.BaseConnection
import org.postgresql.core.TransactionState
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * pgecs' handle on one PostgreSQL database: a connection pool and the transaction runner.
 *
 * Every transaction is `SERIALIZABLE`. [tx] runs a read-write transaction, [read] a read-only one.
 * When PostgreSQL reports a serialization failure (40001) or deadlock (40P01), the transaction is
 * rolled back and its block runs again after a jittered backoff, up to [PgEcsConfig.Retry.maxAttempts]
 * times. A block may therefore run more than once and must only touch the database: side effects go
 * through the outbox.
 *
 * Each transaction borrows its own pooled connection, so the [Database] is safe to share between
 * threads and coroutines. Nested `tx` / `read` calls on the same thread or coroutine join the outer
 * transaction (a `tx` inside a `read` throws).
 *
 * Forms:
 * - blocking [tx] / [read]: on the caller's thread; meant for backend code and virtual threads. They
 *   throw on threads registered in [GameThreads].
 * - `suspend` [txSuspend] / [readSuspend]: on a dispatcher limited to the pool size. Cancelling the
 *   coroutine cancels the running statement and rolls back.
 * - [txAsync] / [readAsync]: return a [CompletableFuture] completed on a pgecs virtual thread; for game
 *   threads and Java callers.
 */
class Database private constructor(
    val config: PgEcsConfig,
    /** The server this database was connected to, as checked by [connect]. */
    val server: ServerInfo,
    private val pool: HikariDataSource,
) : AutoCloseable {

    /** What [connect] found on the server. */
    data class ServerInfo(val version: String, val versionNum: Int, val database: String, val user: String)

    /** Transaction counters, for metrics. */
    val metrics = TxMetrics()

    private val asyncExecutor: ExecutorService =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("pgecs-tx-", 0).factory())

    /** JDBC blocks a thread per running transaction; never more than the pool can serve. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(config.pool.maxSize)

    val isClosed: Boolean get() = pool.isClosed

    // ---------------------------------------------------------------- blocking

    /** Runs [block] in a read-write `SERIALIZABLE` transaction and commits; retried on conflicts. */
    @JvmOverloads
    fun <T> tx(name: String? = null, block: TxScope.() -> T): T = runBlocking(Mode.WRITE, name, block)

    /** Runs [block] in a `SERIALIZABLE READ ONLY` transaction; retried on conflicts. */
    @JvmOverloads
    fun <T> read(name: String? = null, block: TxScope.() -> T): T = runBlocking(Mode.READ, name, block)

    /**
     * Runs [block] in a `SERIALIZABLE READ ONLY DEFERRABLE` transaction: it first waits for a safe
     * snapshot, then can never fail with a serialization failure. For long reports, not for hot paths.
     */
    @JvmOverloads
    fun <T> readDeferrable(name: String? = null, block: TxScope.() -> T): T =
        runBlocking(Mode.READ_DEFERRABLE, name, block)

    // ---------------------------------------------------------------- suspend

    /** [tx] for coroutines. */
    suspend fun <T> txSuspend(name: String? = null, block: suspend TxScope.() -> T): T =
        runSuspend(Mode.WRITE, name, block)

    /** [read] for coroutines. */
    suspend fun <T> readSuspend(name: String? = null, block: suspend TxScope.() -> T): T =
        runSuspend(Mode.READ, name, block)

    // ---------------------------------------------------------------- async

    /**
     * [tx] on a pgecs virtual thread. Never blocks the caller, so it is safe on game threads; apply the
     * result back on the game thread (`server.execute { }`). It does not join a transaction of the caller.
     */
    @JvmOverloads
    fun <T> txAsync(name: String? = null, block: TxScope.() -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync({ runBlocking(Mode.WRITE, name, block, async = true) }, asyncExecutor)

    /** [read] on a pgecs virtual thread; see [txAsync]. */
    @JvmOverloads
    fun <T> readAsync(name: String? = null, block: TxScope.() -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync({ runBlocking(Mode.READ, name, block, async = true) }, asyncExecutor)

    // ---------------------------------------------------------------- other

    /** A round trip to the server through the pool. */
    fun ping(): ServerInfo = read("pgecs:ping") { queryServerInfo(connection) }

    /**
     * Opens a connection outside the pool, in autocommit mode and without statement timeout, for
     * session-level work: advisory locks, `LISTEN`, `CREATE INDEX CONCURRENTLY`. The caller closes it.
     * Count it in the connection budget.
     */
    fun openDedicatedConnection(): Connection =
        dataSource(config, pooled = false).connection.apply { autoCommit = true }

    override fun close() {
        asyncExecutor.shutdown()
        pool.close()
    }

    override fun toString(): String = "Database(${config.jdbcUrl}, user=${config.user})"

    // ---------------------------------------------------------------- runner

    private enum class Mode(val begin: String, val readOnly: Boolean) {
        WRITE("set transaction isolation level serializable, read write", false),
        READ("set transaction isolation level serializable, read only, not deferrable", true),
        READ_DEFERRABLE("set transaction isolation level serializable, read only, deferrable", true),
    }

    /** Thrown inside an attempt when the block swallowed a retryable failure. */
    private class RetryNeeded(cause: Throwable) : RuntimeException("transaction must be retried", cause)

    private fun outerScope(mode: Mode): TxScopeImpl? {
        val outer = TxScopeImpl.CURRENT.get() ?: return null
        check(outer.database === this) { "a transaction of another Database is running on this thread" }
        check(!outer.readOnly || mode.readOnly) { "tx { } inside read { }: the outer transaction is read-only" }
        outer.checkUsable()
        return outer
    }

    private fun <T> runBlocking(mode: Mode, name: String?, block: TxScope.() -> T, async: Boolean = false): T {
        if (!async) outerScope(mode)?.let { return it.block() }
        check(!GameThreads.isGameThread()) {
            "blocking pgecs transaction on game thread ${Thread.currentThread().name}; use txAsync / readAsync"
        }
        check(!isClosed) { "database is closed" }
        var attempt = 0
        while (true) {
            attempt++
            try {
                return attemptBlocking(mode, name, attempt, block)
            } catch (t: Throwable) {
                if (!retryAfter(t, attempt, name)) throw unwrap(t)
                Thread.sleep(backoff(attempt))
            }
        }
    }

    private fun <T> attemptBlocking(mode: Mode, name: String?, attempt: Int, block: TxScope.() -> T): T {
        metrics.attempt(name)
        pool.connection.use { c ->
            val scope = TxScopeImpl(this, c, mode.readOnly, attempt, owner = Thread.currentThread())
            var done = false
            try {
                begin(c, mode)
                val previous = TxScopeImpl.CURRENT.get()
                TxScopeImpl.CURRENT.set(scope)
                val result = try {
                    scope.block()
                } finally {
                    TxScopeImpl.CURRENT.set(previous)
                }
                finish(c, scope)
                done = true
                return result
            } finally {
                scope.end()
                if (!done) rollbackQuietly(c)
            }
        }
    }

    private suspend fun <T> runSuspend(mode: Mode, name: String?, block: suspend TxScope.() -> T): T {
        val outer = currentCoroutineContext()[TxScope]?.scope as TxScopeImpl? ?: TxScopeImpl.CURRENT.get()
        if (outer != null) {
            check(outer.database === this) { "a transaction of another Database is running in this coroutine" }
            check(!outer.readOnly || mode.readOnly) { "tx { } inside read { }: the outer transaction is read-only" }
            outer.checkUsable()
            return outer.block()
        }
        check(!isClosed) { "database is closed" }
        var attempt = 0
        while (true) {
            attempt++
            try {
                return withContext(dispatcher) { attemptSuspend(mode, name, attempt, block) }
            } catch (t: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!retryAfter(t, attempt, name)) throw unwrap(t)
                delay(backoff(attempt))
            }
        }
    }

    private suspend fun <T> attemptSuspend(mode: Mode, name: String?, attempt: Int, block: suspend TxScope.() -> T): T {
        metrics.attempt(name)
        val c = pool.connection
        val scope = TxScopeImpl(this, c, mode.readOnly, attempt, owner = null)
        var done = false
        val cancelled = AtomicBoolean(false)
        try {
            begin(c, mode)
            val result = coroutineScope {
                val running = AtomicBoolean(true)
                // On cancellation while the block runs, cancel its statement on the server: JDBC
                // calls do not react to coroutine cancellation themselves.
                val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (running.get()) {
                            cancelled.set(true)
                            runCatching { c.unwrap(PGConnection::class.java).cancelQuery() }
                        }
                    }
                }
                try {
                    withContext(TxScopeElement(scope) + TxScopeImpl.CURRENT.asContextElement(scope)) { scope.block() }
                } finally {
                    running.set(false)
                    watcher.cancel()
                }
            }
            finish(c, scope)
            done = true
            return result
        } finally {
            scope.end()
            if (!done) rollbackQuietly(c)
            // a cancel request may still be in flight; never hand that connection to someone else
            if (cancelled.get()) pool.evictConnection(c)
            c.close()
        }
    }

    private fun begin(c: Connection, mode: Mode) {
        c.createStatement().use { it.execute(mode.begin) }
    }

    private fun finish(c: Connection, scope: TxScopeImpl) {
        scope.retryCause?.let { throw RetryNeeded(it) }
        scope.end()
        // PostgreSQL answers COMMIT of an aborted transaction with a silent ROLLBACK; never report that as success
        if (c.unwrap(BaseConnection::class.java).transactionState == TransactionState.FAILED) {
            throw IllegalStateException(
                "the transaction was aborted by an SQL error that its block caught; use savepoint { } to continue after errors",
                scope.lastFailure,
            )
        }
        try {
            c.commit()
        } catch (e: SQLException) {
            if (SqlStates.isConnectionFailure(e)) throw TransactionOutcomeUnknownException(e)
            throw e
        }
    }

    private fun rollbackQuietly(c: Connection) {
        try {
            if (!c.isClosed) c.rollback()
        } catch (_: SQLException) {
            // the pool discards broken connections
        }
    }

    private fun retryAfter(t: Throwable, attempt: Int, name: String?): Boolean {
        if (t is TransactionOutcomeUnknownException || !SqlStates.isRetryable(t)) {
            metrics.failure(name)
            return false
        }
        if (attempt >= config.retry.maxAttempts) {
            metrics.exhausted(name)
            return false
        }
        metrics.retry(name)
        return true
    }

    private fun unwrap(t: Throwable): Throwable = if (t is RetryNeeded) t.cause ?: t else t

    private fun backoff(attempt: Int): Long {
        val r = config.retry
        val cap = minOf(r.maxBackoff.toMillis(), r.baseBackoff.toMillis() shl minOf(attempt - 1, 20))
        return if (cap <= 0) 0 else ThreadLocalRandom.current().nextLong(cap + 1)
    }

    companion object {
        /** Oldest supported server: PostgreSQL 18 (native `uuidv7()`). */
        const val MIN_SERVER_VERSION_NUM = 180000

        /** Extensions pgecs needs in its database. */
        val REQUIRED_EXTENSIONS = listOf("pg_jsonschema")

        /**
         * Connects to [config]'s database: checks the server (PostgreSQL 18 or newer, with the
         * [REQUIRED_EXTENSIONS], creating them when missing and allowed), then opens the pool.
         * Throws [SQLException] when the server cannot be reached or does not qualify.
         */
        @JvmStatic
        fun connect(config: PgEcsConfig): Database {
            val server = dataSource(config, pooled = false).connection.use { c ->
                val info = queryServerInfo(c)
                if (info.versionNum < MIN_SERVER_VERSION_NUM) {
                    throw SQLException("pgecs needs PostgreSQL 18 or newer, the server runs ${info.version}")
                }
                ensureExtensions(c)
                info
            }
            val hikari = HikariConfig().apply {
                dataSource = dataSource(config, pooled = true)
                poolName = "pgecs-${config.applicationName}"
                maximumPoolSize = config.pool.maxSize
                minimumIdle = config.pool.minIdle ?: config.pool.maxSize
                connectionTimeout = config.timeouts.acquire.toMillis().coerceAtLeast(250)
                validationTimeout = minOf(5_000L, connectionTimeout)
                maxLifetime = config.pool.maxLifetime.toMillis()
                idleTimeout = config.pool.idleTimeout.toMillis()
                keepaliveTime = config.pool.keepalive.toMillis()
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_SERIALIZABLE"
                // the server was checked above; do not fail construction if it blips right now
                initializationFailTimeout = -1
            }
            return Database(config, server, HikariDataSource(hikari))
        }

        /**
         * [connect], retrying for up to [timeout] while the server is not reachable yet (e.g. while the
         * database container starts). Other failures (no such role, old server) are final.
         */
        @JvmStatic
        @JvmOverloads
        fun connectWithRetry(
            config: PgEcsConfig,
            timeout: Duration = Duration.ofSeconds(60),
            interval: Duration = Duration.ofSeconds(1),
        ): Database {
            val deadline = System.nanoTime() + timeout.toNanos()
            while (true) {
                try {
                    return connect(config)
                } catch (e: SQLException) {
                    // 08xxx: connection exceptions; 57P03: the server is starting up
                    val transient = SqlStates.isConnectionFailure(e) || e.sqlState == "57P03"
                    if (!transient || System.nanoTime() >= deadline) throw e
                    Thread.sleep(interval.toMillis())
                }
            }
        }

        internal fun queryServerInfo(c: Connection): ServerInfo = c.createStatement().use { st ->
            st.executeQuery(
                "select current_setting('server_version'), current_setting('server_version_num')::int, " +
                    "current_database(), current_user",
            ).use { rs ->
                check(rs.next()) { "empty result from server info" }
                ServerInfo(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4))
            }
        }

        private fun ensureExtensions(c: Connection) {
            c.createStatement().use { st ->
                for (ext in REQUIRED_EXTENSIONS) {
                    val installed = st.executeQuery("select 1 from pg_extension where extname = '$ext'").use { it.next() }
                    if (installed) continue
                    val available = st.executeQuery("select 1 from pg_available_extensions where name = '$ext'")
                        .use { it.next() }
                    if (!available) {
                        throw SQLException(
                            "PostgreSQL extension $ext is not installed on the server; pgecs requires it " +
                                "(the Nix dev shell and deploy/compose.yaml provide a PostgreSQL with it)",
                        )
                    }
                    // needs a privileged role; deployments create it up front (nix/postgres-entrypoint.sh)
                    st.execute("create extension $ext")
                }
            }
        }

        /**
         * A pgjdbc data source for [config]. Created directly instead of through `DriverManager`: in
         * Fabric and Velocity the driver is not on the system class path, so the service loader would
         * not find it.
         */
        private fun dataSource(config: PgEcsConfig, pooled: Boolean): PGSimpleDataSource {
            org.postgresql.Driver.parseURL(config.jdbcUrl, null)
                ?: throw SQLException("not a PostgreSQL JDBC URL: ${config.jdbcUrl}", "08001")
            val t = config.timeouts
            return PGSimpleDataSource().apply {
                setURL(config.jdbcUrl)
                user = config.user
                applicationName = config.applicationName
                connectTimeout = t.connect.toSecondsCeil()
                loginTimeout = t.connect.toSecondsCeil()
                tcpKeepAlive = true
                prepareThreshold = config.pool.prepareThreshold
                preparedStatementCacheQueries = config.pool.preparedStatementCacheQueries
                preparedStatementCacheSizeMiB = config.pool.preparedStatementCacheSizeMiB
                val settings = buildList {
                    add("default_transaction_isolation=serializable")
                    if (pooled) {
                        add("statement_timeout=${t.statement.toMillis()}")
                        add("lock_timeout=${t.lock.toMillis()}")
                        add("idle_in_transaction_session_timeout=${t.idleInTransaction.toMillis()}")
                    } else {
                        add("statement_timeout=0")
                        add("idle_in_transaction_session_timeout=0")
                    }
                }
                options = settings.joinToString(" ") { "-c $it" }
                socketTimeout = if (pooled) t.socket?.toSecondsCeil() ?: 0 else 0
            }
        }

        private fun Duration.toSecondsCeil(): Int = ((toMillis() + 999) / 1000).toInt()
    }
}

/**
 * The connection failed while committing: the transaction may or may not have committed. pgecs never
 * retries this; the caller decides (an idempotent write can simply be repeated).
 */
class TransactionOutcomeUnknownException(cause: SQLException) :
    SQLException("connection lost during commit; the transaction may or may not have committed", cause.sqlState, cause)
