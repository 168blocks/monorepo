package net.blocks168.pgecs

import java.util.UUID
import java.util.concurrent.CompletableFuture
import net.blocks168.pgecs.db.Database
import net.blocks168.pgecs.db.TxScope
import net.blocks168.pgecs.db.TxScopeImpl
import net.blocks168.pgecs.dsl.QueryExecutor
import net.blocks168.pgecs.dsl.QueryResultException
import net.blocks168.pgecs.dsl.Queryable
import net.blocks168.pgecs.dsl.ResultMode
import net.blocks168.pgecs.ir.QueryIr
import net.blocks168.pgecs.query.QueryEngine
import net.blocks168.pgecs.query.QueryEngineConfig
import net.blocks168.pgecs.query.exec.ResultSizeException
import net.blocks168.pgecs.query.sql.Mode
import net.blocks168.pgecs.schema.Freeze
import net.blocks168.pgecs.schema.FreezeReport
import net.blocks168.pgecs.system.SchedulerConfig
import net.blocks168.pgecs.system.SystemComponent
import net.blocks168.pgecs.system.Systems

/**
 * The pgecs entry point: one Postgres database as the ECS (docs/pgecs.md, *Kotlin API sketch*).
 *
 * ```kotlin
 * val ecs = Pgecs.connect(PgEcsConfig.fromEnv())   // finds every PgecsModule on the class path
 * ecs.freeze()                                      // registry read-only from here; tx and queries unlock
 * val acc = ecs.tx { create { +Account } }
 * val ids = ecs.read { query { val a by entity; a has Account; select { a } }.fetch() }
 * ```
 *
 * Thread-safe; one per process. [close] closes the query engine and, when [Pgecs.connect] opened it,
 * the database.
 */
class Ecs internal constructor(
    val database: Database,
    val engine: QueryEngine,
    private val ownsDatabase: Boolean,
    /** This process: the owner of the leases it takes. Random unless configured. */
    val processId: UUID,
    schedulerConfig: SchedulerConfig,
) : Queryable, AutoCloseable {
    /** The registered types; add more before [freeze]. */
    val registry = Registry()

    /** Scheduled systems and observers; register them after [freeze], then [Systems.start]. */
    val systems: Systems = Systems(this, schedulerConfig)

    @Volatile
    var freezeReport: FreezeReport? = null
        private set

    /**
     * Reconciles the database with the registry (pgecs design, *Registry and freeze*) and makes the
     * registry read-only. Transactions and queries throw before it. Idempotent.
     */
    @Synchronized
    fun freeze(): FreezeReport {
        freezeReport?.let { return it }
        val report = Freeze(database).run(registry.specs())
        registry.freeze()
        freezeReport = report
        return report
    }

    private fun checkFrozen() {
        check(registry.frozen) { "call Ecs.freeze() before running transactions or queries" }
    }

    // ---- transactions --------------------------------------------------------------------------

    /** A read-write `SERIALIZABLE` transaction, retried on conflicts; see [Database.tx]. */
    @JvmOverloads
    fun <T> tx(name: String? = null, block: EcsTx.() -> T): T {
        checkFrozen()
        return database.tx(name) { runTx(this, block) }
    }

    /** A `SERIALIZABLE READ ONLY` transaction, retried on conflicts. */
    @JvmOverloads
    fun <T> read(name: String? = null, block: EcsRead.() -> T): T {
        checkFrozen()
        return database.read(name) { readScope(this).block() }
    }

    /** A `SERIALIZABLE READ ONLY DEFERRABLE` transaction, for long reports. */
    @JvmOverloads
    fun <T> readDeferrable(name: String? = null, block: EcsRead.() -> T): T {
        checkFrozen()
        return database.readDeferrable(name) { readScope(this).block() }
    }

    /** [tx] on a pgecs virtual thread; never blocks the caller (game threads). */
    @JvmOverloads
    fun <T> txAsync(name: String? = null, block: EcsTx.() -> T): CompletableFuture<T> {
        checkFrozen()
        return database.txAsync(name) { runTx(this, block) }
    }

    /** [read] on a pgecs virtual thread. */
    @JvmOverloads
    fun <T> readAsync(name: String? = null, block: EcsRead.() -> T): CompletableFuture<T> {
        checkFrozen()
        return database.readAsync(name) { readScope(this).block() }
    }

    /** [tx] for coroutines. */
    suspend fun <T> txSuspend(name: String? = null, block: suspend EcsTx.() -> T): T {
        checkFrozen()
        return database.txSuspend(name) {
            val tx = txScope(this)
            tx.block().also { tx.flush() }
        }
    }

    /** [read] for coroutines. */
    suspend fun <T> readSuspend(name: String? = null, block: suspend EcsRead.() -> T): T {
        checkFrozen()
        return database.readSuspend(name) { readScope(this).block() }
    }

    private fun <T> runTx(scope: TxScope, block: EcsTx.() -> T): T {
        val tx = txScope(scope)
        return tx.block().also { tx.flush() }
    }

    /** The write scope of [scope]: one per transaction, shared by nested `tx { }` blocks. */
    private fun txScope(scope: TxScope): EcsTxImpl {
        val impl = scope as TxScopeImpl
        check(!impl.readOnly) { "tx { } inside read { }: the outer transaction is read-only" }
        return impl.attachment(EcsTxImpl::class.java) { EcsTxImpl(this, scope, WriteBuffer()) }
    }

    private fun readScope(scope: TxScope): EcsRead {
        val impl = scope as TxScopeImpl
        // a read { } nested in a tx { } sees the outer transaction's writes
        if (!impl.readOnly) return txScope(scope)
        return impl.attachment(EcsReadImpl::class.java) { EcsReadImpl(this, scope) }
    }

    // ---- queries -------------------------------------------------------------------------------

    /**
     * Runs DSL queries on [engine]. Inside a transaction, in it (after flushing its writes); outside,
     * in a read-only transaction of its own.
     */
    override val queryExecutor: QueryExecutor = QueryExecutor { query, params, mode -> execute(query, params, mode) }

    private fun execute(query: QueryIr, params: List<Any?>, mode: ResultMode): List<Array<Any?>> {
        checkFrozen()
        val current = TxScopeImpl.CURRENT.get()
        if (current == null) return database.read("pgecs:query") { execute(query, params, mode) }
        check(current.database === database) { "a transaction of another database is running on this thread" }
        current.peekAttachment(EcsTxImpl::class.java)?.flush()
        return try {
            engine.execute(current.connection, query, params, mode.toEngine())
        } catch (e: ResultSizeException) {
            throw QueryResultException(e.message ?: "unexpected number of rows")
        }
    }

    private fun ResultMode.toEngine(): Mode = when (this) {
        ResultMode.FETCH -> Mode.FETCH
        ResultMode.ONE -> Mode.ONE
        ResultMode.FIRST -> Mode.FIRST
        ResultMode.COUNT -> Mode.COUNT
    }

    override fun close() {
        systems.close()
        engine.close()
        if (ownsDatabase) database.close()
    }

    override fun toString(): String = "Ecs($database, frozen=${registry.frozen})"
}

/** How to create an [Ecs]. */
object Pgecs {
    /** Settings of [connect]. */
    class Options {
        /** The query engine's settings. */
        var engine: QueryEngineConfig = QueryEngineConfig()

        /** The process id ([Ecs.processId]); stable ids from config make leases survive restarts. */
        var processId: UUID = UUID.randomUUID()

        var scheduler: SchedulerConfig = SchedulerConfig()

        /** Whether to register every [PgecsModule] found by [java.util.ServiceLoader]. */
        var loadModules: Boolean = true

        internal val types = ArrayList<ComponentType>()

        /** Registers [types] in addition to the modules found. */
        fun register(vararg types: ComponentType) {
            this.types += types
        }
    }

    /** Connects to [config]'s database and creates an [Ecs] that owns it. Call [Ecs.freeze] next. */
    @JvmStatic
    @JvmOverloads
    fun connect(config: PgEcsConfig, configure: Options.() -> Unit = {}): Ecs =
        create(Database.connectWithRetry(config), ownsDatabase = true, configure)

    /** An [Ecs] on an already connected [database], which the caller keeps owning. */
    @JvmStatic
    @JvmOverloads
    fun on(database: Database, configure: Options.() -> Unit = {}): Ecs = create(database, ownsDatabase = false, configure)

    private fun create(database: Database, ownsDatabase: Boolean, configure: Options.() -> Unit): Ecs {
        val options = Options().apply(configure)
        val ecs = Ecs(database, QueryEngine(options.engine), ownsDatabase, options.processId, options.scheduler)
        ecs.registry.register(ChildOf, SystemComponent)
        if (options.loadModules) ecs.registry.registerModules()
        ecs.registry.register(options.types)
        return ecs
    }
}
