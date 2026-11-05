package dev.vxcc.alexs168.pgecs.query.opt

import dev.vxcc.alexs168.pgecs.query.plan.Block
import dev.vxcc.alexs168.pgecs.query.plan.Column
import dev.vxcc.alexs168.pgecs.query.plan.Expr
import dev.vxcc.alexs168.pgecs.query.plan.LogicalQuery
import dev.vxcc.alexs168.pgecs.query.plan.Pred

/**
 * A deterministic single pass over the core rules that need no search: flattening, constant folding,
 * duplicate conjuncts and disjuncts, and dropping `has` probes that a mandatory scan of the same or an
 * enclosing block already guarantees.
 *
 * It runs before equality saturation, so the e-graph starts small, and it is the whole optimisation
 * when egglog cannot run (or runs out of time): the plan it produces is always correct.
 */
object Simplifier {
    fun simplify(q: LogicalQuery): LogicalQuery = q.copy(root = block(q.root, emptySet()))

    /** What mandatory scans guarantee: (subject, rel, target), target null for "some target". */
    private data class Fact(val subject: Expr, val rel: String, val target: Expr?)

    private fun factsOf(b: Block): Set<Fact> = buildSet {
        for (s in b.scans) {
            val subject = s.subject ?: continue
            val rel = s.rel ?: continue
            add(Fact(subject, rel, s.target ?: Expr.Col(s.alias, Column.TARGET)))
            add(Fact(subject, rel, null))
        }
    }

    private fun block(b: Block, outer: Set<Fact>): Block {
        val facts = outer + factsOf(b)
        return b.copy(
            where = pred(b.where, facts),
            // optional groups see the enclosing facts, but their own scans are not facts outside them
            optionals = b.optionals.map { o -> o.copy(where = pred(o.where, facts + factsOf(o)), optionals = o.optionals) },
        )
    }

    private fun pred(p: Pred, facts: Set<Fact>): Pred = when (p) {
        is Pred.And -> Pred.and(p.items.map { pred(it, facts) }.distinct())
        is Pred.Or -> Pred.or(p.items.map { pred(it, facts) }.distinct())
        is Pred.Not -> when (val inner = pred(p.pred, facts)) {
            Pred.True -> Pred.False
            Pred.False -> Pred.True
            is Pred.Not -> inner.pred
            is Pred.Cmp -> inner.copy(op = inner.op.negated)
            else -> Pred.Not(inner)
        }
        is Pred.Has -> {
            val rels = p.rels.distinct()
            val subject = p.subject
            if (subject != null && rels.any { Fact(subject, it, p.target) in facts }) Pred.True else p.copy(rels = rels)
        }
        is Pred.Exists -> {
            val inner = block(p.block, facts)
            if (inner.where == Pred.False) Pred.False else Pred.Exists(inner)
        }
        else -> p
    }
}
