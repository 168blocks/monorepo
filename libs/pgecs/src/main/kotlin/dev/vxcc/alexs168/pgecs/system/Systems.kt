package dev.vxcc.alexs168.pgecs.system

import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import dev.vxcc.alexs168.pgecs.Component
import dev.vxcc.alexs168.pgecs.ComponentType
import dev.vxcc.alexs168.pgecs.Ecs
import dev.vxcc.alexs168.pgecs.EcsTx
import dev.vxcc.alexs168.pgecs.EntityId
import dev.vxcc.alexs168.pgecs.dsl.Query
import dev.vxcc.alexs168.pgecs.dsl.QueryScope
import dev.vxcc.alexs168.pgecs.dsl.Selection
import dev.vxcc.alexs168.pgecs.ir.ComponentRef
import dev.vxcc.alexs168.pgecs.ir.EntityVar
import dev.vxcc.alexs168.pgecs.ir.Expr
import dev.vxcc.alexs168.pgecs.ir.RowLock
import dev.vxcc.alexs168.pgecs.ir.Term
import dev.vxcc.alexs168.pgecs.ir.ValueType
import dev.vxcc.alexs168.pgecs.Sql
import dev.vxcc.alexs168.pgecs.schema.ComponentIds
import dev.vxcc.alexs168.pgecs.serial.PgecsJson

/**
 * The state of one system, on its own entity (id: [Systems.entityOf] its name), in `pgecs:system:v1`.
 * Times are epoch milliseconds of the database clock.
 */
@Serializable
data class SystemState(
    val name: String,
    /** `periodic` or `observer`. */
    val kind: String,
    /** When the next periodic run may start. */
    val nextRun: Long = 0,
    /** The process ([Ecs.processId]) that holds the lease, or null. */
    val leaseOwner: String? = null,
    val leaseUntil: Long? = null,
    /** Observers: the last processed change group, `(cursorXid, cursorEntity)`. */
    val cursorXid: Long? = null,
    val cursorEntity: String? = null,
)

/** `pgecs:system:v1`: leases, schedules and observer cursors. Kept out of the change log. */
object SystemComponent : Component<SystemState>("pgecs:system:v1")

/** Something that happens to a component, that an observer reacts to. */
class Observe private constructor(val type: ComponentType, val ops: Set<ChangeOp>) {
    companion object {
        /** [type] was added to an entity. */
        @JvmStatic
        fun added(type: ComponentType) = Observe(type, setOf(ChangeOp.INSERT))

        /** [type] was removed from an entity, including by deleting the entity or a cascade. */
        @JvmStatic
        fun removed(type: ComponentType) = Observe(type, setOf(ChangeOp.DELETE))

        /** The value of [type] changed. */
        @JvmStatic
        fun updated(type: ComponentType) = Observe(type, setOf(ChangeOp.UPDATE))

        /** Any change of [type]. */
        @JvmStatic
        fun any(type: ComponentType) = Observe(type, ChangeOp.entries.toSet())
    }
}

/** Settings of the scheduler. */
data class SchedulerConfig(
    /** How long a lease lasts; the leader renews it after half of this. */
    val lease: Duration = Duration.ofSeconds(30),
    /** How often each system checks whether it is due (and observers whether there are changes). */
    val poll: Duration = Duration.ofSeconds(1),
    /** A run stops after this many batches, so an action that leaves its rows matching cannot loop forever. */
    val maxBatchesPerRun: Int = 10_000,
    /** Change rows that every observer has passed are deleted when older than this. */
    val changeRetention: Duration = Duration.ofDays(7),
)

/**
 * Scheduled systems and observers of one [Ecs] (pgecs design, *Systems and scheduling*).
 *
 * - **Periodic systems** run every interval. Starting a run claims the lease on the system's row in
 *   one statement, so only one process leads a run; other processes that registered the same system
 *   help while the lease is held. Each batch is its own `SERIALIZABLE` transaction: the query runs
 *   with `limit n` and `FOR UPDATE OF <its first component> SKIP LOCKED`, the action runs on the rows,
 *   and the batch commits. Batches repeat until the query returns nothing, so the action must make its
 *   rows stop matching (expiry deletes them). When the leader dies, the lease expires and the next run
 *   starts elsewhere.
 * - **Observers** read `ecs_change` after their cursor and below the snapshot's `xmin`, so they only
 *   see committed transactions and never skip one that commits late. Changes are grouped per
 *   transaction and entity ([EntityChange]); one leased process runs each observer, and every batch
 *   advances the cursor in the same transaction as the action's writes: database effects happen
 *   exactly once, external effects at least once through the outbox.
 *
 * Register with [system], [task] or [observer], then [start] the scheduler (or drive it with
 * [runOnce], as tests do).
 */
class Systems internal constructor(private val ecs: Ecs, val config: SchedulerConfig) : AutoCloseable {
    private val registered = ConcurrentHashMap<String, Registered>()
    private val running = AtomicBoolean()
    private val threads = ArrayList<Thread>()

    /** A registered system. */
    abstract inner class Registered internal constructor(val name: String, val kind: String) {
        val entity: EntityId = entityOf(name)

        /** Batches this process ran, for metrics. */
        val batches = AtomicLong()

        internal abstract fun runOnce(): Boolean
    }

    /** Id of the entity of system [name]: UUIDv5 of `pgecs:system:<name>`, the same in every process. */
    fun entityOf(name: String): EntityId = EntityId.of(ComponentIds.of("pgecs:system:$name"))

    // ---- registration --------------------------------------------------------------------------

    /**
     * A periodic system that processes the rows of [query] in batches of [batchSize], every [every].
     *
     * ```kotlin
     * ecs.systems.system("b8:util:expire-slow", Duration.ofMinutes(5), 500, {
     *     val e by entity
     *     e has ExpiresSlowComponent.where { it < now() }
     *     select { e }
     * }) { rows -> rows.forEach { delete(it) } }
     * ```
     */
    fun <R> system(
        name: String,
        every: Duration,
        batchSize: Int,
        query: QueryScope.() -> Selection<R>,
        action: EcsTx.(List<R>) -> Unit,
    ): Registered = add(Periodic(name, every, batchSize, query, action))

    /** A periodic system without a query: [action] runs once per interval, in one transaction. */
    fun task(name: String, every: Duration, action: EcsTx.() -> Unit): Registered =
        add(Periodic<Unit>(name, every, 0, null) { action() })

    /**
     * An observer of [on], getting the changes of up to [batchSize] (transaction, entity) groups per
     * batch, oldest first. A new observer starts with changes committed after it was first registered.
     */
    fun observer(name: String, vararg on: Observe, batchSize: Int = 100, action: EcsTx.(List<EntityChange>) -> Unit): Registered {
        require(on.isNotEmpty()) { "observer $name observes nothing" }
        return add(Observer(name, on.toList(), batchSize, action))
    }

    private fun <S : Registered> add(s: S): S {
        val old = registered.putIfAbsent(s.name, s)
        require(old == null) { "system ${s.name} is registered twice" }
        ensureRow(s)
        if (running.get()) launch(s)
        return s
    }

    operator fun get(name: String): Registered? = registered[name]

    /** Creates the system's row unless another process did; never touches an existing one. */
    private fun ensureRow(s: Registered) {
        ecs.tx("pgecs:system-register") {
            val xid = if (s.kind == OBSERVER) snapshotXmin(this) else null
            val state = SystemState(s.name, s.kind, cursorXid = xid, cursorEntity = xid?.let { NIL.toString() })
            Sql.update(connection, "insert into ecs_entity (id) values (?) on conflict (id) do nothing", s.entity.id)
            Sql.update(
                connection,
                "insert into ecs_component (entity, rel, value) values (?, 'pgecs:system:v1', ?::jsonb) on conflict (entity, rel, target) do nothing",
                s.entity.id, PgecsJson.encodeToString(SystemState.serializer(), state),
            )
            val stored = get(s.entity, SystemComponent) ?: error("system ${s.name} has no row")
            check(stored.kind == s.kind) { "system ${s.name} is registered as ${s.kind} here but as ${stored.kind} in the database" }
        }
    }

    // ---- running -------------------------------------------------------------------------------

    /** Starts one virtual thread per system that polls every [SchedulerConfig.poll], plus change-log retention. */
    @Synchronized
    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (registered[RETENTION] == null) installRetention()
        registered.values.forEach(::launch)
    }

    private fun installRetention() {
        task(RETENTION, Duration.ofHours(1)) { pruneChanges(this) }
    }

    @Synchronized
    private fun launch(s: Registered) {
        threads += Thread.ofVirtual().name("pgecs-system-${s.name}").start {
            while (running.get()) {
                val worked = try {
                    s.runOnce()
                } catch (_: InterruptedException) {
                    return@start
                } catch (t: Throwable) {
                    LOG.log(System.Logger.Level.WARNING, "system ${s.name} failed; retrying after ${config.poll}", t)
                    false
                }
                if (!worked) {
                    try {
                        Thread.sleep(config.poll)
                    } catch (_: InterruptedException) {
                        return@start
                    }
                }
            }
        }
    }

    /** Stops the scheduler and releases this process' leases. */
    @Synchronized
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        threads.forEach(Thread::interrupt)
        threads.forEach { it.join(Duration.ofSeconds(10)) }
        threads.clear()
        runCatching { releaseLeases() }
    }

    /**
     * Runs system [name] once now in this thread: a due periodic run (leading or helping), or one
     * observer batch. Returns whether it did any work.
     */
    fun runOnce(name: String): Boolean = (registered[name] ?: throw IllegalArgumentException("no system $name")).runOnce()

    private fun releaseLeases() {
        ecs.tx("pgecs:system-release") {
            for (s in registered.values) {
                connection.prepareStatement(
                    "update ecs_component set value = value || '{\"leaseOwner\": null, \"leaseUntil\": null}' " +
                        "where entity = ? and rel = 'pgecs:system:v1' and value->>'leaseOwner' = ?",
                ).use { st ->
                    st.setObject(1, s.entity.id)
                    st.setString(2, ecs.processId.toString())
                    st.executeUpdate()
                }
            }
        }
    }

    // ---- leases --------------------------------------------------------------------------------

    private val me: String get() = ecs.processId.toString()

    /**
     * Claims the lease of [s] in one statement when it is free (or already ours) and, for periodic
     * systems, the run is due. Returns the lease's end, or null when not claimed.
     */
    private fun claim(s: Registered, due: Boolean): Long? = ecs.tx("pgecs:system-claim") {
        connection.prepareStatement(
            """
            update ecs_component set value = value || jsonb_build_object('leaseOwner', ?::text, 'leaseUntil', $NOW_MS + ?)
             where entity = ? and rel = 'pgecs:system:v1'
               and (not ? or (value->>'nextRun')::bigint <= $NOW_MS)
               and ((value->>'leaseUntil') is null or (value->>'leaseUntil')::bigint < $NOW_MS or value->>'leaseOwner' = ?)
            returning (value->>'leaseUntil')::bigint
            """.trimIndent(),
        ).use { st ->
            st.setString(1, me)
            st.setLong(2, config.lease.toMillis())
            st.setObject(3, s.entity.id)
            st.setBoolean(4, due)
            st.setString(5, me)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }
    }

    /** Whether another process holds an unexpired lease on [s]. */
    private fun leasedByOther(s: Registered): Boolean = ecs.read("pgecs:system-state") {
        val st = get(s.entity, SystemComponent) ?: return@read false
        st.leaseOwner != null && st.leaseOwner != me && (st.leaseUntil ?: 0) >= nowMillis(this)
    }

    private fun finishRun(s: Registered, every: Duration) {
        ecs.tx("pgecs:system-finish") {
            connection.prepareStatement(
                """
                update ecs_component set value = value || jsonb_build_object('nextRun', $NOW_MS + ?, 'leaseOwner', null, 'leaseUntil', null)
                 where entity = ? and rel = 'pgecs:system:v1' and value->>'leaseOwner' = ?
                """.trimIndent(),
            ).use { st ->
                st.setLong(1, every.toMillis())
                st.setObject(2, s.entity.id)
                st.setString(3, me)
                st.executeUpdate()
            }
        }
    }

    // ---- periodic ------------------------------------------------------------------------------

    private inner class Periodic<R>(
        name: String,
        val every: Duration,
        val batchSize: Int,
        val query: (QueryScope.() -> Selection<R>)?,
        val action: EcsTx.(List<R>) -> Unit,
    ) : Registered(name, PERIODIC) {
        init {
            require(!every.isNegative && !every.isZero) { "system $name: interval must be positive" }
            require(query == null || batchSize > 0) { "system $name: batch size must be positive" }
        }

        override fun runOnce(): Boolean {
            var leaseUntil = claim(this, due = true)
            if (leaseUntil == null) {
                // a run led by another process: help with its batches
                if (query == null || !leasedByOther(this)) return false
                return runBatches(leader = false, leaseUntil = 0) > 0
            }
            if (query == null) {
                ecs.tx(name) { action(emptyList()) }
                batches.incrementAndGet()
            } else {
                runBatches(leader = true, leaseUntil = leaseUntil)
            }
            finishRun(this, every)
            return true
        }

        /** Runs batches until the query is empty; the leader renews its lease on the way. */
        private fun runBatches(leader: Boolean, leaseUntil: Long): Int {
            var until = leaseUntil
            var n = 0
            while (n < config.maxBatchesPerRun) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (leader && System.currentTimeMillis() > until - config.lease.toMillis() / 2) {
                    until = claim(this, due = false) ?: return n // the lease was lost: someone else leads now
                }
                val rows = ecs.tx(name) {
                    val rows = batchQuery(ecs.query(query!!)).fetch()
                    if (rows.isNotEmpty()) action(rows)
                    rows
                }
                if (rows.isEmpty()) break
                n++
                batches.incrementAndGet()
            }
            if (n == config.maxBatchesPerRun) {
                LOG.log(System.Logger.Level.WARNING, "system $name stopped after $n batches; does its action make its rows stop matching?")
            }
            return n
        }

        /** [q] with `limit batchSize` and `FOR UPDATE OF <its first component match> SKIP LOCKED`. */
        private fun batchQuery(q: Query<R>): Query<R> {
            val first = q.ir.terms.filterIsInstance<Term.Match>()
                .firstOrNull { it.subject is EntityVar && it.component is ComponentRef }
                ?: throw IllegalArgumentException("system $name: its query needs a component term on a variable to lock")
            val lock = RowLock(first.subject as EntityVar, first.component as ComponentRef)
            return q.withIr(q.ir.copy(lock = lock, limit = Expr.Literal(batchSize.toLong(), ValueType.INT)))
        }
    }

    // ---- observers -----------------------------------------------------------------------------

    private inner class Observer(
        name: String,
        val on: List<Observe>,
        val batchSize: Int,
        val action: EcsTx.(List<EntityChange>) -> Unit,
    ) : Registered(name, OBSERVER) {
        private val rels = on.map { it.type.name }.distinct().toTypedArray()
        private val ops = on.flatMap { it.ops }.map { it.sql }.distinct().toTypedArray()

        @Volatile
        private var leaseUntil = 0L

        @Volatile
        private var lastIdleAdvance = 0L

        init {
            require(batchSize > 0) { "observer $name: batch size must be positive" }
            on.forEach { ecs.registry.require(it.type) }
        }

        override fun runOnce(): Boolean {
            if (System.currentTimeMillis() > leaseUntil - config.lease.toMillis() / 2) {
                leaseUntil = claim(this, due = false) ?: 0
                if (leaseUntil == 0L) return false
            }
            val n = ecs.tx(name) {
                val state = connection.prepareStatement(
                    "select value::text, $NOW_MS from ecs_component where entity = ? and rel = 'pgecs:system:v1'",
                ).use { st ->
                    st.setObject(1, entity.id)
                    st.executeQuery().use { rs ->
                        check(rs.next()) { "observer $name has no system row" }
                        PgecsJson.decodeFromString(SystemState.serializer(), rs.getString(1)) to rs.getLong(2)
                    }
                }
                val (s, now) = state
                // the lease is checked in the same transaction that advances the cursor
                if (s.leaseOwner != me || (s.leaseUntil ?: 0) < now) return@tx -1
                val cursorXid = s.cursorXid ?: 0
                val cursorEntity = s.cursorEntity?.let(UUID::fromString) ?: NIL
                val groups = readChanges(this, cursorXid, cursorEntity)
                if (groups.isEmpty()) {
                    advanceIdle(this, cursorXid)
                    return@tx 0
                }
                action(groups)
                val last = groups.last()
                setCursor(this, last.xid, last.entity.id)
                groups.size
            }
            if (n < 0) {
                leaseUntil = 0
                return false
            }
            if (n > 0) batches.incrementAndGet()
            return n > 0
        }

        private fun readChanges(tx: EcsTx, cursorXid: Long, cursorEntity: UUID): List<EntityChange> {
            val c = tx.connection
            return c.prepareStatement(
                """
                with groups as (
                  select distinct xid, entity from ecs_change
                   where rel = any(?) and op = any(?)
                     and xid < pg_snapshot_xmin(pg_current_snapshot())
                     and (xid, entity) > (?::text::xid8, ?::uuid)
                   order by xid, entity
                   limit ?
                )
                select c.xid::text::bigint, c.entity, c.rel, c.target, c.op, c.old_value::text
                  from ecs_change c join groups g on g.xid = c.xid and g.entity = c.entity
                 order by c.xid, c.entity, c.id
                """.trimIndent(),
            ).use { st ->
                st.setArray(1, c.createArrayOf("text", rels))
                st.setArray(2, c.createArrayOf("text", ops))
                st.setString(3, cursorXid.toString())
                st.setObject(4, cursorEntity)
                st.setInt(5, batchSize)
                st.executeQuery().use { rs ->
                    val out = ArrayList<EntityChange>()
                    var key: Pair<Long, UUID>? = null
                    var rows = ArrayList<ChangeRow>()
                    fun emit() {
                        key?.let { (x, e) -> out += EntityChange(EntityId.of(e), x, rows) }
                    }
                    while (rs.next()) {
                        val k = rs.getLong(1) to (rs.getObject(2) as UUID)
                        if (k != key) {
                            emit()
                            key = k
                            rows = ArrayList()
                        }
                        val target = (rs.getObject(4) as UUID).takeIf { it != NIL }?.let(EntityId::of)
                        rows += ChangeRow(rs.getString(3), target, ChangeOp.ofSql(rs.getString(5)), rs.getString(6))
                    }
                    emit()
                    out
                }
            }
        }

        private fun setCursor(tx: EcsTx, xid: Long, entity: UUID) {
            tx.connection.prepareStatement(
                "update ecs_component set value = value || jsonb_build_object('cursorXid', ?::bigint, 'cursorEntity', ?::text) " +
                    "where entity = ? and rel = 'pgecs:system:v1'",
            ).use { st ->
                st.setLong(1, xid)
                st.setString(2, entity.toString())
                st.setObject(3, this.entity.id)
                st.executeUpdate()
            }
        }

        /**
         * With nothing to do, moves the cursor up to the snapshot's xmin now and then, so that change
         * retention is not held back by an observer of a rarely changing component.
         */
        private fun advanceIdle(tx: EcsTx, cursorXid: Long) {
            val now = System.currentTimeMillis()
            if (now - lastIdleAdvance < IDLE_ADVANCE_MILLIS) return
            lastIdleAdvance = now
            val xmin = snapshotXmin(tx)
            if (xmin > cursorXid) setCursor(tx, xmin, NIL)
        }
    }

    // ---- retention -----------------------------------------------------------------------------

    /**
     * Deletes change rows older than [SchedulerConfig.changeRetention] that every observer in the
     * database has passed, in chunks.
     */
    fun pruneChanges(tx: EcsTx): Int {
        var total = 0
        while (true) {
            val n = tx.connection.prepareStatement(
                """
                delete from ecs_change where id in (
                  select id from ecs_change
                   where at < now() - make_interval(secs => ?)
                     and xid < coalesce((select min((value->>'cursorXid')::bigint)::text::xid8 from ecs_component
                                          where rel = 'pgecs:system:v1' and value->>'kind' = 'observer'),
                                        pg_snapshot_xmin(pg_current_snapshot()))
                   order by id limit 10000)
                """.trimIndent(),
            ).use { st ->
                st.setDouble(1, config.changeRetention.toMillis() / 1000.0)
                st.executeUpdate()
            }
            total += n
            if (n < 10000) return total
        }
    }

    companion object {
        const val PERIODIC = "periodic"
        const val OBSERVER = "observer"

        /** The built-in system that prunes `ecs_change`. */
        const val RETENTION = "pgecs:change-retention"

        private const val IDLE_ADVANCE_MILLIS = 60_000L
        private val NIL = UUID(0, 0)

        /** The database clock in epoch milliseconds, at transaction start. */
        private const val NOW_MS = "(extract(epoch from now()) * 1000)::bigint"

        private val LOG: System.Logger = System.getLogger(Systems::class.java.name)

        internal fun snapshotXmin(tx: EcsTx): Long = tx.connection.createStatement().use { st ->
            st.executeQuery("select pg_snapshot_xmin(pg_current_snapshot())::text::bigint").use { it.next(); it.getLong(1) }
        }

        internal fun nowMillis(tx: dev.vxcc.alexs168.pgecs.EcsRead): Long = tx.connection.createStatement().use { st ->
            st.executeQuery("select $NOW_MS").use { it.next(); it.getLong(1) }
        }
    }
}
