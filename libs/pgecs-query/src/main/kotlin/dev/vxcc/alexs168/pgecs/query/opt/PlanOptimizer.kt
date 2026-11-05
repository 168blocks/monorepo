package dev.vxcc.alexs168.pgecs.query.opt

import dev.vxcc.alexs168.pgecs.query.plan.LogicalQuery
import dev.vxcc.alexs168.pgecs.query.plan.Pred
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Optimises a plan: the [Simplifier] always, then equality saturation ([EqsatOptimizer]) within
 * [timeoutMillis].
 *
 * If egglog fails, the simplified plan is used, which is correct. If it takes longer than the
 * timeout (typically while the JVM is still warming egglog up), the simplified plan is returned at
 * once and [Result.late] completes with the optimised plan when egglog is done, so the caller can swap
 * it in for later executions.
 */
class PlanOptimizer(
    private val eqsat: EqsatOptimizer?,
    private val executor: Executor,
    val timeoutMillis: Long = 250,
) {
    enum class By { SIMPLIFIER, EQSAT }

    class Result(
        val plan: LogicalQuery,
        val by: By,
        /** Time spent in egglog, when it finished in time. */
        val eqsatNanos: Long,
        /** When egglog ran out of time: completes with its plan (or null if it failed). */
        val late: CompletableFuture<LogicalQuery?>?,
        /** Why egglog's result is not used, if it is not. */
        val failure: Throwable?,
    )

    fun optimize(q: LogicalQuery): Result {
        val simple = Simplifier.simplify(q)
        if (eqsat == null || !worthSaturating(simple)) return Result(simple, By.SIMPLIFIER, 0, null, null)
        val start = System.nanoTime()
        val future = CompletableFuture.supplyAsync({ Simplifier.simplify(eqsat.optimize(simple)) }, executor)
        return try {
            val plan = future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            Result(plan, By.EQSAT, System.nanoTime() - start, null, null)
        } catch (e: TimeoutException) {
            Result(simple, By.SIMPLIFIER, 0, future.handle { p, _ -> p }, e)
        } catch (e: java.util.concurrent.ExecutionException) {
            Result(simple, By.SIMPLIFIER, 0, null, e.cause ?: e)
        }
    }

    /** Equality saturation has nothing to do without at least two predicates or a negation to look at. */
    private fun worthSaturating(q: LogicalQuery): Boolean {
        var nodes = 0
        fun visit(p: Pred) {
            when (p) {
                is Pred.And -> { nodes += p.items.size; p.items.forEach(::visit) }
                is Pred.Or -> { nodes += 2; p.items.forEach(::visit) }
                is Pred.Not -> { nodes += 2; visit(p.pred) }
                is Pred.Exists -> { nodes += 1; visit(p.block.where) }
                else -> {}
            }
        }
        visit(q.root.where)
        return nodes >= 2
    }
}
