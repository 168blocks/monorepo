package dev.vxcc.alexs168.pgecs.db

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * Transaction counters of one [Database], in total and per transaction name (the `name` argument of
 * `tx` / `read`, `null` counted as `""`). Retries per call site show where contention is.
 */
class TxMetrics internal constructor() {
    /** Counts for one name. */
    class Counts internal constructor() {
        internal val attempts = LongAdder()
        internal val retries = LongAdder()
        internal val failures = LongAdder()
        internal val exhausted = LongAdder()

        fun snapshot() = Snapshot(attempts.sum(), retries.sum(), failures.sum(), exhausted.sum())
    }

    /**
     * [attempts]: runs of a block, including retries. [retries]: runs repeated after a serialization
     * failure or deadlock. [failures]: transactions that failed with any other error. [exhausted]:
     * transactions that still conflicted after the last attempt.
     */
    data class Snapshot(val attempts: Long, val retries: Long, val failures: Long, val exhausted: Long)

    private val total = Counts()
    private val byName = ConcurrentHashMap<String, Counts>()

    fun total(): Snapshot = total.snapshot()

    fun byName(): Map<String, Snapshot> = byName.mapValues { it.value.snapshot() }

    internal fun attempt(name: String?) = bump(name) { it.attempts }
    internal fun retry(name: String?) = bump(name) { it.retries }
    internal fun failure(name: String?) = bump(name) { it.failures }
    internal fun exhausted(name: String?) = bump(name) { it.exhausted }

    private inline fun bump(name: String?, counter: (Counts) -> LongAdder) {
        counter(total).increment()
        counter(byName.computeIfAbsent(name ?: "") { Counts() }).increment()
    }
}
