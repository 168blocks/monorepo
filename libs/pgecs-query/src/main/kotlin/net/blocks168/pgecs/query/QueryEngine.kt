package net.blocks168.pgecs.query

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.Caffeine
import net.blocks168.pgecs.eqsat.EgglogPool
import net.blocks168.pgecs.ir.QueryIr
import net.blocks168.pgecs.ir.ValueType
import net.blocks168.pgecs.ir.parameterize
import net.blocks168.pgecs.query.exec.Execution
import net.blocks168.pgecs.query.lower.Lowering
import net.blocks168.pgecs.query.opt.EqsatOptimizer
import net.blocks168.pgecs.query.opt.PlanOptimizer
import net.blocks168.pgecs.query.plan.Block
import net.blocks168.pgecs.query.plan.LogicalQuery
import net.blocks168.pgecs.query.plan.Pred
import net.blocks168.pgecs.query.plan.Stats
import net.blocks168.pgecs.query.sql.CompiledSql
import net.blocks168.pgecs.query.sql.Mode
import net.blocks168.pgecs.query.sql.SqlCompiler
import java.sql.Connection
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

/** Settings of a [QueryEngine]. */
data class QueryEngineConfig(
    /** Threads that lower, optimise and compile new query shapes (never game threads). */
    val compileThreads: Int = 2,
    /** egglog instances; 0 turns equality saturation off (the simplifier alone then optimises). */
    val eqsatInstances: Int = 2,
    val eqsatIterations: Int = EqsatOptimizer.DEFAULT_ITERATIONS,
    /** How long a compile waits for egglog before using the simplified plan (and upgrading later). */
    val eqsatTimeout: Duration = Duration.ofMillis(250),
    /** Total weight of cached plans and statements; a plan weighs about its SQL length in characters. */
    val maxCacheWeight: Long = 8L shl 20,
    /** Cached plans unused for this long are dropped. */
    val expireAfterAccess: Duration = Duration.ofHours(1),
    /** How often to re-read the row-count statistics (on the next execution after this long). */
    val statsRefresh: Duration = Duration.ofMinutes(1),
    /** A shape is re-planned when a component it reads grew or shrank by more than this factor. */
    val driftFactor: Double = 10.0,
) {
    init {
        require(compileThreads > 0 && eqsatInstances >= 0)
    }
}

/**
 * A compiled query shape for one [Mode]: the statement (null when the planner proved the query
 * returns no rows), the IR types of its columns, and the plan it came from.
 */
class CompiledQuery(
    val sql: CompiledSql?,
    val columns: List<ValueType>,
    val plan: LogicalQuery,
    /** Whether egglog optimised [plan] (or only the simplifier did). */
    val optimized: Boolean,
    internal val stats: Stats,
) {
    override fun toString(): String = sql?.sql ?: "-- no rows: the query is contradictory"
}

/**
 * The pgecs query engine for the Postgres source: plans, optimises, compiles and runs first-level IR
 * queries.
 *
 * **Pipeline.** [Lowering] (IR to logical plan), [PlanOptimizer] (simplifier, then egglog within a
 * time limit), [SqlCompiler] (one statement, joins ordered by [Stats]), [Execution].
 *
 * **Caches** (pgecs design, *Query compilation cache*), both Caffeine with single-flight loads:
 * 1. *plan*: the parameterized IR shape plus which parameters are null at execution (null changes
 *    `x == p` into `x is null`) → optimised logical plan;
 * 2. *SQL*: plan key plus [Mode] → statement text and bind layout.
 * Server-side prepared statements are pgjdbc's job (`prepareThreshold`). Plans are re-made when the
 * row counts of a component they read drift by more than [QueryEngineConfig.driftFactor], when egglog
 * finishes after its time limit (the better plan replaces the simplified one), and on [invalidateAll]
 * (a source was added or changed).
 *
 * **Hot shapes.** Every execution counts its shape; [HotShapes] stores the counts in Postgres and
 * compiles the hottest shapes of the previous boot at startup.
 *
 * Thread-safe. One per process.
 */
class QueryEngine(val config: QueryEngineConfig = QueryEngineConfig()) : AutoCloseable {
    private val threadIds = AtomicInteger()
    private val compileExecutor: ExecutorService = Executors.newFixedThreadPool(config.compileThreads) { r ->
        Thread(r, "pgecs-compile-${threadIds.incrementAndGet()}").apply { isDaemon = true }
    }
    private val eqsatExecutor: ExecutorService? = if (config.eqsatInstances == 0) {
        null
    } else {
        Executors.newFixedThreadPool(config.eqsatInstances) { r ->
            Thread(r, "pgecs-eqsat-${threadIds.incrementAndGet()}").apply { isDaemon = true }
        }
    }
    private val egglog: EgglogPool? =
        if (config.eqsatInstances == 0) null else EgglogPool(config.eqsatInstances, EqsatOptimizer.PRELUDE)
    private val optimizer = PlanOptimizer(
        egglog?.let { EqsatOptimizer(it, config.eqsatIterations) },
        eqsatExecutor ?: compileExecutor,
        config.eqsatTimeout.toMillis(),
    )

    /** Key of the plan cache: the shape, and which of its parameters are null at this execution. */
    private data class PlanKey(val shape: QueryIr, val nullParams: Set<Int>)

    private data class SqlKey(val plan: PlanKey, val mode: Mode)

    private class Plan(val query: LogicalQuery, val columns: List<ValueType>, val optimized: Boolean, val stats: Stats)

    private val plans: AsyncCache<PlanKey, Plan> = Caffeine.newBuilder()
        .maximumWeight(config.maxCacheWeight / 2)
        .weigher<PlanKey, Plan> { k, _ -> k.shape.canonicalText.length }
        .expireAfterAccess(config.expireAfterAccess)
        .executor(compileExecutor)
        .recordStats()
        .buildAsync()

    private val statements: AsyncCache<SqlKey, CompiledQuery> = Caffeine.newBuilder()
        .maximumWeight(config.maxCacheWeight / 2)
        .weigher<SqlKey, CompiledQuery> { _, v -> 64 + (v.sql?.sql?.length ?: 0) }
        .expireAfterAccess(config.expireAfterAccess)
        .executor(compileExecutor)
        .recordStats()
        .buildAsync()

    @Volatile
    var stats: Stats = Stats.UNKNOWN
        private set

    @Volatile
    private var statsReadAt = 0L
    private val statsRefreshing = AtomicBoolean()

    /** Uses per shape since the last [HotShapes.flush]. */
    internal val uses = ConcurrentHashMap<QueryIr, LongAdder>()

    // ---- metrics ----------------------------------------------------------------------------------

    private val compiles = LongAdder()
    private val eqsatRuns = LongAdder()
    private val eqsatLate = LongAdder()
    private val eqsatFailures = LongAdder()
    private val replans = LongAdder()
    private val compileNanos = Reservoir()
    private val eqsatNanos = Reservoir()

    /** Counters and timings of the engine, for metrics. */
    data class Metrics(
        val planHitRate: Double,
        val sqlHitRate: Double,
        val cachedPlans: Long,
        val cachedStatements: Long,
        val evictions: Long,
        val compiles: Long,
        val eqsatRuns: Long,
        val eqsatLate: Long,
        val eqsatFailures: Long,
        val replans: Long,
        val compileMillisP50: Double,
        val compileMillisP99: Double,
        val eqsatMillisP50: Double,
        val eqsatMillisP99: Double,
    )

    fun metrics(): Metrics {
        val p = plans.synchronous().stats()
        val s = statements.synchronous().stats()
        return Metrics(
            planHitRate = p.hitRate(),
            sqlHitRate = s.hitRate(),
            cachedPlans = plans.synchronous().estimatedSize(),
            cachedStatements = statements.synchronous().estimatedSize(),
            evictions = p.evictionCount() + s.evictionCount(),
            compiles = compiles.sum(),
            eqsatRuns = eqsatRuns.sum(),
            eqsatLate = eqsatLate.sum(),
            eqsatFailures = eqsatFailures.sum(),
            replans = replans.sum(),
            compileMillisP50 = compileNanos.quantile(0.5) / 1e6,
            compileMillisP99 = compileNanos.quantile(0.99) / 1e6,
            eqsatMillisP50 = eqsatNanos.quantile(0.5) / 1e6,
            eqsatMillisP99 = eqsatNanos.quantile(0.99) / 1e6,
        )
    }

    // ---- compiling --------------------------------------------------------------------------------

    /**
     * The compiled form of [shape] (a parameterized, validated query) for [mode], when parameters
     * [nullParams] are null. Completes on a compile thread for a new shape, at once for a cached one;
     * game threads wait on the future instead of blocking.
     */
    fun compileAsync(shape: QueryIr, nullParams: Set<Int> = emptySet(), mode: Mode = Mode.FETCH): CompletableFuture<CompiledQuery> {
        val key = SqlKey(PlanKey(shape, nullParams), mode)
        return statements.get(key) { k, _ -> plan(k.plan).thenApply { sql(it, k.mode) } }.thenCompose { cq ->
            if (drifted(cq)) {
                replans.increment()
                invalidate(key.plan)
                statements.get(key) { k, _ -> plan(k.plan).thenApply { sql(it, k.mode) } }
            } else {
                CompletableFuture.completedFuture(cq)
            }
        }
    }

    /** [compileAsync], waiting for it. Throws what compiling throws. */
    fun compile(shape: QueryIr, nullParams: Set<Int> = emptySet(), mode: Mode = Mode.FETCH): CompiledQuery =
        await(compileAsync(shape, nullParams, mode))

    private fun plan(key: PlanKey): CompletableFuture<Plan> = plans.get(key) { k, _ ->
        CompletableFuture.supplyAsync({ makePlan(k) }, compileExecutor)
    }

    private fun makePlan(key: PlanKey): Plan {
        val start = System.nanoTime()
        compiles.increment()
        val lowered = Lowering.lower(key.shape, key.nullParams)
        val result = optimizer.optimize(lowered.query)
        val snapshot = stats
        when {
            result.by == PlanOptimizer.By.EQSAT -> {
                eqsatRuns.increment()
                eqsatNanos.add(result.eqsatNanos)
            }
            result.late != null -> {
                eqsatLate.increment()
                // swap the optimised plan in once egglog is done
                result.late.thenAccept { late ->
                    if (late != null) {
                        plans.put(key, CompletableFuture.completedFuture(Plan(late, lowered.columns, true, snapshot)))
                        Mode.entries.forEach { statements.synchronous().invalidate(SqlKey(key, it)) }
                    }
                }
            }
            result.failure != null -> {
                eqsatFailures.increment()
                LOG.log(System.Logger.Level.WARNING, "egglog failed; using the simplified plan of ${key.shape.fingerprint}", result.failure)
            }
        }
        compileNanos.add(System.nanoTime() - start)
        return Plan(result.plan, lowered.columns, result.by == PlanOptimizer.By.EQSAT, snapshot)
    }

    private fun sql(plan: Plan, mode: Mode): CompiledQuery {
        val empty = plan.query.root.where == Pred.False && !plan.query.isAggregate
        val sql = if (empty) null else SqlCompiler(plan.stats).compile(plan.query, mode)
        return CompiledQuery(sql, plan.columns, plan.query, plan.optimized, plan.stats)
    }

    private fun drifted(cq: CompiledQuery): Boolean {
        val now = stats
        if (now === cq.stats) return false
        return rels(cq.plan.root).any { now.drifted(cq.stats, it, config.driftFactor) }
    }

    private fun rels(b: Block): Set<String> = buildSet {
        b.scans.forEach { s -> s.rel?.let(::add) }
        b.optionals.forEach { addAll(rels(it)) }
    }

    private fun invalidate(key: PlanKey) {
        plans.synchronous().invalidate(key)
        Mode.entries.forEach { statements.synchronous().invalidate(SqlKey(key, it)) }
    }

    /**
     * Warms egglog up: optimises [shapes] [runs] times in total, one at a time on a compile thread,
     * bypassing the caches. egglog's JIT warm-up takes a few hundred runs and is shared by every
     * instance, so doing it at startup keeps the first players' new query shapes fast. Uses one egglog
     * instance at a time, so real compiles are not held up for long.
     */
    fun warmUpAsync(shapes: List<QueryIr>, runs: Int = 300): CompletableFuture<Void> {
        if (shapes.isEmpty() || egglog == null) return CompletableFuture.completedFuture(null)
        val plans = shapes.mapNotNull { runCatching { Lowering.lower(it).query }.getOrNull() }
        if (plans.isEmpty()) return CompletableFuture.completedFuture(null)
        val eqsat = EqsatOptimizer(egglog, config.eqsatIterations)
        return CompletableFuture.runAsync({
            for (i in 0 until runs) {
                if (Thread.currentThread().isInterrupted) return@runAsync
                runCatching { eqsat.optimize(plans[i % plans.size]) }
            }
        }, compileExecutor)
    }

    /** Drops every cached plan, for example when a source is added or changes its capabilities. */
    fun invalidateAll() {
        plans.synchronous().invalidateAll()
        statements.synchronous().invalidateAll()
    }

    // ---- executing --------------------------------------------------------------------------------

    /**
     * Runs [query] with [params] (one per [QueryIr.params], in order) on [connection], inside whatever
     * transaction the caller has open, and returns its rows (see [Execution] for the value types).
     * [query] may contain literals; they are lifted into parameters, so they never split the cache.
     */
    fun execute(connection: Connection, query: QueryIr, params: List<Any?>, mode: Mode = Mode.FETCH): List<Array<Any?>> {
        val bound = query.parameterize(params)
        val nulls = bound.arguments.indices.filterTo(HashSet()) { bound.arguments[it] == null }
        refreshStats(connection)
        val cq = compile(bound.shape, nulls, mode)
        uses.computeIfAbsent(bound.shape) { LongAdder() }.increment()
        return Execution.run(connection, cq.sql, cq.columns, bound.arguments, mode)
    }

    /** Re-reads [stats] through [connection] if they are older than [QueryEngineConfig.statsRefresh]. */
    fun refreshStats(connection: Connection, force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && statsReadAt != 0L && now - statsReadAt < config.statsRefresh.toNanos()) return
        if (!statsRefreshing.compareAndSet(false, true)) return
        try {
            stats = Stats.read(connection)
            statsReadAt = now
        } finally {
            statsRefreshing.set(false)
        }
    }

    /** Uses [stats] instead of reading them from the database (tests, or an external source of estimates). */
    fun setStats(stats: Stats) {
        this.stats = stats
        statsReadAt = System.nanoTime()
    }

    override fun close() {
        compileExecutor.shutdownNow()
        eqsatExecutor?.shutdownNow()
    }

    internal companion object {
        val LOG: System.Logger = System.getLogger(QueryEngine::class.java.name)

        fun <T> await(f: CompletableFuture<T>): T = try {
            f.join()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }
    }
}

/** The last 1024 samples, for rough quantiles. */
private class Reservoir {
    private val samples = LongArray(1024)
    private var n = 0L

    @Synchronized
    fun add(v: Long) {
        samples[(n++ % samples.size).toInt()] = v
    }

    @Synchronized
    fun quantile(q: Double): Double {
        val size = minOf(n, samples.size.toLong()).toInt()
        if (size == 0) return 0.0
        val sorted = samples.copyOf(size).also { it.sort() }
        return sorted[((size - 1) * q).toInt()].toDouble()
    }
}
