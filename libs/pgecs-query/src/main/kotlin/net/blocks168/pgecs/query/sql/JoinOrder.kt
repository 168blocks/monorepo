package net.blocks168.pgecs.query.sql

import net.blocks168.pgecs.query.plan.CmpOp
import net.blocks168.pgecs.query.plan.Column
import net.blocks168.pgecs.query.plan.Expr
import net.blocks168.pgecs.query.plan.Pred
import net.blocks168.pgecs.query.plan.Scan
import net.blocks168.pgecs.query.plan.Stats
import net.blocks168.pgecs.query.plan.aliasesOf

/**
 * Greedy join ordering by estimated cardinality: start with the scan that yields the fewest rows,
 * then repeatedly add the scan that keeps the intermediate result smallest given what is already
 * joined. Scans connected to nothing joined yet are estimated at their full size, so cross products
 * come last.
 *
 * Estimates come from [Stats]: a scan whose subject or target is already known returns about
 * `rows(rel) / entities` rows per known entity (at most one, by the primary key, when both are known);
 * equality filters on its value keep 1%, range filters 30%, anything else half.
 */
object JoinOrder {
    fun order(scans: List<Scan>, conds: List<Pred>, stats: Stats, outer: Set<Int>): List<Scan> {
        if (scans.size <= 1) return scans
        val placed = outer.toMutableSet()
        val left = scans.toMutableList()
        val out = mutableListOf<Scan>()
        while (left.isNotEmpty()) {
            val next = left.minBy { estimate(it, conds, stats, placed) }
            left -= next
            out += next
            placed += next.alias
        }
        return out
    }

    /** Estimated rows [s] adds per row of the [placed] scans. */
    fun estimate(s: Scan, conds: List<Pred>, stats: Stats, placed: Set<Int>): Double {
        val base = when (s.rel) {
            null -> stats.entities
            else -> stats.rows(s.rel)
        }
        val per = when (s.rel) {
            null -> 1.0
            else -> stats.perEntity(s.rel)
        }
        val own = setOf(s.alias)
        // a parameter or a column of a joined scan; the nil target of plain components says nothing
        fun known(e: Expr?): Boolean = e != null && e != Expr.Nil && aliasesOf(e).let { s.alias !in it && placed.containsAll(it) }
        val subject = known(s.subject)
        val target = known(s.target)
        var rows = when {
            subject && target -> minOf(per, 1.0)
            subject || target -> per
            s.distinct -> base / 2
            else -> base
        }
        for (c in conds) {
            val refs = aliasesOf(c)
            if (s.alias !in refs || !placed.containsAll(refs - own)) continue
            // the scan's own entity/target conditions are already counted above
            if (c is Pred.Cmp && c.left is Expr.Col && (c.left as Expr.Col).alias == s.alias &&
                (c.left as Expr.Col).column != Column.VALUE
            ) continue
            rows *= when (c) {
                is Pred.Cmp -> when (c.op) {
                    CmpOp.EQ, CmpOp.NOT_DISTINCT -> 0.01
                    CmpOp.LT, CmpOp.LE, CmpOp.GT, CmpOp.GE -> 0.3
                    else -> 0.9
                }
                is Pred.InList -> 0.05
                is Pred.Not -> 0.9
                else -> 0.5
            }
        }
        return rows
    }
}
