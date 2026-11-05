package net.blocks168.pgecs.db

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.blocks168.pgecs.PgEcsConfig
import net.blocks168.pgecs.testing.TestPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseTest {
    private lateinit var db: Database

    @BeforeAll
    fun connect() {
        db = Database.connect(TestPostgres.config.copy(pool = PgEcsConfig.Pool(maxSize = 6)))
        db.tx {
            connection.createStatement().use {
                it.execute("create table if not exists t_db (k int primary key, v int not null)")
                it.execute("truncate t_db")
            }
        }
    }

    @AfterAll
    fun close() = db.close()

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.int(sql: String): Int = createStatement().use { st ->
        st.executeQuery(sql).use { rs -> check(rs.next()); rs.getInt(1) }
    }

    private fun Connection.str(sql: String): String = createStatement().use { st ->
        st.executeQuery(sql).use { rs -> check(rs.next()); rs.getString(1) }
    }

    @Test
    fun `connects to PostgreSQL 18 and pings`() {
        assertTrue(db.server.versionNum >= Database.MIN_SERVER_VERSION_NUM)
        assertEquals(TestPostgres.DATABASE, db.ping().database)
    }

    @Test
    fun `transactions are serializable, read is read-only`() {
        assertEquals("serializable", db.tx { connection.str("show transaction_isolation") })
        assertEquals("off", db.tx { connection.str("show transaction_read_only") })
        assertEquals("serializable", db.read { connection.str("show transaction_isolation") })
        assertEquals("on", db.read { connection.str("show transaction_read_only") })
        assertEquals("on", db.readDeferrable { connection.str("show transaction_deferrable") })
        val e = assertFailsWith<SQLException> { db.read { connection.exec("insert into t_db values (-1, 0)") } }
        assertEquals("25006", e.sqlState)
    }

    @Test
    fun `session timeouts come from the config`() {
        val (stmt, lock, idle) = db.read {
            Triple(
                connection.str("show statement_timeout"),
                connection.str("show lock_timeout"),
                connection.str("show idle_in_transaction_session_timeout"),
            )
        }
        assertEquals("30s", stmt)
        assertEquals("10s", lock)
        assertEquals("1min", idle)
        Database.connect(TestPostgres.config.copy(timeouts = PgEcsConfig.Timeouts(statement = Duration.ofMillis(200)))).use { short ->
            val e = assertFailsWith<SQLException> { short.read { connection.exec("select pg_sleep(5)") } }
            assertEquals(SqlStates.QUERY_CANCELED, e.sqlState)
        }
    }

    @Test
    fun `commits on success and rolls back on failure`() {
        db.tx { connection.exec("insert into t_db values (1, 1)") }
        assertFailsWith<IllegalStateException> {
            db.tx {
                connection.exec("insert into t_db values (2, 2)")
                error("abort")
            }
        }
        assertEquals(1, db.read { connection.int("select count(*) from t_db where k in (1, 2)") })
    }

    @Test
    fun `the runner owns the transaction`() {
        assertFailsWith<UnsupportedOperationException> { db.tx { connection.commit() } }
        assertFailsWith<UnsupportedOperationException> { db.tx { connection.rollback() } }
        assertFailsWith<UnsupportedOperationException> { db.tx { connection.autoCommit = true } }
        assertFailsWith<UnsupportedOperationException> { db.tx { connection.close() } }
    }

    @Test
    fun `scopes are confined to their block and thread`() {
        val escaped = db.tx { this }
        assertFailsWith<IllegalStateException> { escaped.connection.createStatement() }
        db.tx {
            val scope = this
            val t = Executors.newSingleThreadExecutor()
            try {
                val e = assertFailsWith<ExecutionException> { t.submit { scope.connection.createStatement() }.get() }
                assertTrue(e.cause is IllegalStateException)
            } finally {
                t.shutdown()
            }
        }
    }

    @Test
    fun `nested calls join the outer transaction`() {
        assertNull(TxScope.current())
        db.tx {
            val outer = this
            assertSame(outer, TxScope.current())
            db.tx { assertSame(outer, this) }
            db.read { assertSame(outer, this) }
        }
        db.read { assertFailsWith<IllegalStateException> { db.tx { } } }
        assertNull(TxScope.current())
    }

    @Test
    fun `savepoint rolls back part of a transaction`() {
        db.tx {
            connection.exec("insert into t_db values (10, 1)")
            runCatching { savepoint { connection.exec("insert into t_db values (11, 1)"); error("undo") } }
            savepoint { connection.exec("insert into t_db values (12, 1)") }
        }
        assertEquals(listOf(10, 12), db.read {
            connection.createStatement().use { st ->
                st.executeQuery("select k from t_db where k between 10 and 12 order by k").use { rs ->
                    buildList { while (rs.next()) add(rs.getInt(1)) }
                }
            }
        })
    }

    /** Two transactions that each read both rows and write one: write skew, which SERIALIZABLE forbids. */
    private fun writeSkew(name: String, me: Int, other: Int, barrier: CyclicBarrier, runs: AtomicInteger) = db.tx(name) {
        if (runs.incrementAndGet() <= 2) barrier.await(10, TimeUnit.SECONDS)
        val sum = connection.int("select coalesce(sum(v), 0) from t_db where k in ($me, $other)")
        if (runs.get() <= 2) barrier.await(10, TimeUnit.SECONDS)
        connection.exec("update t_db set v = ${sum + 1} where k = $me")
    }

    @Test
    fun `serialization failures are retried`() {
        db.tx { connection.exec("insert into t_db values (20, 0), (21, 0) on conflict (k) do update set v = 0") }
        val before = db.metrics.byName()["skew"]?.retries ?: 0
        val barrier = CyclicBarrier(2)
        val runs = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit { writeSkew("skew", 20, 21, barrier, runs) }
            val b = pool.submit { writeSkew("skew", 21, 20, barrier, runs) }
            a.get(30, TimeUnit.SECONDS)
            b.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdown()
        }
        assertTrue(runs.get() >= 3, "one transaction must have run again")
        assertTrue(db.metrics.byName().getValue("skew").retries > before)
        // serial result: one saw 0 and wrote 1, the other saw 1 and wrote 2
        assertEquals(3, db.read { connection.int("select sum(v) from t_db where k in (20, 21)") })
    }

    @Test
    fun `a swallowed serialization failure still retries`() {
        val runs = AtomicInteger()
        db.tx("swallow") {
            if (runs.incrementAndGet() == 1) {
                runCatching { savepoint { connection.exec("do $$ begin raise exception 'x' using errcode = '40001'; end $$") } }
            }
        }
        assertEquals(2, runs.get())
        // and without a savepoint, the aborted transaction fails to commit and is retried
        runs.set(0)
        db.tx("swallow2") {
            if (runs.incrementAndGet() == 1) {
                runCatching { connection.exec("do $$ begin raise exception 'x' using errcode = '40001'; end $$") }
            }
        }
        assertEquals(2, runs.get())
    }

    @Test
    fun `a transaction aborted by a swallowed error does not report success`() {
        val e = assertFailsWith<IllegalStateException> {
            db.tx {
                connection.exec("insert into t_db values (40, 1)")
                runCatching { connection.exec("select 1/0") }
            }
        }
        assertEquals("22012", (e.cause as SQLException).sqlState)
        assertEquals(0, db.read { connection.int("select count(*) from t_db where k = 40") })
    }

    @Test
    fun `statements cannot outlive their transaction`() {
        val st = db.tx { connection.createStatement() }
        assertFailsWith<IllegalStateException> { st.execute("select 1") }
    }

    @Test
    fun `gives up after maxAttempts`() {
        val runs = AtomicInteger()
        val e = assertFailsWith<SQLException> {
            db.tx("always-conflicts") {
                runs.incrementAndGet()
                connection.exec("do $$ begin raise exception 'x' using errcode = '40P01'; end $$")
            }
        }
        assertEquals(SqlStates.DEADLOCK_DETECTED, e.sqlState)
        assertEquals(db.config.retry.maxAttempts, runs.get())
        assertEquals(1, db.metrics.byName().getValue("always-conflicts").exhausted)
    }

    @Test
    fun `other errors are not retried`() {
        val runs = AtomicInteger()
        assertFailsWith<SQLException> { db.tx { runs.incrementAndGet(); connection.exec("select 1/0") } }
        assertEquals(1, runs.get())
    }

    @Test
    fun `blocking calls are refused on game threads, async works there`() {
        val done = CountDownLatch(1)
        var refused: Throwable? = null
        var asyncResult: Int? = null
        val t = Thread {
            GameThreads.register()
            try {
                refused = runCatching { db.read { 1 } }.exceptionOrNull()
                asyncResult = db.readAsync { connection.int("select 42") }.get(10, TimeUnit.SECONDS)
            } finally {
                GameThreads.unregister()
                done.countDown()
            }
        }
        t.start()
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertTrue(refused is IllegalStateException)
        assertEquals(42, asyncResult)
    }

    @Test
    fun `suspend transactions join and commit`() = runBlocking {
        val n = db.txSuspend {
            connection.exec("insert into t_db values (30, 1) on conflict (k) do update set v = 1")
            delay(10) // resumes on another thread; the scope follows
            db.tx { connection.exec("update t_db set v = 2 where k = 30") } // blocking join
            db.readSuspend { connection.int("select v from t_db where k = 30") }
        }
        assertEquals(2, n)
    }

    @Test
    fun `cancelling a suspend transaction cancels its statement`() = runBlocking {
        val started = System.nanoTime()
        val job = async {
            db.txSuspend { connection.exec("select pg_sleep(20)") }
        }
        delay(300)
        job.cancel()
        withTimeout(5_000) { runCatching { job.await() } }
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10))
        // the pool still works
        assertEquals(1, db.readSuspend { connection.int("select 1") })
    }

    @Test
    fun `async transactions complete their future`() {
        assertEquals(7, db.txAsync { connection.int("select 7") }.get(10, TimeUnit.SECONDS))
        val e = assertFailsWith<ExecutionException> { db.txAsync { connection.int("select 1/0") }.get(10, TimeUnit.SECONDS) }
        assertNotNull(e.cause as? SQLException)
    }

    @Test
    fun `retry gives up on an unreachable server`() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        val start = System.nanoTime()
        assertFailsWith<SQLException> {
            Database.connectWithRetry(PgEcsConfig("jdbc:postgresql://127.0.0.1:$port/x", "u"), Duration.ofMillis(500), Duration.ofMillis(100))
        }
        assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(500))
    }

    @Test
    fun `rejects non-postgres urls`() {
        assertFailsWith<SQLException> { Database.connect(PgEcsConfig("jdbc:mysql://x/y", "u")) }
    }

    @Test
    fun `config from env`() {
        val cfg = PgEcsConfig.fromEnv(mapOf("PGECS_URL" to "jdbc:postgresql://db/x", "PGECS_USER" to "u", "PGECS_POOL_SIZE" to "7"))
        assertEquals("jdbc:postgresql://db/x", cfg.jdbcUrl)
        assertEquals("u", cfg.user)
        assertEquals(7, cfg.pool.maxSize)
    }

    @Test
    fun `retryable classification follows causes`() {
        assertTrue(SqlStates.isRetryable(RuntimeException(SQLException("x", "40001"))))
        assertTrue(SqlStates.isRetryable(SQLException("x", "25P02").apply { nextException = SQLException("y", "40P01") }))
        assertTrue(!SqlStates.isRetryable(SQLException("x", "23505")))
    }
}
