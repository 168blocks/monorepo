package net.blocks168.pgecs.db

import java.sql.Connection
import kotlin.coroutines.CoroutineContext

/**
 * One running `SERIALIZABLE` transaction, handed to the block of [Database.tx] or [Database.read].
 *
 * The scope belongs to that block: after the block returns or throws, every use throws
 * [IllegalStateException]. A blocking scope may also only be used on the thread that opened it.
 *
 * [connection] is the transaction's connection, for other pgecs modules (the query executor, writes).
 * The runner owns the transaction: `commit`, `rollback()`, `setAutoCommit`, `setTransactionIsolation`,
 * `setReadOnly` and `close` throw [UnsupportedOperationException]. Use [savepoint] for partial rollback.
 */
interface TxScope {
    /** The transaction's connection, valid only while the block runs. */
    val connection: Connection

    /** `true` inside [Database.read]: the transaction is `READ ONLY`. */
    val readOnly: Boolean

    /** Attempt number of this run of the block, from 1; above 1 after a serialization failure. */
    val attempt: Int

    /**
     * Runs [block] inside a savepoint: when it throws, its changes are rolled back and the exception
     * is rethrown, while the rest of the transaction continues. A serialization failure or deadlock
     * inside [block] still retries the whole transaction, even if the caller catches it, because
     * PostgreSQL's guarantee only covers transactions that are retried from the start.
     */
    fun <T> savepoint(block: () -> T): T

    /**
     * Coroutine context element of the current scope; `coroutineContext[TxScope]` finds it inside
     * [Database.txSuspend] and [Database.readSuspend].
     */
    companion object Key : CoroutineContext.Key<TxScopeElement> {
        /** The scope of the transaction running on this thread, or `null` outside one. */
        @JvmStatic
        fun current(): TxScope? = TxScopeImpl.CURRENT.get()
    }
}

/** Carries the current [TxScope] in a coroutine context. */
class TxScopeElement internal constructor(val scope: TxScope) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = TxScope.Key
}
