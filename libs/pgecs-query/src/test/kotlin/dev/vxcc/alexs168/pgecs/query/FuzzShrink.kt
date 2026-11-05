package dev.vxcc.alexs168.pgecs.query

import dev.vxcc.alexs168.pgecs.ir.AnyEntity
import dev.vxcc.alexs168.pgecs.ir.EntityRef
import dev.vxcc.alexs168.pgecs.ir.EntityVar
import dev.vxcc.alexs168.pgecs.ir.Expr
import dev.vxcc.alexs168.pgecs.ir.Projection
import dev.vxcc.alexs168.pgecs.ir.QueryIr
import dev.vxcc.alexs168.pgecs.ir.Term
import dev.vxcc.alexs168.pgecs.ir.TypeEntity
import dev.vxcc.alexs168.pgecs.ir.ValueType
import dev.vxcc.alexs168.pgecs.ir.ValueVar
import dev.vxcc.alexs168.pgecs.ir.Variable
import dev.vxcc.alexs168.pgecs.ir.children
import dev.vxcc.alexs168.pgecs.ir.variables

/**
 * Greedy shrinking of a failing query: drops terms, projections, sort keys and paging, flattens
 * optional groups and replaces predicates by their parts, keeping each step that still fails.
 * Unused parameters and variables are removed and renumbered, so every candidate is a valid query.
 */
object FuzzShrink {
    class Candidate(val query: QueryIr, val args: List<Any?>)

    /** The smallest query reachable from [start] for which [fails] still returns a failure message. */
    fun shrink(start: Candidate, maxSteps: Int = 400, fails: (Candidate) -> String?): Pair<Candidate, String?> {
        var cur = start
        var msg = fails(cur)
        var steps = 0
        var progress = true
        while (progress && steps < maxSteps) {
            progress = false
            for (c in candidates(cur.query)) {
                if (++steps > maxSteps) break
                val next = runCatching { compact(c, cur.args) }.getOrNull() ?: continue
                if (next.query.canonicalText.length >= cur.query.canonicalText.length) continue
                val m = runCatching { fails(next) }.getOrElse { "check threw $it" } ?: continue
                cur = next
                msg = m
                progress = true
                break
            }
        }
        return cur to msg
    }

    private fun candidates(q: QueryIr): Sequence<QueryIr> = sequence {
        for (i in q.terms.indices) yield(q.copy(terms = q.terms.filterIndexed { j, _ -> j != i }))
        for ((i, t) in q.terms.withIndex()) {
            when (t) {
                is Term.Optional -> {
                    yield(q.copy(terms = q.terms.take(i) + t.terms + q.terms.drop(i + 1)))
                    if (t.terms.size > 1) {
                        for (j in t.terms.indices) yield(q.copy(terms = q.terms.replace(i, Term.Optional(t.terms.filterIndexed { k, _ -> k != j }))))
                    }
                }
                is Term.Filter -> for (p in smaller(t.predicate)) yield(q.copy(terms = q.terms.replace(i, Term.Filter(p))))
                is Term.Match -> {
                    if (t.subject !is AnyEntity && t.subject !is EntityVar) yield(q.copy(terms = q.terms.replace(i, t.copy(subject = AnyEntity))))
                    if (t.target != null && t.target !is AnyEntity) yield(q.copy(terms = q.terms.replace(i, t.copy(target = AnyEntity))))
                }
            }
        }
        if (q.select.size > 1) for (i in q.select.indices) yield(q.copy(select = q.select.filterIndexed { j, _ -> j != i }))
        for ((i, p) in q.select.withIndex()) {
            if (p.expr !in q.groupBy) for (e in p.expr.children().filter { it.type.kind != ValueType.Kind.JSON }) yield(q.copy(select = q.select.replace(i, Projection(p.name, e))))
        }
        if (q.orderBy.isNotEmpty()) {
            yield(q.copy(orderBy = emptyList(), limit = null, offset = null))
            for (i in q.orderBy.indices) yield(q.copy(orderBy = q.orderBy.filterIndexed { j, _ -> j != i }))
        }
        if (q.limit != null) yield(q.copy(limit = null))
        if (q.offset != null) yield(q.copy(offset = null))
        if (q.distinct) yield(q.copy(distinct = false))
        for (i in q.groupBy.indices) {
            val g = q.groupBy[i]
            yield(q.copy(groupBy = q.groupBy.filterIndexed { j, _ -> j != i }, select = q.select.filter { it.expr != g }))
        }
    }

    /** Simpler predicates than [p]: its boolean parts and constants. */
    private fun smaller(p: Expr): List<Expr> = buildList {
        add(Expr.Literal.of(true))
        add(Expr.Literal.of(false))
        when (p) {
            is Expr.And -> p.operands.indices.forEach { i -> add(dev.vxcc.alexs168.pgecs.ir.and(p.operands.filterIndexed { j, _ -> j != i })) }
            is Expr.Or -> p.operands.indices.forEach { i -> add(dev.vxcc.alexs168.pgecs.ir.or(p.operands.filterIndexed { j, _ -> j != i })) }
            else -> {}
        }
        addAll(p.children().filter { it.type == ValueType.BOOL && it !is Expr.Literal })
    }

    private fun <T> List<T>.replace(i: Int, x: T) = toMutableList().also { it[i] = x }

    // ---- removing unused parameters and variables ---------------------------------------------------

    /** [q] without unused parameters and variables, renumbered and validated. */
    fun compact(q: QueryIr, args: List<Any?>): Candidate {
        val usedVars = buildSet {
            fun term(t: Term) {
                when (t) {
                    is Term.Match -> listOfNotNull(t.subject as? EntityVar, t.target as? EntityVar, t.value).forEach(::add)
                    is Term.Filter -> addAll(t.predicate.variables())
                    is Term.Optional -> t.terms.forEach(::term)
                }
            }
            q.terms.forEach(::term)
            (q.select.map { it.expr } + q.groupBy + q.orderBy.map { it.expr }).forEach { addAll(it.variables()) }
            q.lock?.let { add(it.subject) }
        }
        val usedParams = sortedSetOf<Int>()
        q.terms.forEach { collectParams(it, usedParams) }
        (q.select.map { it.expr } + q.groupBy + q.orderBy.map { it.expr } + listOfNotNull(q.limit, q.offset)).forEach { collectParams(it, usedParams) }

        val keptVars = q.variables.filter { it in usedVars }
        val varMap: Map<Variable, Variable> = keptVars.withIndex().associate { (i, v) ->
            v to when (v) {
                is EntityVar -> EntityVar(i, v.name)
                is ValueVar -> ValueVar(i, v.name, v.type)
            }
        }
        val paramMap = usedParams.withIndex().associate { (i, p) -> p to i }
        val r = Remap(varMap, paramMap)
        val out = QueryIr(
            variables = keptVars.map { varMap.getValue(it) },
            params = usedParams.map { q.params[it] },
            terms = q.terms.map(r::term),
            select = q.select.map { it.copy(expr = r.expr(it.expr)) },
            distinct = q.distinct,
            groupBy = q.groupBy.map(r::expr),
            orderBy = q.orderBy.map { it.copy(expr = r.expr(it.expr)) },
            limit = q.limit?.let(r::expr),
            offset = q.offset?.let(r::expr),
            lock = q.lock?.let { it.copy(subject = varMap.getValue(it.subject) as EntityVar) },
        ).validate()
        return Candidate(out, usedParams.map { args[it] })
    }

    private fun collectParams(e: Expr, out: MutableSet<Int>) {
        if (e is Expr.Param) out += e.index
        if (e is Expr.Exists) e.terms.forEach { collectParams(it, out) } else e.children().forEach { collectParams(it, out) }
    }

    private fun collectParams(t: Term, out: MutableSet<Int>) {
        when (t) {
            is Term.Match -> listOfNotNull(t.subject as? Expr, t.target as? Expr).forEach { collectParams(it, out) }
            is Term.Filter -> collectParams(t.predicate, out)
            is Term.Optional -> t.terms.forEach { collectParams(it, out) }
        }
    }

    private class Remap(val vars: Map<Variable, Variable>, val params: Map<Int, Int>) {
        fun v(x: Variable) = vars[x] ?: error("variable $x removed but still used")

        fun entity(e: EntityRef): EntityRef = when (e) {
            is EntityVar -> v(e) as EntityVar
            is Expr.Param -> Expr.Param(params.getValue(e.index), e.type)
            AnyEntity, is TypeEntity, is Expr.Literal -> e
        }

        fun term(t: Term): Term = when (t) {
            is Term.Match -> Term.Match(entity(t.subject), t.component, t.target?.let(::entity), t.value?.let { v(it) as ValueVar })
            is Term.Filter -> Term.Filter(expr(t.predicate))
            is Term.Optional -> Term.Optional(t.terms.map(::term))
        }

        fun expr(e: Expr): Expr = when (e) {
            is Expr.Var -> Expr.Var(v(e.variable))
            is Expr.Field -> Expr.Field(v(e.variable) as ValueVar, e.path, e.type)
            is Expr.Param -> Expr.Param(params.getValue(e.index), e.type)
            is Expr.Literal, is Expr.Arg -> e
            is Expr.Compare -> Expr.Compare(e.op, expr(e.left), expr(e.right))
            is Expr.And -> Expr.And(e.operands.map(::expr))
            is Expr.Or -> Expr.Or(e.operands.map(::expr))
            is Expr.Not -> Expr.Not(expr(e.operand))
            is Expr.IsNull -> Expr.IsNull(expr(e.operand))
            is Expr.Coalesce -> Expr.Coalesce(e.operands.map(::expr))
            is Expr.If -> Expr.If(expr(e.condition), expr(e.then), expr(e.otherwise))
            is Expr.Arith -> Expr.Arith(e.op, expr(e.left), expr(e.right))
            is Expr.Negate -> Expr.Negate(expr(e.operand))
            is Expr.Call -> Expr.Call(e.fn, e.args.map(::expr), e.type)
            is Expr.Exists -> Expr.Exists(e.terms.map(::term))
            is Expr.Aggregate -> Expr.Aggregate(e.fn, e.arg?.let(::expr), e.distinct)
        }
    }
}
