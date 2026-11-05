package net.blocks168.pgecs.query

import net.blocks168.pgecs.ir.AggregateFn
import net.blocks168.pgecs.ir.AnyEntity
import net.blocks168.pgecs.ir.AnyRelation
import net.blocks168.pgecs.ir.ArithOp
import net.blocks168.pgecs.ir.CompareOp
import net.blocks168.pgecs.ir.ComponentKind
import net.blocks168.pgecs.ir.ComponentRef
import net.blocks168.pgecs.ir.EntityRef
import net.blocks168.pgecs.ir.EntityVar
import net.blocks168.pgecs.ir.Expr
import net.blocks168.pgecs.ir.Functions
import net.blocks168.pgecs.ir.NullOrder
import net.blocks168.pgecs.ir.QueryIr
import net.blocks168.pgecs.ir.Term
import net.blocks168.pgecs.ir.TypeEntity
import net.blocks168.pgecs.ir.ValueType
import net.blocks168.pgecs.ir.Variable
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A reference evaluator of the first-level IR, straight from its documented semantics (Kotlin
 * semantics for expressions, flecs-style bags of matches, wildcards that never multiply rows), over
 * an in-memory copy of the data. The randomized test checks the engine against it.
 *
 * Deliberately naive: nested loops, no optimisation, nothing shared with the engine.
 */
class Oracle(private val rows: List<Row>, private val typeEntities: Map<String, UUID> = emptyMap()) {
    /** One component row. [value] is a [Long] for integer components, a [Map] for JSON objects, null for tags. */
    data class Row(val entity: UUID, val rel: String, val target: UUID, val value: Any?)

    private val nil = UUID(0, 0)

    private class Binding(val values: Map<Variable, Any?>) {
        operator fun get(v: Variable) = values[v]
        fun with(v: Variable, x: Any?) = Binding(values + (v to x))
        override fun equals(other: Any?) = other is Binding && values == other.values
        override fun hashCode() = values.hashCode()
    }

    fun run(q: QueryIr, args: List<Any?>): List<List<Any?>> {
        val matches = solve(q.terms, Binding(emptyMap()), args)
        val aggregate = q.isAggregate
        var out: List<Pair<List<Any?>, List<Any?>>> = if (aggregate) {
            val groups = LinkedHashMap<List<Any?>, MutableList<Binding>>()
            if (q.groupBy.isEmpty()) groups[emptyList()] = mutableListOf()
            for (b in matches) groups.getOrPut(q.groupBy.map { eval(it, b, args, null) }) { mutableListOf() } += b
            groups.values.map { g ->
                q.select.map { eval(it.expr, g.firstOrNull() ?: Binding(emptyMap()), args, g) } to
                    q.orderBy.map { eval(it.expr, g.firstOrNull() ?: Binding(emptyMap()), args, g) }
            }
        } else {
            matches.map { b -> q.select.map { eval(it.expr, b, args, null) } to q.orderBy.map { eval(it.expr, b, args, null) } }
        }
        if (q.distinct) out = out.distinctBy { it.first }
        if (q.orderBy.isNotEmpty()) {
            out = out.sortedWith { a, b ->
                for ((i, k) in q.orderBy.withIndex()) {
                    val x = a.second[i]
                    val y = b.second[i]
                    val c = when {
                        x == null && y == null -> 0
                        x == null -> if (k.nulls == NullOrder.FIRST) -1 else 1
                        y == null -> if (k.nulls == NullOrder.FIRST) 1 else -1
                        else -> compare(x, y).let { if (k.descending) -it else it }
                    }
                    if (c != 0) return@sortedWith c
                }
                0
            }
        }
        val offset = (q.offset?.let { eval(it, Binding(emptyMap()), args, null) } as Long?)?.toInt() ?: 0
        val limit = (q.limit?.let { eval(it, Binding(emptyMap()), args, null) } as Long?)?.toInt() ?: Int.MAX_VALUE
        return out.drop(offset).take(limit).map { it.first }
    }

    /** All matches of a conjunction: mandatory matches, then optional groups, then filters. */
    private fun solve(terms: List<Term>, start: Binding, args: List<Any?>): List<Binding> {
        var cur = listOf(start)
        for (m in terms.filterIsInstance<Term.Match>()) cur = cur.flatMap { match(m, it, args) }
        for (o in terms.filterIsInstance<Term.Optional>()) {
            cur = cur.flatMap { b ->
                val r = solve(o.terms, b, args)
                if (r.isNotEmpty()) {
                    r
                } else {
                    val fresh = boundBy(o.terms).filter { it !in b.values }
                    listOf(fresh.fold(b) { acc, v -> acc.with(v, null) })
                }
            }
        }
        for (f in terms.filterIsInstance<Term.Filter>()) cur = cur.filter { eval(f.predicate, it, args, null) == true }
        return cur
    }

    private fun boundBy(terms: List<Term>): Set<Variable> = buildSet {
        for (t in terms) {
            when (t) {
                is Term.Match -> listOfNotNull(t.subject as? EntityVar, t.target as? EntityVar, t.value).forEach(::add)
                is Term.Optional -> addAll(boundBy(t.terms))
                is Term.Filter -> {}
            }
        }
    }

    private fun match(m: Term.Match, b: Binding, args: List<Any?>): List<Binding> {
        val c = m.component
        val out = mutableListOf<Binding>()
        for (r in rows) {
            when (c) {
                is AnyRelation -> if (r.target == nil) continue
                is ComponentRef -> if (r.rel != c.name) continue
            }
            var nb = b
            fun position(ref: EntityRef?, actual: UUID): Boolean = when (ref) {
                null -> actual == nil
                AnyEntity -> true
                is EntityVar -> if (ref in nb.values) {
                    nb[ref] == actual
                } else {
                    nb = nb.with(ref, actual)
                    true
                }
                is Expr.Param -> args[ref.index] == actual
                is Expr.Literal -> ref.value == actual
                is TypeEntity -> typeEntities[ref.name] == actual
            }
            if (!position(m.subject, r.entity)) continue
            val pair = c is AnyRelation || (c as ComponentRef).kind == ComponentKind.RELATION
            if (!position(if (pair) m.target ?: AnyEntity else null, r.target)) continue
            m.value?.let { v ->
                if (v in nb.values) {
                    if (nb[v] != r.value) return@let
                } else {
                    nb = nb.with(v, r.value)
                }
            }
            out += nb
        }
        val wildcard = m.subject == AnyEntity || m.target == AnyEntity || c is AnyRelation
        return if (wildcard && m.value == null) out.distinct() else out
    }

    private fun compare(x: Any, y: Any): Int = when {
        x is Number && y is Number -> if (x is Long && y is Long) x.compareTo(y) else x.toDouble().compareTo(y.toDouble())
        x is String && y is String -> x.compareTo(y)
        x is Boolean && y is Boolean -> x.compareTo(y)
        x is UUID && y is UUID -> x.toString().compareTo(y.toString())
        x is Instant && y is Instant -> x.compareTo(y)
        else -> error("cannot compare $x and $y")
    }

    private fun eq(x: Any?, y: Any?): Boolean = when {
        x is Number && y is Number -> compare(x, y) == 0
        else -> x == y
    }

    private fun eval(e: Expr, b: Binding, args: List<Any?>, group: List<Binding>?): Any? = when (e) {
        is Expr.Var -> b[e.variable].let { v -> if (e.type.kind == ValueType.Kind.INSTANT && v is Long) Instant.ofEpochMilli(v) else v }
        is Expr.Field -> {
            var v: Any? = b[e.variable]
            for (k in e.path) v = (v as? Map<*, *>)?.get(k)
            when {
                v == null -> null
                e.type.kind == ValueType.Kind.UUID -> UUID.fromString(v as String)
                e.type.kind == ValueType.Kind.INSTANT -> Instant.ofEpochMilli((v as Number).toLong())
                else -> v
            }
        }
        is Expr.Param -> args[e.index]
        is Expr.Literal -> e.value
        is Expr.Compare -> {
            val l = eval(e.left, b, args, group)
            val r = eval(e.right, b, args, group)
            when (e.op) {
                CompareOp.EQ -> eq(l, r)
                CompareOp.NE -> !eq(l, r)
                else -> {
                    val c = compare(l!!, r!!)
                    when (e.op) {
                        CompareOp.LT -> c < 0
                        CompareOp.LE -> c <= 0
                        CompareOp.GT -> c > 0
                        else -> c >= 0
                    }
                }
            }
        }
        is Expr.And -> e.operands.all { eval(it, b, args, group) == true }
        is Expr.Or -> e.operands.any { eval(it, b, args, group) == true }
        is Expr.Not -> eval(e.operand, b, args, group) != true
        is Expr.IsNull -> eval(e.operand, b, args, group) == null
        is Expr.Coalesce -> e.operands.firstNotNullOfOrNull { eval(it, b, args, group) }
        is Expr.Arith -> {
            val l = eval(e.left, b, args, group) as Long?
            val r = eval(e.right, b, args, group) as Long?
            if (l == null || r == null) {
                null
            } else {
                when (e.op) {
                    ArithOp.PLUS -> l + r
                    ArithOp.MINUS -> l - r
                    ArithOp.TIMES -> l * r
                    ArithOp.DIV -> l / r
                    ArithOp.REM -> l % r
                }
            }
        }
        is Expr.Negate -> (eval(e.operand, b, args, group) as Long?)?.let { -it }
        is Expr.Call -> {
            val a = e.args.map { eval(it, b, args, group) }
            if (a.any { it == null }) {
                null
            } else {
                when (e.fn) {
                    Functions.LOWERCASE -> (a[0] as String).lowercase()
                    Functions.UPPERCASE -> (a[0] as String).uppercase()
                    Functions.TRIM -> (a[0] as String).trim()
                    Functions.LENGTH -> (a[0] as String).length.toLong()
                    Functions.STARTS_WITH -> (a[0] as String).startsWith(a[1] as String)
                    Functions.ENDS_WITH -> (a[0] as String).endsWith(a[1] as String)
                    Functions.CONTAINS -> (a[0] as String).contains(a[1] as String)
                    Functions.NOW -> Instant.now()
                    Functions.CONCAT -> (a[0] as String) + (a[1] as String)
                    else -> error("unknown function ${e.fn}")
                }
            }
        }
        is Expr.Exists -> solve(e.terms, b, args).isNotEmpty()
        is Expr.If -> if (eval(e.condition, b, args, group) == true) eval(e.then, b, args, group) else eval(e.otherwise, b, args, group)
        is Expr.Aggregate -> {
            val g = group ?: error("aggregate outside a group")
            val values = if (e.arg == null) g.map { 1L } else g.mapNotNull { eval(e.arg!!, it, args, null) }
            val vs = if (e.distinct) values.distinct() else values
            when (e.fn) {
                AggregateFn.COUNT -> vs.size.toLong()
                AggregateFn.SUM -> if (vs.isEmpty()) null else vs.sumOf { (it as Long) }
                AggregateFn.MIN -> vs.minWithOrNull { x, y -> compare(x!!, y!!) }
                AggregateFn.MAX -> vs.maxWithOrNull { x, y -> compare(x!!, y!!) }
                AggregateFn.AVG -> if (vs.isEmpty()) null else vs.map { (it as Number).toDouble() }.average()
            }
        }
        is Expr.Arg -> error("unsubstituted argument")
    }.let { if (it is Duration) it else it }
}
