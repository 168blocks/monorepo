package net.blocks168.pgecs.query.sql

import net.blocks168.pgecs.query.plan.AggFn
import net.blocks168.pgecs.query.plan.Block
import net.blocks168.pgecs.query.plan.CmpOp
import net.blocks168.pgecs.query.plan.Column
import net.blocks168.pgecs.query.plan.Expr
import net.blocks168.pgecs.query.plan.Fn
import net.blocks168.pgecs.query.plan.Kind
import net.blocks168.pgecs.query.plan.LogicalQuery
import net.blocks168.pgecs.query.plan.Pred
import net.blocks168.pgecs.query.plan.Scan
import net.blocks168.pgecs.query.plan.Stats
import net.blocks168.pgecs.query.plan.aliasesOf

/** What the caller wants from a query; decides the statement's tail. */
enum class Mode {
    /** All rows. */
    FETCH,

    /** Exactly one row: at most two are read, so a second one can be reported. */
    ONE,

    /** The first row or none: at most one is read. */
    FIRST,

    /** `select count(*)` of the rows. */
    COUNT,
}

/** One positional `?` of a compiled statement, in order. */
sealed interface Bind {
    /** The caller's argument number [index], as [kind] (an array of [kind] when [list]). */
    data class Param(val index: Int, val kind: Kind, val list: Boolean) : Bind

    /** A constant of the plan. */
    data class Const(val value: Any, val kind: Kind) : Bind

    /** The execution time in epoch milliseconds, one value per execution. */
    data object Now : Bind
}

/** A compiled statement: SQL text with `?` placeholders and what to bind to each of them. */
data class CompiledSql(val sql: String, val binds: List<Bind>) {
    override fun toString(): String = sql
}

/**
 * Compiles a [LogicalQuery] into one SQL statement.
 *
 * Two rules matter for performance (pgecs design, *Compiling to SQL*):
 *
 * - **Component names are literals**, values are bind parameters, so Postgres sees `rel = '...'` at
 *   plan time and can use per-component partial indexes.
 * - **Index expressions are matched, never invented.** A field is rendered the same way every time
 *   ([fieldSql]), and a predicate is rendered as written, so it equals a declared index expression
 *   exactly when the predicate means the indexed expression.
 *
 * Scans are joined in an order chosen from [stats] (most selective first, then always the scan that
 * keeps the intermediate result smallest). Postgres reorders joins itself up to `join_collapse_limit`
 * relations; beyond that this order is the one it uses.
 */
class SqlCompiler(private val stats: Stats) {

    fun compile(q: LogicalQuery, mode: Mode = Mode.FETCH): CompiledSql = Rendering(stats, q).render(mode)

    companion object {
        const val NIL = "'00000000-0000-0000-0000-000000000000'::uuid"

        fun quote(s: String): String = "'" + s.replace("'", "''") + "'"

        fun quoteIdent(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

        /**
         * The SQL of field [path] of `[alias].value`, read as [kind]. Registry freeze must render index
         * expressions with this same function, so that predicates match them:
         *
         * - text: `value->'a'->>'b'`, the whole value `value#>>'{}'`
         * - numbers, booleans, uuids: the text form cast, `(value->>'n')::bigint`; text first, because
         *   a JSON `null` cannot be cast to a number but reads as SQL null as text
         * - JSON: `value->'a'->'b'`, the whole value `value`
         */
        fun fieldSql(alias: String, path: List<Any>, kind: Kind): String {
            val base = "$alias.value"
            fun key(k: Any) = if (k is Int) "$k" else quote(k as String)
            if (kind == Kind.JSON) return base + path.joinToString("") { "->${key(it)}" }
            val text = if (path.isEmpty()) {
                "$base#>>'{}'"
            } else {
                base + path.dropLast(1).joinToString("") { "->${key(it)}" } + "->>" + key(path.last())
            }
            return when (kind) {
                Kind.STRING -> text
                else -> "($text)::${kind.sqlType}"
            }
        }
    }
}

/** One compilation: aliases, bind placeholders and the text. */
private class Rendering(private val stats: Stats, private val q: LogicalQuery) {
    private val binds = mutableListOf<Bind>()
    private var nextAlias = maxAlias(q) + 1
    private var nextProbe = 0

    private fun alias(a: Int) = "c$a"

    /** A placeholder that [finish] turns into `?`, so that text can be assembled in any order. */
    private fun bind(b: Bind): String {
        binds += b
        return "\u0000${binds.size - 1}\u0000"
    }

    private fun finish(text: String): CompiledSql {
        val out = StringBuilder()
        val ordered = mutableListOf<Bind>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\u0000') {
                val end = text.indexOf('\u0000', i + 1)
                ordered += binds[text.substring(i + 1, end).toInt()]
                out.append('?')
                i = end + 1
            } else {
                out.append(c)
                i++
            }
        }
        return CompiledSql(out.toString(), ordered)
    }

    fun render(mode: Mode): CompiledSql {
        val (from, where) = block(q.root, emptySet())
        val whereSql = if (where.isEmpty()) "" else "\nwhere " + where.joinToString("\n  and ") { pred(it) }

        val items = q.select.map { expr(it.expr) to it.name }
        fun ordinalOf(e: Expr): Int? = q.select.indexOfFirst { it.expr == e }.takeIf { it >= 0 }?.plus(1)

        val tail = StringBuilder()
        if (q.groupBy.isNotEmpty()) {
            tail.append("\ngroup by ").append(q.groupBy.joinToString(", ") { e -> ordinalOf(e)?.toString() ?: expr(e) })
        }
        if (q.having != Pred.True) tail.append("\nhaving ").append(pred(q.having))
        val orderBy = if (q.orderBy.isEmpty()) {
            ""
        } else {
            "\norder by " + q.orderBy.joinToString(", ") { k ->
                val e = ordinalOf(k.expr)?.toString() ?: expr(k.expr)
                val collate = if (k.expr.kind == Kind.STRING) " collate \"C\"" else ""
                val nulls = when (k.nullsFirst) {
                    null -> ""
                    true -> " nulls first"
                    false -> " nulls last"
                }
                e + collate + (if (k.descending) " desc" else "") + nulls
            }
        }
        val cap = when (mode) {
            Mode.ONE -> 2
            Mode.FIRST -> 1
            else -> null
        }
        val limit = when {
            q.limit == null && cap == null -> ""
            q.limit == null -> "\nlimit $cap"
            cap == null -> "\nlimit " + expr(q.limit)
            else -> "\nlimit least(${expr(q.limit)}, $cap)"
        }
        val offset = q.offset?.let { "\noffset " + expr(it) } ?: ""
        val lock = q.lock?.let { l -> "\nfor ${l.mode} of ${alias(l.alias)}" + (if (l.wait.isEmpty()) "" else " ${l.wait}") } ?: ""

        val distinct = if (q.distinct) "distinct " else ""
        val list = items.joinToString(", ") { (sql, name) -> "$sql as ${SqlCompiler.quoteIdent(name)}" }
        val sql = when (mode) {
            Mode.COUNT -> if (!q.distinct && !q.isAggregate && q.limit == null && q.offset == null) {
                "select count(*)\nfrom $from$whereSql"
            } else {
                val inner = if (q.distinct || q.isAggregate) list else "1"
                "select count(*) from (\nselect $distinct$inner\nfrom $from$whereSql$tail$orderBy$limit$offset\n) q"
            }
            else -> "select $distinct$list\nfrom $from$whereSql$tail$orderBy$limit$offset$lock"
        }
        return finish(sql)
    }

    // ---- blocks -----------------------------------------------------------------------------------

    /**
     * The `from` clause of [b] (its scans joined, then its optional groups left-joined) and the
     * conjuncts left for its `where`. [outer] are the aliases of enclosing blocks, always available.
     */
    private fun block(b: Block, outer: Set<Int>): Pair<String, List<Pred>> {
        // probes of a pair with every end bound match at most one row (the primary key): join them
        val scans = b.scans.toMutableList()
        val rest = mutableListOf<Pred>()
        for (c in Pred.conjuncts(b.where)) {
            if (c is Pred.Has && c.rels.size == 1 && c.subject != null && c.target != null) {
                scans += Scan(nextAlias++, c.rels.single(), c.subject, c.target)
            } else {
                rest += c
            }
        }
        val conds = scans.flatMap { it.conditions() } + rest
        if (scans.isEmpty()) {
            // only probes and filters: one row, kept or not by the `where`
            val from = StringBuilder("(select 1) one")
            for (o in b.optionals) optional(o, outer, null, from)
            return from.toString() to conds
        }
        val order = JoinOrder.order(scans, conds, stats, outer)
        val where = mutableListOf<Pred>()
        val placed = outer.toMutableSet()
        val from = StringBuilder()
        val remaining = conds.toMutableList()
        order.forEachIndexed { i, s ->
            placed += s.alias
            val here = remaining.filter { c ->
                val refs = aliasesOf(c)
                s.alias in refs && placed.containsAll(refs) && simple(c)
            }
            remaining -= here.toSet()
            if (i == 0) {
                from.append(table(s))
                relCondition(s)?.let { where += it }
                where += here
            } else {
                val on = listOfNotNull(relCondition(s)) + here
                from.append("\njoin ").append(table(s)).append(" on ")
                    .append(if (on.isEmpty()) "true" else on.joinToString(" and ") { pred(it) })
            }
        }
        for (o in b.optionals) optional(o, placed, null, from)
        // conjuncts on optional aliases, or not simple enough for an `on`, stay in `where`
        where += remaining
        return from.toString() to where
    }

    /**
     * Left-joins optional group [o] (and, after it, its nested groups) onto [from]. [available]
     * aliases may be referenced. A nested group is joined after its parent rather than inside it (an
     * `on` inside parentheses cannot see outer tables); [guard], an alias of the parent group, keeps it
     * from matching when the parent did not.
     */
    private fun optional(o: Block, available: Set<Int>, guard: Int?, from: StringBuilder) {
        val scans = o.scans
        val conds = (scans.flatMap { it.conditions() } + Pred.conjuncts(o.where)).toMutableList()
        val own = scans.map { it.alias }.toSet()
        val order = JoinOrder.order(scans, conds, stats, available)
        val inner = StringBuilder()
        val innerPlaced = mutableSetOf<Int>()
        order.forEachIndexed { i, s ->
            innerPlaced += s.alias
            if (i == 0) {
                inner.append(table(s))
            } else {
                val here = conds.filter { c ->
                    val refs = aliasesOf(c)
                    s.alias in refs && innerPlaced.containsAll(refs) && simple(c)
                }
                conds -= here.toSet()
                val on = listOfNotNull(relCondition(s)) + here
                inner.append(" join ").append(table(s)).append(" on ")
                    .append(if (on.isEmpty()) "true" else on.joinToString(" and ") { pred(it) })
            }
        }
        val outerOn = mutableListOf<Pred>()
        relCondition(order.first())?.let { outerOn += it }
        if (guard != null && conds.none { guard in aliasesOf(it) }) {
            outerOn += Pred.Not(Pred.IsNull(Expr.Col(guard, Column.ENTITY)))
        }
        outerOn += conds
        val joined = if (order.size == 1) inner.toString() else "($inner)"
        from.append("\nleft join ").append(joined).append(" on ")
            .append(if (outerOn.isEmpty()) "true" else outerOn.joinToString(" and ") { pred(it) })
        for (n in o.optionals) optional(n, available + own, order.first().alias, from)
    }

    /** Conjuncts that read well in a join's `on`; the rest go to `where` (same meaning for inner joins). */
    private fun simple(p: Pred): Boolean = p is Pred.Cmp || p is Pred.IsNull || p is Pred.InList || p is Pred.Test

    private fun table(s: Scan): String {
        val a = alias(s.alias)
        if (!s.distinct) return "ecs_component $a"
        val cols = listOfNotNull(s.subject?.let { "entity" }, s.target?.let { "target" })
        val where = buildList {
            s.rel?.let { add("rel = ${SqlCompiler.quote(it)}") }
            // a pair (any relation, or a relation with any target); plain components have the nil target
            if (s.rel == null || s.target == null) add("target <> ${SqlCompiler.NIL}")
        }
        return "(select distinct ${cols.joinToString(", ")} from ecs_component where ${where.joinToString(" and ")}) $a"
    }

    /** `rel = '...'` of a plain scan; distinct scans filter inside. */
    private fun relCondition(s: Scan): Pred? {
        if (s.distinct) return null
        return if (s.rel != null) {
            Pred.Cmp(CmpOp.EQ, Expr.Col(s.alias, Column.REL), Expr.Const(s.rel, Kind.STRING))
        } else if (s.target == null) {
            Pred.Cmp(CmpOp.NE, Expr.Col(s.alias, Column.TARGET), Expr.Nil)
        } else {
            null
        }
    }

    // ---- predicates and expressions ---------------------------------------------------------------

    private fun pred(p: Pred): String = when (p) {
        Pred.True -> "true"
        Pred.False -> "false"
        is Pred.Cmp -> {
            val left = p.left
            if (left is Expr.Col && left.column == Column.REL) {
                // component names are literals, never parameters (partial indexes)
                "${alias(left.alias)}.rel ${p.op.sql} ${SqlCompiler.quote((p.right as Expr.Const).value as String)}"
            } else {
                val ordering = p.op in setOf(CmpOp.LT, CmpOp.LE, CmpOp.GT, CmpOp.GE)
                val l = expr(p.left)
                val r = expr(p.right)
                if (ordering && (p.left.kind == Kind.STRING || p.right.kind == Kind.STRING)) {
                    // Kotlin compares strings by UTF-16 code units, close to the "C" collation
                    "($l collate \"C\") ${p.op.sql} $r"
                } else {
                    "$l ${p.op.sql} $r"
                }
            }
        }
        is Pred.IsNull -> "${expr(p.expr)} is null"
        is Pred.InList -> "${expr(p.left)} = any(${expr(p.right)})"
        is Pred.Test -> expr(p.expr)
        is Pred.Has -> {
            val h = "h${nextProbe++}"
            val conds = buildList {
                p.subject?.let { add("$h.entity = ${expr(it)}") }
                when (p.rels.size) {
                    0 -> {}
                    1 -> add("$h.rel = ${SqlCompiler.quote(p.rels.single())}")
                    else -> add("$h.rel in (${p.rels.joinToString(", ") { SqlCompiler.quote(it) }})")
                }
                when (val t = p.target) {
                    null -> if (p.rels.isEmpty()) add("$h.target <> ${SqlCompiler.NIL}")
                    else -> add("$h.target = ${expr(t)}")
                }
            }
            "exists (select 1 from ecs_component $h where ${conds.joinToString(" and ")})"
        }
        is Pred.Exists -> {
            val outer = (0 until nextAlias).toSet() - p.block.aliases
            val (from, where) = block(p.block, outer)
            val w = if (where.isEmpty()) "" else " where " + where.joinToString(" and ") { pred(it) }
            "exists (select 1 from $from$w)"
        }
        is Pred.And -> p.items.joinToString(" and ", "(", ")") { pred(it) }
        is Pred.Or -> p.items.joinToString(" or ", "(", ")") { pred(it) }
        is Pred.Not -> "not ${paren(p.pred)}"
    }

    private fun paren(p: Pred): String = when (p) {
        is Pred.And, is Pred.Or, Pred.True, Pred.False, is Pred.Has, is Pred.Exists -> pred(p)
        else -> "(${pred(p)})"
    }

    private fun expr(e: Expr): String = when (e) {
        is Expr.Col -> "${alias(e.alias)}.${e.column.sql}"
        is Expr.Field -> SqlCompiler.fieldSql(alias(e.alias), e.path, e.kind)
        is Expr.Param -> bind(Bind.Param(e.index, e.kind, e.list)) + "::" + e.kind.sqlType + (if (e.list) "[]" else "")
        is Expr.Const -> when (val v = e.value) {
            null -> "null" + (e.kind?.let { "::${it.sqlType}" } ?: "")
            else -> bind(Bind.Const(v, e.kind!!)) + "::" + e.kind.sqlType
        }
        Expr.Nil -> SqlCompiler.NIL
        Expr.Now -> bind(Bind.Now) + "::bigint"
        is Expr.TypeEntity -> "(select t.entity from ecs_component_type t where t.name = ${SqlCompiler.quote(e.name)})"
        is Expr.Cast -> "(${expr(e.expr)})::${e.kind.sqlType}"
        is Expr.Call -> call(e)
        is Expr.Arith -> "(${expr(e.left)} ${e.op.sql} ${expr(e.right)})"
        is Expr.Aggregate -> aggregate(e)
        is Expr.Bool -> "coalesce(${pred(e.pred)}, false)"
        is Expr.If -> "case when ${pred(e.condition)} then ${expr(e.then)} else ${expr(e.otherwise)} end"
    }

    private fun call(e: Expr.Call): String {
        val a = e.args.map { expr(it) }
        return when (e.fn) {
            Fn.LOWER -> "lower(${a[0]})"
            Fn.UPPER -> "upper(${a[0]})"
            Fn.TRIM -> "btrim(${a[0]})"
            Fn.LENGTH -> "length(${a[0]})::bigint"
            Fn.STARTS_WITH -> "starts_with(${a[0]}, ${a[1]})"
            Fn.ENDS_WITH -> "(right(${a[0]}, length(${a[1]})) = ${a[1]})"
            Fn.CONTAINS -> "(strpos(${a[0]}, ${a[1]}) > 0)"
            Fn.ABS -> "abs(${a[0]})"
            Fn.COALESCE -> "coalesce(${a.joinToString(", ")})"
            Fn.CONCAT -> "(${a.joinToString(" || ")})"
        }
    }

    private fun aggregate(e: Expr.Aggregate): String {
        val arg = e.arg?.let { expr(it) + if (it.kind == Kind.STRING && (e.fn == AggFn.MIN || e.fn == AggFn.MAX)) " collate \"C\"" else "" }
        val d = if (e.distinct) "distinct " else ""
        return when (e.fn) {
            AggFn.COUNT -> if (arg == null) "count(*)" else "count($d$arg)"
            AggFn.SUM -> "sum($d$arg)::${e.kind.sqlType}"
            AggFn.AVG -> "avg($d$arg)::double precision"
            AggFn.MIN -> "min($arg)"
            AggFn.MAX -> "max($arg)"
        }
    }

    private fun maxAlias(q: LogicalQuery): Int {
        var m = -1
        fun visit(b: Block) {
            b.scans.forEach { m = maxOf(m, it.alias) }
            b.optionals.forEach(::visit)
            fun visitPred(p: Pred) {
                when (p) {
                    is Pred.Exists -> visit(p.block)
                    is Pred.And -> p.items.forEach(::visitPred)
                    is Pred.Or -> p.items.forEach(::visitPred)
                    is Pred.Not -> visitPred(p.pred)
                    else -> {}
                }
            }
            visitPred(b.where)
        }
        visit(q.root)
        return m
    }
}

