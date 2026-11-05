package net.blocks168.pgecs.eqsat

import java.util.concurrent.ArrayBlockingQueue

/**
 * A fixed pool of [Egglog] instances that share a [prelude] (datatypes, rules, cost annotations).
 *
 * Every instance loads the prelude into its session e-graph once. [query] then borrows an instance and
 * runs the query between `(push)` and `(pop)`, so the rules are not parsed again for every query.
 * Callers block while all instances are busy. Instances are created lazily, so an unused pool costs
 * nothing.
 *
 * egglog's `(pop)` does not give back everything a query allocated: the session grows by ~20 KiB per
 * query (with the join-reordering workload) and gets slower as it grows. So each instance rebuilds its
 * session from the prelude after [rebuildEvery] queries.
 *
 * Thread-safe.
 */
class EgglogPool(val size: Int, val prelude: String, val rebuildEvery: Int = 200) {
    init {
        require(size > 0) { "size must be positive" }
        require(rebuildEvery > 0) { "rebuildEvery must be positive" }
    }

    private val idle = ArrayBlockingQueue<Slot>(size)

    init {
        repeat(size) { idle.add(Slot()) }
    }

    private inner class Slot {
        private var egglog: Egglog? = null
        private var uses = 0

        fun get(): Egglog {
            val current = egglog ?: return Egglog.create().also {
                it.runInSession(prelude)
                egglog = it
            }
            if (++uses >= rebuildEvery) {
                uses = 0
                discard()
            }
            return current
        }

        /** Rebuilds the session from the prelude after a failed query (or drops the instance if that fails). */
        fun discard() {
            val it = egglog ?: return
            try {
                it.resetSession()
                it.runInSession(prelude)
            } catch (e: Throwable) {
                egglog = null
                throw e
            }
        }
    }

    /**
     * Runs [query] (for example `(let q ...) (run-schedule ...) (extract q)`) on top of the prelude and
     * returns its outputs. Nothing the query adds stays in the e-graph.
     */
    fun query(query: String): String = withSession { it.runInSession("(push)\n$query\n(pop)") }

    /**
     * Borrows an instance whose session holds exactly the prelude. [block] must leave it that way
     * (balance its pushes and pops); if it throws, the session is rebuilt from the prelude.
     */
    fun <T> withSession(block: (Egglog) -> T): T {
        val slot = idle.take()
        try {
            val egglog = slot.get()
            return try {
                block(egglog)
            } catch (e: Throwable) {
                runCatching { slot.discard() }.onFailure(e::addSuppressed)
                throw e
            }
        } finally {
            idle.put(slot)
        }
    }
}
