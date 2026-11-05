package net.blocks168.pgecs.db

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Statement

internal class TxScopeImpl(
    val database: Database,
    private val raw: Connection,
    override val readOnly: Boolean,
    override val attempt: Int,
    /** Thread the scope is confined to; `null` for coroutine scopes, which may move between threads. */
    private val owner: Thread?,
) : TxScope {
    @Volatile
    private var active = true

    /** Set when a retryable failure was seen (and possibly swallowed) inside the block. */
    @Volatile
    var retryCause: Throwable? = null
        private set

    override val connection: Connection = Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
        Guard(),
    ) as Connection

    fun end() {
        active = false
    }

    fun checkUsable() {
        check(active) { "transaction scope used after its block ended" }
        if (owner != null) {
            check(Thread.currentThread() === owner) {
                "transaction scope of thread ${owner.name} used from thread ${Thread.currentThread().name}"
            }
        }
    }

    /** The last SQL failure seen through [connection] or its statements. */
    @Volatile
    var lastFailure: Throwable? = null
        private set

    /** Records a failure seen by the scope, so the runner retries even if the block swallowed it. */
    fun noteFailure(t: Throwable) {
        if (t !is java.sql.SQLException) return
        lastFailure = t
        if (retryCause == null && SqlStates.isRetryable(t)) retryCause = t
    }

    override fun <T> savepoint(block: () -> T): T {
        checkUsable()
        val sp = raw.setSavepoint()
        val result = try {
            block()
        } catch (t: Throwable) {
            noteFailure(t)
            try {
                raw.rollback(sp)
            } catch (rollbackFailure: java.sql.SQLException) {
                t.addSuppressed(rollbackFailure)
            }
            throw t
        }
        raw.releaseSavepoint(sp)
        return result
    }

    override fun toString() = "TxScope(readOnly=$readOnly, attempt=$attempt, active=$active)"

    private inner class Guard : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            when (method.name) {
                "equals" -> return proxy === args?.get(0)
                "hashCode" -> return System.identityHashCode(proxy)
                "toString" -> return "pgecs transaction connection ($this@TxScopeImpl)"
                "isClosed" -> return !active || raw.isClosed
                "commit", "setAutoCommit", "setTransactionIsolation", "setReadOnly", "close", "abort",
                "beginRequest", "endRequest" ->
                    throw UnsupportedOperationException("pgecs owns this transaction: ${method.name} is not allowed")
                "rollback" -> if (args.isNullOrEmpty()) {
                    throw UnsupportedOperationException("pgecs owns this transaction: use savepoint { } or throw")
                }
            }
            checkUsable()
            val result = try {
                method.invoke(raw, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException.also(::noteFailure)
            }
            // statements report their failures to the scope too
            return if (result is Statement && Statement::class.java.isAssignableFrom(method.returnType)) {
                Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(method.returnType), StatementGuard(result))
            } else {
                result
            }
        }
    }

    private inner class StatementGuard(private val statement: Statement) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            when (method.name) {
                "equals" -> return proxy === args?.get(0)
                "hashCode" -> return System.identityHashCode(proxy)
                "getConnection" -> return connection
                "close", "isClosed" -> {}
                else -> checkUsable()
            }
            return try {
                method.invoke(statement, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException.also(::noteFailure)
            }
        }
    }

    companion object {
        val CURRENT = ThreadLocal<TxScopeImpl?>()
    }
}
