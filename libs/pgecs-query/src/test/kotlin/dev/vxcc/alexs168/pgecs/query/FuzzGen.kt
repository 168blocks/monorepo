package dev.vxcc.alexs168.pgecs.query

import dev.vxcc.alexs168.pgecs.ir.AggregateFn
import dev.vxcc.alexs168.pgecs.ir.AnyEntity
import dev.vxcc.alexs168.pgecs.ir.AnyRelation
import dev.vxcc.alexs168.pgecs.ir.ArithOp
import dev.vxcc.alexs168.pgecs.ir.CompareOp
import dev.vxcc.alexs168.pgecs.ir.EntityRef
import dev.vxcc.alexs168.pgecs.ir.EntityVar
import dev.vxcc.alexs168.pgecs.ir.Expr
import dev.vxcc.alexs168.pgecs.ir.Functions
import dev.vxcc.alexs168.pgecs.ir.IrBuilder
import dev.vxcc.alexs168.pgecs.ir.NullOrder
import dev.vxcc.alexs168.pgecs.ir.Nullability
import dev.vxcc.alexs168.pgecs.ir.QueryIr
import dev.vxcc.alexs168.pgecs.ir.SortKey
import dev.vxcc.alexs168.pgecs.ir.Term
import dev.vxcc.alexs168.pgecs.ir.TermsBuilder
import dev.vxcc.alexs168.pgecs.ir.TypeEntity
import dev.vxcc.alexs168.pgecs.ir.ValueType
import dev.vxcc.alexs168.pgecs.ir.ValueType.Kind
import dev.vxcc.alexs168.pgecs.ir.ValueVar
import dev.vxcc.alexs168.pgecs.ir.Variable
import java.time.Duration
import java.util.UUID
import kotlin.random.Random

/**
 * One generated query with its arguments, and what the metamorphic checks need: [rebind] draws new
 * arguments for the same shape (plan-cache reuse), [partition] is the ternary logic partitioning
 * of the query by a random predicate `p`: the rows of [query] are exactly those of `query ∧ p`
 * and `query ∧ ¬p` together (two-valued: IR predicates are never null).
 */
class FuzzCase(
    val query: QueryIr,
    val args: List<Any?>,
    val rebind: (Random) -> List<Any?>,
    val partition: Partition?,
) {
    class Partition(val whenTrue: QueryIr, val whenFalse: QueryIr, val args: List<Any?>)
}

/**
 * Generates random, valid first-level IR queries over [FuzzSchema]: matches of every kind (tags,
 * pairs, wildcards, fixed entities, type entities, values), optional groups with filters inside,
 * predicates and expressions of every kind the IR has, projections, aggregates with and without
 * group-by, distinct, order-by with null placement, limit and offset. Values come from the same
 * pools as the data, so comparisons match often.
 *
 * Variables bound only in an optional group are tracked ([nullVars]), so ordering comparisons and
 * concatenations get a `?:` default where the validator requires a non-null operand; most
 * generated queries are valid.
 */
class FuzzGen(private val rnd: Random, private val entities: List<UUID>) {
    private val s = FuzzSchema
    private lateinit var b: IrBuilder
    private val args = mutableListOf<Any?>()
    private val suppliers = mutableListOf<(Random) -> Any?>()
    private val bound = mutableListOf<EntityVar>()
    private val values = mutableMapOf<Kind, MutableList<ValueVar>>()
    private val nullVars = mutableSetOf<Variable>()
    private var vars = 0

    /** Whether `now()` may appear: only where its exact value cannot reach the result (predicates). */
    private var nowOk = true

    private fun chance(n: Int) = rnd.nextInt(n) == 0

    private fun <T> pick(vararg xs: T): T = xs[rnd.nextInt(xs.size)]

    // ---- values and parameters ---------------------------------------------------------------------

    private fun value(kind: Kind, r: Random): Any = when (kind) {
        Kind.INT -> r.nextLong(-3, 4)
        Kind.TEXT -> s.names.random(r)
        Kind.INSTANT -> s.instants.random(r)
        Kind.DURATION -> Duration.ofMillis(listOf(0L, 1L, 1000L, 60_000L, -60_000L).random(r))
        Kind.UUID -> entities.random(r)
        Kind.BOOL -> r.nextBoolean()
        else -> error("no values of $kind")
    }

    private fun param(type: ValueType, supply: (Random) -> Any?): Expr.Param =
        b.param(type).also {
            suppliers += supply
            args += supply(rnd)
        }

    /** A literal, a parameter or (for a nullable parameter type) a parameter that may be null. */
    private fun constant(kind: Kind): Expr = when (rnd.nextInt(5)) {
        0, 1 -> Expr.Literal.of(value(kind, rnd))
        2, 3 -> param(ValueType(kind)) { value(kind, it) }
        else -> param(ValueType(kind, nullable = true)) { if (it.nextInt(3) == 0) null else value(kind, it) }
    }

    private fun nonNullConstant(kind: Kind): Expr =
        if (rnd.nextBoolean()) Expr.Literal.of(value(kind, rnd)) else param(ValueType(kind)) { value(kind, it) }

    // ---- terms --------------------------------------------------------------------------------------

    private fun newEntity(): EntityVar = b.entity("e${vars++}").also { bound += it }

    private fun entityRef(allowAny: Boolean): EntityRef = when (rnd.nextInt(10)) {
        in 0..4 -> newEntity()
        in 5..6 -> bound.randomOrNull(rnd) ?: newEntity()
        7 -> if (allowAny) AnyEntity else bound.randomOrNull(rnd) ?: newEntity()
        else -> param(ValueType.UUID) { entities.random(it) }
    }

    private fun newValue(kind: Kind): ValueVar =
        b.value("v${vars++}", ValueType(kind)).also { values.getOrPut(kind) { mutableListOf() } += it }

    /** A match into [t]. */
    private fun match(t: TermsBuilder) {
        when (rnd.nextInt(9)) {
            0, 1 -> t.match(entityRef(allowAny = false), s.tags.random(rnd))
            2, 3 -> t.match(entityRef(allowAny = true), s.rels.random(rnd), target = entityRef(allowAny = true))
            4 -> t.match(entityRef(allowAny = false), AnyRelation(), target = entityRef(allowAny = false))
            5 -> t.match(entityRef(allowAny = true), s.can, target = if (chance(3)) AnyEntity else TypeEntity(s.capabilities.random(rnd)))
            6 -> t.match(entityRef(allowAny = false), s.score, value = newValue(Kind.INT))
            7 -> t.match(entityRef(allowAny = false), s.at, value = newValue(Kind.INSTANT))
            else -> t.match(entityRef(allowAny = false), s.json, value = newValue(Kind.JSON))
        }
    }

    // ---- expressions --------------------------------------------------------------------------------

    private fun mayBeNull(e: Expr) = Nullability.mayBeNull(e, nullVars)

    /** [e], with a `?:` default if it may be null. */
    private fun nonNull(e: Expr): Expr = if (mayBeNull(e)) Expr.Coalesce(listOf(e, nonNullConstant(e.type.kind))) else e

    private fun valueVar(kind: Kind): ValueVar? = values[kind]?.randomOrNull(rnd)

    private fun json(): ValueVar? = valueVar(Kind.JSON)

    /** An expression of [kind]; may be null. */
    fun expr(kind: Kind, depth: Int): Expr = when (kind) {
        Kind.INT -> intExpr(depth)
        Kind.TEXT -> textExpr(depth)
        Kind.INSTANT -> instantExpr(depth)
        Kind.DURATION -> durationExpr(depth)
        Kind.UUID -> uuidExpr()
        Kind.BOOL -> pred(depth)
        else -> error("no expressions of $kind")
    }

    private fun leafOr(depth: Int, leaf: () -> Expr, inner: () -> Expr): Expr = if (depth >= 3 || chance(2)) leaf() else inner()

    private fun intExpr(depth: Int): Expr = leafOr(depth, {
        when (rnd.nextInt(4)) {
            0 -> valueVar(Kind.INT)?.let { Expr.Var(it) } ?: constant(Kind.INT)
            1 -> json()?.let { Expr.Field(it, if (chance(3)) listOf("meta", "k") else listOf("n"), ValueType.INT.asNullable()) } ?: constant(Kind.INT)
            else -> constant(Kind.INT)
        }
    }) {
        when (rnd.nextInt(7)) {
            0, 1 -> Expr.Arith(pick(ArithOp.PLUS, ArithOp.MINUS, ArithOp.TIMES), intExpr(depth + 1), intExpr(depth + 1))
            // never by zero: Kotlin throws, SQL raises an error
            2 -> Expr.Arith(pick(ArithOp.DIV, ArithOp.REM), intExpr(depth + 1), Expr.Literal.of(pick(-2L, -1L, 1L, 2L, 3L)))
            3 -> Expr.Negate(intExpr(depth + 1))
            4 -> Expr.If(pred(depth + 1), intExpr(depth + 1), intExpr(depth + 1))
            5 -> Functions.call(Functions.LENGTH, textExpr(depth + 1))
            else -> Expr.Coalesce(listOf(intExpr(depth + 1), nonNull(intExpr(depth + 1))))
        }
    }

    private fun textExpr(depth: Int): Expr = leafOr(depth, {
        json()?.takeIf { rnd.nextInt(3) != 0 }?.let { Expr.Field(it, listOf("name"), ValueType.TEXT) } ?: constant(Kind.TEXT)
    }) {
        when (rnd.nextInt(6)) {
            0 -> Functions.call(Functions.LOWERCASE, textExpr(depth + 1))
            1 -> Functions.call(Functions.UPPERCASE, textExpr(depth + 1))
            2 -> Functions.call(Functions.TRIM, textExpr(depth + 1))
            3 -> Functions.call(Functions.CONCAT, nonNull(textExpr(depth + 1)), nonNull(textExpr(depth + 1)))
            4 -> Expr.If(pred(depth + 1), textExpr(depth + 1), textExpr(depth + 1))
            else -> Expr.Coalesce(listOf(textExpr(depth + 1), nonNull(textExpr(depth + 1))))
        }
    }

    private fun instantExpr(depth: Int): Expr = leafOr(depth, {
        when (rnd.nextInt(5)) {
            0 -> valueVar(Kind.INSTANT)?.let { Expr.Var(it) } ?: constant(Kind.INSTANT)
            1 -> json()?.let { Expr.Field(it, listOf("at"), ValueType.INSTANT.asNullable()) } ?: constant(Kind.INSTANT)
            2 -> if (nowOk) Functions.call(Functions.NOW) else constant(Kind.INSTANT)
            else -> constant(Kind.INSTANT)
        }
    }) {
        when (rnd.nextInt(3)) {
            0 -> Expr.Arith(pick(ArithOp.PLUS, ArithOp.MINUS), instantExpr(depth + 1), durationExpr(depth + 1))
            1 -> Expr.If(pred(depth + 1), instantExpr(depth + 1), instantExpr(depth + 1))
            else -> Expr.Coalesce(listOf(instantExpr(depth + 1), nonNull(instantExpr(depth + 1))))
        }
    }

    private fun durationExpr(depth: Int): Expr = leafOr(depth, { constant(Kind.DURATION) }) {
        when (rnd.nextInt(5)) {
            0 -> Expr.Arith(ArithOp.MINUS, instantExpr(depth + 1), instantExpr(depth + 1))
            1 -> Expr.Arith(pick(ArithOp.PLUS, ArithOp.MINUS), durationExpr(depth + 1), durationExpr(depth + 1))
            2 -> Expr.Arith(ArithOp.TIMES, durationExpr(depth + 1), Expr.Literal.of(pick(-1L, 2L, 3L)))
            3 -> Expr.Negate(durationExpr(depth + 1))
            else -> Expr.If(pred(depth + 1), durationExpr(depth + 1), durationExpr(depth + 1))
        }
    }

    private fun uuidExpr(): Expr = when (rnd.nextInt(3)) {
        0 -> json()?.let { Expr.Field(it, listOf("ref"), ValueType.UUID.asNullable()) } ?: constant(Kind.UUID)
        1 -> bound.randomOrNull(rnd)?.let { Expr.Var(it) } ?: constant(Kind.UUID)
        else -> constant(Kind.UUID)
    }

    private val comparable = listOf(Kind.INT, Kind.INT, Kind.TEXT, Kind.TEXT, Kind.INSTANT, Kind.DURATION, Kind.UUID)

    /** A non-null boolean. */
    fun pred(depth: Int): Expr {
        if (depth >= 4) return Expr.Literal.of(rnd.nextBoolean())
        return when (rnd.nextInt(if (depth >= 2) 8 else 14)) {
            0, 1, 2 -> {
                val kind = comparable.random(rnd)
                val op = CompareOp.entries.random(rnd)
                val l = expr(kind, depth + 1)
                val r = expr(kind, depth + 1)
                if (op.isOrdering) Expr.Compare(op, nonNull(l), nonNull(r)) else Expr.Compare(op, l, r)
            }
            3 -> Expr.IsNull(expr(comparable.random(rnd), depth + 1))
            4 -> Functions.call(pick(Functions.STARTS_WITH, Functions.ENDS_WITH, Functions.CONTAINS), nonNull(textExpr(depth + 1)), nonNull(textExpr(depth + 1)))
            5 -> if (bound.isNotEmpty()) existsProbe() else pred(depth + 1)
            6 -> Expr.Literal.of(rnd.nextBoolean())
            7 -> Expr.Not(pred(depth + 1))
            8, 9 -> Expr.And(listOf(pred(depth + 1), pred(depth + 1)))
            10, 11 -> Expr.Or(listOf(pred(depth + 1), pred(depth + 1)))
            12 -> if (bound.isNotEmpty()) existsChild() else pred(depth + 1)
            else -> if (chance(2)) Expr.If(pred(depth + 1), pred(depth + 1), pred(depth + 1)) else Expr.Compare(pick(CompareOp.EQ, CompareOp.NE), pred(depth + 1), pred(depth + 1))
        }
    }

    /** `x has T`, `x has (R, y)`, `x can cap` with only visible variables. */
    private fun existsProbe(): Expr = b.exists {
        val x = bound.random(rnd)
        when (rnd.nextInt(4)) {
            0 -> match(x, s.tags.random(rnd))
            1 -> match(x, s.can, target = TypeEntity(s.capabilities.random(rnd)))
            else -> match(x, s.rels.random(rnd), target = if (rnd.nextBoolean()) AnyEntity else bound.random(rnd))
        }
    }

    /** "has a child that ...": local variables, correlated with a visible one. */
    private fun existsChild(): Expr {
        val outer = bound.random(rnd)
        return b.exists {
            val c = b.entity("x${vars++}")
            if (rnd.nextBoolean()) match(c, s.rels.random(rnd), target = outer) else match(outer, s.rels.random(rnd), target = c)
            when (rnd.nextInt(3)) {
                0 -> match(c, s.tags.random(rnd))
                1 -> {
                    val v = b.value("x${vars++}", ValueType.INT)
                    match(c, s.score, value = v)
                    filter(Expr.Compare(CompareOp.entries.random(rnd), Expr.Var(v), nonNullConstant(Kind.INT)))
                }
                else -> {}
            }
        }
    }

    // ---- queries ------------------------------------------------------------------------------------

    private val projectable = listOf(Kind.INT, Kind.TEXT, Kind.INSTANT, Kind.DURATION, Kind.UUID, Kind.BOOL)

    fun query(): FuzzCase {
        b = IrBuilder()
        repeat(1 + rnd.nextInt(3)) { match(b) }
        val topLevel = (bound + values.values.flatten()).toSet()
        if (chance(3)) {
            b.optional {
                repeat(1 + rnd.nextInt(2)) { match(this) }
                // inside the group its own variables are not null
                if (chance(3)) filter(pred(1))
            }
            nullVars += (bound + values.values.flatten()).toSet() - topLevel
        }
        repeat(rnd.nextInt(3)) { b.filter(pred(0)) }

        nowOk = false
        val aggregate = chance(5)
        val select = mutableListOf<Expr>()
        if (aggregate) {
            repeat(rnd.nextInt(3)) {
                val key = if (rnd.nextBoolean() && bound.isNotEmpty()) Expr.Var(bound.random(rnd)) else expr(projectable.random(rnd), 2)
                if (key !in select) {
                    b.groupBy(key)
                    select += key
                }
            }
            repeat(1 + rnd.nextInt(3)) { select += aggregate() }
        } else {
            select += bound.distinct().shuffled(rnd).take(rnd.nextInt(3))
                .map { Expr.Var(it) }
            repeat(rnd.nextInt(3)) { select += expr(projectable.random(rnd), 1) }
            if (select.isEmpty()) select += Expr.Literal.of(1L)
        }
        select.distinct().forEachIndexed { i, e -> b.select("p$i", e) }
        val columns = select.distinct()
        val distinct = !aggregate && chance(5)
        if (distinct) b.distinct()

        var paged = false
        val orderable = columns.filter { it.type.kind != Kind.JSON }
        if (orderable.isNotEmpty() && chance(3)) {
            val keys = orderable.shuffled(rnd).take(1 + rnd.nextInt(orderable.size)).toMutableList()
            paged = chance(2)
            // with paging, which rows are returned must not depend on ties: order by every column
            if (paged) keys += orderable - keys.toSet()
            for (k in keys) {
                val desc = rnd.nextBoolean()
                val nulls = if (chance(3)) pick(NullOrder.FIRST, NullOrder.LAST) else if (desc) NullOrder.LAST else NullOrder.FIRST
                b.orderBy(SortKey(k, desc, nulls))
            }
            if (paged) {
                val limited = rnd.nextBoolean()
                if (limited) b.limit(pagingValue())
                if (!limited || rnd.nextBoolean()) b.offset(pagingValue())
            }
        }
        nowOk = true

        val base = b.build()
        val baseArgs = args.toList()
        val baseSuppliers = suppliers.toList()
        val rebind: (Random) -> List<Any?> = { r -> baseSuppliers.map { it(r) } }

        // the partition predicate sees the top-level variables, with their nullability
        val partition = if (!aggregate && !paged) {
            val p = pred(0)
            val full = b.buildUnchecked()
            FuzzCase.Partition(
                whenTrue = full.copy(terms = base.terms + Term.Filter(p)).validate(),
                whenFalse = full.copy(terms = base.terms + Term.Filter(Expr.Not(p))).validate(),
                args = args.toList(),
            )
        } else {
            null
        }
        return FuzzCase(base, baseArgs, rebind, partition)
    }

    private fun pagingValue(): Expr =
        if (rnd.nextBoolean()) Expr.Literal.of(rnd.nextLong(0, 5)) else param(ValueType.INT) { it.nextLong(0, 5) }

    private fun aggregate(): Expr = when (rnd.nextInt(6)) {
        0 -> Expr.Aggregate(AggregateFn.COUNT, null)
        1 -> Expr.Aggregate(AggregateFn.COUNT, expr(projectable.random(rnd), 2), distinct = rnd.nextBoolean())
        2 -> Expr.Aggregate(AggregateFn.SUM, intExpr(2), distinct = chance(3))
        3 -> Expr.Aggregate(AggregateFn.AVG, intExpr(2), distinct = chance(3))
        else -> Expr.Aggregate(pick(AggregateFn.MIN, AggregateFn.MAX), expr(pick(Kind.INT, Kind.TEXT, Kind.INSTANT, Kind.DURATION, Kind.UUID), 2))
    }
}
