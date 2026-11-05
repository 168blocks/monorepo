package dev.vxcc.alexs168.pgecs.outbox

import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import dev.vxcc.alexs168.pgecs.Capability
import dev.vxcc.alexs168.pgecs.Component
import dev.vxcc.alexs168.pgecs.Ecs
import dev.vxcc.alexs168.pgecs.EcsTx
import dev.vxcc.alexs168.pgecs.EntityId
import dev.vxcc.alexs168.pgecs.ExclusiveRelation
import dev.vxcc.alexs168.pgecs.Relation
import dev.vxcc.alexs168.pgecs.Sql
import dev.vxcc.alexs168.pgecs.Tag
import dev.vxcc.alexs168.pgecs.ChildOf
import dev.vxcc.alexs168.pgecs.db.SqlStates
import dev.vxcc.alexs168.pgecs.schema.ComponentIds
import dev.vxcc.alexs168.pgecs.schema.IndexSpec
import dev.vxcc.alexs168.pgecs.serial.PgecsJson
import org.postgresql.PGConnection

// ---- the library's components ----------------------------------------------------------------------

/** One JVM running pgecs. [lockKey] is the advisory lock it holds while alive. */
@Serializable
data class ProcessInfo(
    val name: String,
    val kind: String,
    val host: String,
    val version: String,
    val startedAt: Long,
    val lockKey: Long,
)

/** `pgecs:process:v1`, unique on its lock key, so a hash collision fails at registration. */
object ProcessComponent : Component<ProcessInfo>("pgecs:process:v1") {
    init {
        index(IndexSpec("lock_key", listOf("((value->>'lockKey')::bigint)"), unique = true))
    }
}

/** A place that runs effects, a child of its process. */
@Serializable
data class EndpointInfo(val name: String)

object EndpointComponent : Component<EndpointInfo>("pgecs:endpoint:v1")

/** `(pgecs:can, <capability>)` on an endpoint: it runs entries of that capability. */
object Can : Relation("pgecs:can")

/** An outbox entry. Times are epoch milliseconds of the database clock. */
@Serializable
data class OutboxEntry(
    val capability: String,
    val payload: JsonElement = JsonNull,
    val idempotencyKey: String,
    val attempts: Int = 0,
    val notBefore: Long = 0,
    /** After this, an unfinished entry is dead instead of waiting (for an endpoint that is down). */
    val deadline: Long? = null,
    val claimedBy: String? = null,
    val leaseUntil: Long? = null,
    val lastError: String? = null,
)

/** `pgecs:outbox:v1`. Kept out of the change log: the outbox has its own delivery. */
object OutboxComponent : Component<OutboxEntry>("pgecs:outbox:v1")

/** `(pgecs:outbox-target, <endpoint>)`: only that endpoint may run the entry. */
object OutboxTarget : ExclusiveRelation("pgecs:outbox-target")

/** An entry that failed too often or passed its deadline; kept for inspection. */
object OutboxDead : Tag("pgecs:outbox-dead")

// ---- writing entries -----------------------------------------------------------------------------

/**
 * Writes an outbox entry in this transaction: [payload] for any endpoint with [capability], or only
 * for endpoint [to]. Delivery is at least once, after commit; handlers use [idempotencyKey] to run an
 * effect once. Returns the entry's entity.
 */
fun <T> EcsTx.outbox(
    capability: Capability<T>,
    payload: T,
    to: EntityId? = null,
    idempotencyKey: String = UUID.randomUUID().toString(),
    notBefore: java.time.Instant? = null,
    deadline: java.time.Instant? = null,
): EntityId {
    ecs.registry.require(capability)
    val entry = OutboxEntry(
        capability = capability.name,
        payload = PgecsJson.encodeToJsonElement(capability.serializer, payload),
        idempotencyKey = idempotencyKey,
        notBefore = notBefore?.toEpochMilli() ?: 0,
        deadline = deadline?.toEpochMilli(),
    )
    return create {
        this[OutboxComponent] = entry
        if (to != null) set(OutboxTarget, to)
    }
}

// ---- processes, endpoints and delivery -------------------------------------------------------------

/** Settings of outbox delivery. */
data class OutboxConfig(
    /** Entries claimed per round trip. */
    val claimBatch: Int = 50,
    /** How long a claim lasts; an entry whose handler did not finish by then is claimable again. */
    val lease: Duration = Duration.ofMinutes(1),
    /** Polling interval when no notification arrives. */
    val poll: Duration = Duration.ofSeconds(3),
    /** Attempts before an entry is dead. */
    val maxAttempts: Int = 10,
    /** Retry delay after a failed attempt `n`: `min(maxBackoff, baseBackoff * 2^(n-1))`. */
    val baseBackoff: Duration = Duration.ofSeconds(1),
    val maxBackoff: Duration = Duration.ofMinutes(10),
)

/** Another process with the same id is running (it holds the process' advisory lock). */
class ProcessAlreadyRunningException(id: UUID) : IllegalStateException("pgecs process $id is already running elsewhere")

/**
 * Processes, endpoints and outbox delivery of one [Ecs] (pgecs design, *Processes, endpoints and
 * outbox routing*).
 *
 * ```kotlin
 * val process = ecs.outbox.process(config.processId, name = "backend-1", kind = "ktor-backend")
 * process.endpoint(config.endpointId, "backend") { handles(Smtp) { mail -> smtp.send(mail) } }
 * ecs.outbox.start()
 * ```
 *
 * The process takes `pg_advisory_lock(lockKey)` on a dedicated connection and holds it while it runs;
 * that is its liveness, visible to every process in `pg_locks`. The same connection `LISTEN`s on
 * `pgecs_outbox`. Each endpoint claims entries for its capabilities (targeted at it, or untargeted)
 * in one statement with `FOR UPDATE ... SKIP LOCKED`, runs the handlers outside any transaction, and
 * deletes the entry in a second transaction. A failure schedules a retry with backoff; after
 * [OutboxConfig.maxAttempts] attempts, or past its deadline, an entry gets [OutboxDead].
 */
class Outbox internal constructor(private val ecs: Ecs, val config: OutboxConfig) : AutoCloseable {
    @Volatile
    private var process: ProcessHandle? = null
    private val running = AtomicBoolean()
    private val wake = Semaphore(0)
    private val threads = ArrayList<Thread>()

    /** Deliveries that ran, failed and went dead, for metrics. */
    val delivered = AtomicLong()
    val failed = AtomicLong()
    val dead = AtomicLong()

    /** This process, once [process] registered it. */
    val current: ProcessHandle? get() = process

    /**
     * Registers this JVM as process [id] (stable, from config). Throws [ProcessAlreadyRunningException]
     * when another JVM with that id is alive.
     */
    @Synchronized
    fun process(id: UUID, name: String, kind: String, version: String = "dev"): ProcessHandle {
        check(process == null) { "this Ecs already runs as process ${process!!.id}" }
        val key = lockKey(id)
        val lock = ecs.database.openDedicatedConnection()
        try {
            val got = lock.prepareStatement("select pg_try_advisory_lock(?)").use { st ->
                st.setLong(1, key)
                st.executeQuery().use { it.next(); it.getBoolean(1) }
            }
            if (!got) throw ProcessAlreadyRunningException(id)
            lock.createStatement().use { it.execute("listen pgecs_outbox") }
            val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("unknown")
            ecs.tx("pgecs:process-register") {
                ensure(EntityId.of(id)) {
                    this[ProcessComponent] = ProcessInfo(name, kind, host, version, System.currentTimeMillis(), key)
                }
            }
        } catch (t: Throwable) {
            lock.close()
            throw t
        }
        return ProcessHandle(EntityId.of(id), key, lock).also { process = it }
    }

    /** The registered process. */
    inner class ProcessHandle internal constructor(val id: EntityId, val lockKey: Long, internal val lock: Connection) {
        internal val endpoints = ConcurrentHashMap<EntityId, Endpoint>()

        /**
         * Registers endpoint [id] of this process. Its `(pgecs:can, ...)` pairs are rewritten from the
         * handlers in [configure]; entries targeted at it whose capability it no longer has go dead.
         */
        fun endpoint(id: UUID, name: String, configure: Endpoint.Builder.() -> Unit): Endpoint {
            val handlers = Endpoint.Builder().apply(configure).handlers.toMap()
            handlers.keys.forEach { ecs.registry.require(it) }
            val e = Endpoint(EntityId.of(id), name, handlers)
            check(endpoints.putIfAbsent(e.id, e) == null) { "endpoint $id is registered twice" }
            ecs.tx("pgecs:endpoint-register") {
                ensure(e.id) {
                    this[EndpointComponent] = EndpointInfo(name)
                    set(ChildOf, this@ProcessHandle.id)
                    removeAll(Can)
                    handlers.keys.forEach { add(Can, EntityId.of(ComponentIds.of(it.name))) }
                }
                flush()
                Sql.update(
                    connection,
                    """
                    insert into ecs_component (entity, rel)
                    select o.entity, 'pgecs:outbox-dead' from ecs_component o
                      join ecs_component t on t.entity = o.entity and t.rel = 'pgecs:outbox-target' and t.target = ?
                     where o.rel = 'pgecs:outbox:v1' and not (o.value->>'capability' = any(?))
                    on conflict do nothing
                    """.trimIndent(),
                    e.id.id, connection.createArrayOf("text", handlers.keys.map { it.name }.toTypedArray()),
                )
            }
            if (running.get()) launch(e)
            return e
        }
    }

    /** An endpoint: what it handles, and its delivery loop. */
    class Endpoint internal constructor(
        val id: EntityId,
        val name: String,
        internal val handlers: Map<Capability<*>, (Any?) -> Unit>,
    ) {
        class Builder internal constructor() {
            internal val handlers = LinkedHashMap<Capability<*>, (Any?) -> Unit>()

            /** Runs entries of [capability] here; throwing fails the attempt (it is retried). */
            fun <T> handles(capability: Capability<T>, handler: (T) -> Unit) {
                @Suppress("UNCHECKED_CAST")
                handlers[capability] = { payload -> handler(payload as T) }
            }
        }
    }

    // ---- delivery --------------------------------------------------------------------------------

    /** Starts delivery: one loop per endpoint, woken by notifications and by polling. */
    @Synchronized
    fun start() {
        val p = process ?: throw IllegalStateException("register the process (outbox.process(...)) before start()")
        if (!running.compareAndSet(false, true)) return
        threads += Thread.ofVirtual().name("pgecs-outbox-listen").start { listen(p) }
        p.endpoints.values.forEach(::launch)
        // entries past their deadline are dead, also when their endpoint is down
        if (ecs.systems[DEADLINES] == null) {
            ecs.systems.task(DEADLINES, Duration.ofMinutes(1)) { markExpired(this) }
        }
    }

    @Synchronized
    private fun launch(e: Endpoint) {
        threads += Thread.ofVirtual().name("pgecs-outbox-${e.name}").start {
            while (running.get()) {
                val n = try {
                    deliverOnce(e)
                } catch (_: InterruptedException) {
                    return@start
                } catch (t: Throwable) {
                    LOG.log(System.Logger.Level.WARNING, "outbox endpoint ${e.name} failed to claim", t)
                    0
                }
                if (n == 0) {
                    try {
                        wake.tryAcquire(config.poll.toMillis(), TimeUnit.MILLISECONDS)
                        wake.drainPermits()
                    } catch (_: InterruptedException) {
                        return@start
                    }
                }
            }
        }
    }

    private fun listen(p: ProcessHandle) {
        val pg = p.lock.unwrap(PGConnection::class.java)
        while (running.get()) {
            try {
                val notes = pg.getNotifications(config.poll.toMillis().toInt().coerceAtLeast(1))
                if (!notes.isNullOrEmpty()) wake.release(p.endpoints.size.coerceAtLeast(1))
            } catch (t: Throwable) {
                if (!running.get()) return
                LOG.log(System.Logger.Level.WARNING, "outbox listener failed", t)
                try {
                    Thread.sleep(config.poll)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /**
     * Claims and runs up to [OutboxConfig.claimBatch] entries for endpoint [e] now, in this thread.
     * Returns how many it claimed.
     */
    fun deliverOnce(e: Endpoint): Int {
        val me = e.id.id.toString()
        val claimed = claim(e, me)
        // the claims last until about then (measured from after the claim, so a little longer)
        val until = System.nanoTime() + config.lease.toNanos()
        for ((entity, entry) in claimed) {
            val capability = e.handlers.keys.first { it.name == entry.capability }
            val outcome = runCatching {
                val payload = PgecsJson.decodeFromJsonElement(capability.serializer, entry.payload)
                e.handlers.getValue(capability)(payload)
            }
            outcome.exceptionOrNull()?.let { error ->
                failed.incrementAndGet()
                LOG.log(System.Logger.Level.INFO, "outbox entry $entity (${entry.capability}) failed, attempt ${entry.attempts}", error)
            }
            settle(until) { if (outcome.isSuccess) complete(entity, me) else fail(entity, me, entry, outcome.exceptionOrNull()!!) }
        }
        return claimed.size
    }

    /**
     * Records a handler's outcome, retrying conflicts for as long as the claim lasts: the handler has
     * already run, and if its completion were given up (after the transaction's own retries) the entry
     * would run again once the lease lapsed, and a failure would not count towards dying.
     */
    private fun settle(until: Long, record: () -> Unit) {
        var pause = 5L
        while (true) {
            try {
                return record()
            } catch (e: Exception) {
                if (!SqlStates.isRetryable(e) || System.nanoTime() + pause * 1_000_000 >= until) throw e
                Thread.sleep(pause + (Math.random() * pause).toLong())
                pause = minOf(pause * 2, 500)
            }
        }
    }

    private fun claim(e: Endpoint, me: String): List<Pair<EntityId, OutboxEntry>> = ecs.tx("pgecs:outbox-claim") {
        val caps = e.handlers.keys.map { it.name }.toTypedArray()
        connection.prepareStatement(
            """
            update ecs_component o
               set value = o.value || jsonb_build_object('claimedBy', ?::text, 'leaseUntil', $NOW_MS + ?,
                                                         'attempts', coalesce((o.value->>'attempts')::int, 0) + 1)
             where (o.entity, o.rel, o.target) in (
               select c.entity, c.rel, c.target from ecs_component c
                where c.rel = 'pgecs:outbox:v1'
                  and c.value->>'capability' = any(?)
                  and coalesce((c.value->>'notBefore')::bigint, 0) <= $NOW_MS
                  and ((c.value->>'leaseUntil') is null or (c.value->>'leaseUntil')::bigint < $NOW_MS)
                  and not exists (select 1 from ecs_component d where d.entity = c.entity and d.rel = 'pgecs:outbox-dead')
                  and coalesce((select t.target = ? from ecs_component t where t.entity = c.entity and t.rel = 'pgecs:outbox-target'), true)
                order by coalesce((c.value->>'notBefore')::bigint, 0), c.entity
                limit ?
                  for update of c skip locked)
            returning o.entity, o.value::text
            """.trimIndent(),
        ).use { st ->
            st.setString(1, me)
            st.setLong(2, config.lease.toMillis())
            st.setArray(3, connection.createArrayOf("text", caps))
            st.setObject(4, e.id.id)
            st.setInt(5, config.claimBatch)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(EntityId.of(rs.getObject(1) as UUID) to PgecsJson.decodeFromString(OutboxEntry.serializer(), rs.getString(2)))
                }
            }
        }
    }

    /** Deletes the entry, if this endpoint still holds its claim (a lapsed claim may run twice: at least once). */
    private fun complete(entity: EntityId, me: String) {
        ecs.tx("pgecs:outbox-complete") {
            Sql.update(
                connection,
                "delete from ecs_entity where id = ? and exists (select 1 from ecs_component where entity = ? and rel = 'pgecs:outbox:v1' and value->>'claimedBy' = ?)",
                entity.id, entity.id, me,
            )
        }
        delivered.incrementAndGet()
    }

    private fun fail(entity: EntityId, me: String, entry: OutboxEntry, error: Throwable) {
        // the claim already counted this attempt
        val attempts = entry.attempts
        val backoff = minOf(config.maxBackoff.toMillis(), config.baseBackoff.toMillis() shl minOf(attempts - 1, 30))
        val giveUp = attempts >= config.maxAttempts
        ecs.tx("pgecs:outbox-fail") {
            val n = Sql.update(
                connection,
                """
                update ecs_component set value = value || jsonb_build_object('claimedBy', null, 'leaseUntil', null,
                                                  'notBefore', $NOW_MS + ?, 'lastError', ?::text)
                 where entity = ? and rel = 'pgecs:outbox:v1' and value->>'claimedBy' = ?
                """.trimIndent(),
                backoff, (error.toString()).take(1000), entity.id, me,
            )
            if (n > 0 && giveUp) {
                update(entity) { +OutboxDead }
                dead.incrementAndGet()
            }
        }
    }

    /** Marks entries past their deadline dead. */
    fun markExpired(tx: EcsTx): Int = Sql.update(
        tx.connection,
        """
        insert into ecs_component (entity, rel)
        select entity, 'pgecs:outbox-dead' from ecs_component
         where rel = 'pgecs:outbox:v1' and (value->>'deadline')::bigint < $NOW_MS
        on conflict do nothing
        """.trimIndent(),
    ).also { dead.addAndGet(it.toLong()) }

    /** Whether the process [id] is alive: some session holds its advisory lock. */
    fun isAlive(id: UUID): Boolean = ecs.read("pgecs:process-alive") {
        Sql.exists(
            connection,
            "select 1 from pg_locks where locktype = 'advisory' and granted and objsubid = 1 " +
                "and ((classid::bigint << 32) | objid::bigint) = ?",
            lockKey(id),
        )
    }

    /** Stops delivery and releases the process lock. */
    @Synchronized
    override fun close() {
        running.set(false)
        threads.forEach(Thread::interrupt)
        threads.forEach { it.join(Duration.ofSeconds(10)) }
        threads.clear()
        process?.lock?.let { runCatching { it.close() } }
        process = null
    }

    companion object {
        /** The library's types of this layer, registered by every Ecs and freeze. */
        val TYPES: List<dev.vxcc.alexs168.pgecs.ComponentType>
            get() = listOf(ProcessComponent, EndpointComponent, Can, OutboxComponent, OutboxTarget, OutboxDead)

        /** The system that marks entries past their deadline dead. */
        const val DEADLINES = "pgecs:outbox-deadlines"

        private const val NOW_MS = "(extract(epoch from now()) * 1000)::bigint"
        private val LOG: System.Logger = System.getLogger(Outbox::class.java.name)

        /** The 64-bit advisory lock key of process [id]. */
        @JvmStatic
        fun lockKey(id: UUID): Long = id.mostSignificantBits xor id.leastSignificantBits
    }
}
