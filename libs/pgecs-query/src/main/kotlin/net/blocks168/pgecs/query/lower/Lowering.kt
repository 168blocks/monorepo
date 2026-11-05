package net.blocks168.pgecs.query.lower

import net.blocks168.pgecs.ir.AggregateFn
import net.blocks168.pgecs.ir.AnyEntity
import net.blocks168.pgecs.ir.AnyRelation
import net.blocks168.pgecs.ir.ComponentKind
import net.blocks168.pgecs.ir.ComponentRef
import net.blocks168.pgecs.ir.CompareOp
import net.blocks168.pgecs.ir.EntityRef
import net.blocks168.pgecs.ir.EntityVar
import net.blocks168.pgecs.ir.Functions
import net.blocks168.pgecs.ir.LockMode
import net.blocks168.pgecs.ir.LockWait
import net.blocks168.pgecs.ir.NullOrder
import net.blocks168.pgecs.ir.QueryIr
import net.blocks168.pgecs.ir.SourceId
import net.blocks168.pgecs.ir.Term
import net.blocks168.pgecs.ir.TypeEntity
import net.blocks168.pgecs.ir.ValueType
import net.blocks168.pgecs.ir.ValueVar
import net.blocks168.pgecs.ir.Variable
import net.blocks168.pgecs.query.plan.AggFn
import net.blocks168.pgecs.query.plan.ArithOp
import net.blocks168.pgecs.query.plan.Block
import net.blocks168.pgecs.query.plan.CmpOp
import net.blocks168.pgecs.query.plan.Column
import net.blocks168.pgecs.query.plan.Expr
import net.blocks168.pgecs.query.plan.Fn
import net.blocks168.pgecs.query.plan.Kind
import net.blocks168.pgecs.query.plan.Lock
import net.blocks168.pgecs.query.plan.LogicalQuery
import net.blocks168.pgecs.query.plan.OrderKey
import net.blocks168.pgecs.query.plan.Pred
import net.blocks168.pgecs.query.plan.Scan
import net.blocks168.pgecs.query.plan.SelectItem
import java.time.Duration
import java.time.Instant
import net.blocks168.pgecs.ir.ArithOp as IrArithOp
import net.blocks168.pgecs.ir.Expr as IrExpr

/** The query uses something the Postgres planner cannot run (another source, an unknown function). */
class UnsupportedQueryException(message: String) : IllegalArgumentException(message)

/** A lowered query and the IR types of its output columns, which say how to decode them. */
class Lowered(val query: LogicalQuery, val columns: List<ValueType>)

/**
 * Lowers a validated, parameterized first-level IR query ([QueryIr]) into a [LogicalQuery].
 *
 * The IR has Kotlin semantics; the plan has SQL semantics. The translation keeps the meaning:
 *
 * - **Predicates** are brought into negation normal form under Kotlin's two-valued logic. Each leaf
 *   then appears un-negated, and is translated so that it is SQL-true exactly when it is Kotlin-true
 *   (false or null otherwise). Since `and`/`or` are monotone, the whole `where` then keeps exactly the
 *   rows Kotlin would. `==` becomes `=` when one side cannot be null (so indexes apply) and
 *   `is not distinct from` otherwise; `!=` becomes `<>` only when neither side can be null.
 * - **Nullable parameters** whose value is null at this execution are constants (`x == p` becomes
 *   `x is null`). Callers include which parameters are null in the plan-cache key.
 * - **Variables** are resolved to one canonical expression each (see [Block]).
 * - **Matches** that bind a variable or a value become scans; matches whose variables are all bound
 *   already are probes ([Pred.Has]), which never multiply rows (a pair with all ends bound exists at
 *   most once, by the primary key). A wildcard (`*`) never multiplies rows: when one has to bind a
 *   variable, the scan is `select distinct` over the bound column.
 * - **Values** in JSON are read with the casts of their storage kind: UUIDs as text, instants and
 *   durations as epoch / plain milliseconds.
 */
object Lowering {
    fun lower(ir: QueryIr, nullParams: Set<Int> = emptySet()): Lowered = Lowerer(ir, nullParams).run()

    /** The storage kind of a value of IR type [t] when read from SQL. Fields of UUIDs are text; see [fieldKind]. */
    fun kindOf(t: ValueType): Kind = when (t.kind) {
        ValueType.Kind.BOOL -> Kind.BOOLEAN
        ValueType.Kind.INT -> Kind.LONG
        ValueType.Kind.FLOAT -> Kind.DOUBLE
        ValueType.Kind.TEXT -> Kind.STRING
        ValueType.Kind.UUID -> Kind.ENTITY
        ValueType.Kind.INSTANT -> Kind.LONG
        ValueType.Kind.DURATION -> Kind.LONG
        ValueType.Kind.JSON -> Kind.JSON
    }

    /** The kind a JSON field of IR type [t] is read as: like [kindOf], but UUIDs are stored as strings. */
    fun fieldKind(t: ValueType): Kind = if (t.kind == ValueType.Kind.UUID) Kind.STRING else kindOf(t)
}

private class Lowerer(private val ir: QueryIr, private val nullParams: Set<Int>) {
    private var nextAlias = 0
    private var nextBlock = 0

    /** Value variables: the alias of the scan that binds each. */
    private val valueAlias = HashMap<ValueVar, Int>()

    /** The top-level match that the query locks; it is always a scan, never a probe. */
    private val lockMatch: Term.Match? = ir.lock?.let { l ->
        ir.terms.filterIsInstance<Term.Match>().first { it.subject == l.subject && it.component == l.component }
    }
    private var lockAlias: Int? = null

    /** A select scope: the query, an `exists` body. Optional groups share their enclosing scope. */
    private inner class Scope(val parent: Scope?) {
        val vars = HashMap<Variable, Expr>()
        val nullable = HashSet<Variable>()

        fun lookup(v: Variable): Expr? = vars[v] ?: parent?.lookup(v)

        fun isNullable(v: Variable): Boolean = if (v in vars) v in nullable else parent?.isNullable(v) ?: false
    }

    fun run(): Lowered {
        for (t in ir.terms) checkSource(t)
        val top = Scope(null)
        val root = block(ir.terms, top, nextBlock++)
        val select = ir.select.map { SelectItem(it.name, expr(it.expr, top)) }
        val query = LogicalQuery(
            root = root,
            select = select,
            distinct = ir.distinct,
            groupBy = ir.groupBy.map { expr(it, top) },
            orderBy = ir.orderBy.map { OrderKey(expr(it.expr, top), it.descending, it.nulls == NullOrder.FIRST) },
            limit = ir.limit?.let { expr(it, top) },
            offset = ir.offset?.let { expr(it, top) },
            lock = ir.lock?.let { l ->
                Lock(
                    alias = lockAlias ?: error("lock target was not scanned"),
                    mode = when (l.mode) {
                        LockMode.UPDATE -> "update"
                        LockMode.NO_KEY_UPDATE -> "no key update"
                        LockMode.SHARE -> "share"
                        LockMode.KEY_SHARE -> "key share"
                    },
                    wait = when (l.wait) {
                        LockWait.WAIT -> ""
                        LockWait.SKIP_LOCKED -> "skip locked"
                        LockWait.NOWAIT -> "nowait"
                    },
                )
            },
        )
        return Lowered(query, ir.select.map { it.expr.type })
    }

    private fun checkSource(t: Term) {
        when (t) {
            is Term.Match -> if (t.source != SourceId.POSTGRES) {
                throw UnsupportedQueryException("${t.component} belongs to source ${t.source}; only Postgres is supported so far")
            }
            is Term.Optional -> t.terms.forEach(::checkSource)
            is Term.Filter -> {}
        }
    }

    // ---- terms ------------------------------------------------------------------------------------

    /**
     * Lowers [terms], a conjunction, into a block of [scope]. Mandatory matches come first (they bind
     * variables), then optional groups, then filters, so filters see every binding with its final
     * nullability.
     */
    private fun block(terms: List<Term>, scope: Scope, id: Int): Block {
        val scans = mutableListOf<Scan>()
        val where = mutableListOf<Pred>()
        val optionals = mutableListOf<Block>()
        val boundHere = mutableListOf<Variable>()

        val matches = terms.filterIsInstance<Term.Match>()
        // Concrete matches bind first; wildcards only bind what nothing else does, since binding with a
        // wildcard costs a `select distinct`.
        val (concrete, wild) = matches.partition { !hasWildcard(it) }
        val probes = mutableListOf<Term.Match>()
        for (m in concrete + wild) {
            val newVars = entityVars(m).filter { scope.lookup(it) == null }
            if (m.value != null || newVars.isNotEmpty() || m === lockMatch) {
                scan(m, scope, scans, boundHere)?.let { scans += it }
            } else {
                probes += m
            }
        }
        for (m in probes) where += probe(m, scope)

        for (t in terms.filterIsInstance<Term.Optional>()) {
            val before = scope.vars.keys.toSet()
            val group = block(t.terms, scope, nextBlock++)
            // a group that scans nothing binds nothing and cannot change the rows
            if (group.scans.isNotEmpty()) optionals += group
            // variables first bound in the group are null outside it when it does not match
            scope.nullable += scope.vars.keys - before
        }

        for (t in terms.filterIsInstance<Term.Filter>()) where += pred(t.predicate, scope, positive = true)

        return Block(id, scans, Pred.and(where), optionals)
    }

    private fun hasWildcard(m: Term.Match) = m.subject == AnyEntity || m.target == AnyEntity || m.component is AnyRelation

    private fun entityVars(m: Term.Match): List<EntityVar> =
        listOfNotNull(m.subject as? EntityVar, m.target as? EntityVar).distinct()

    private fun relOf(m: Term.Match): String? = (m.component as? ComponentRef)?.name

    private fun isPair(m: Term.Match): Boolean = when (val c = m.component) {
        is AnyRelation -> true
        is ComponentRef -> c.kind == ComponentKind.RELATION
    }

    /** An entity position that is not a variable: a parameter, a literal, a type entity, or null for `*`. */
    private fun fixedEntity(r: EntityRef): Expr? = when (r) {
        AnyEntity -> null
        is TypeEntity -> Expr.TypeEntity(r.name)
        is IrExpr.Param -> param(r)
        is IrExpr.Literal -> Expr.Const(r.value, Kind.ENTITY)
        is EntityVar -> error("not a fixed entity: $r")
    }

    /** The scan for [m], or null when [m] only binds a value of a component that [existing] already scans. */
    private fun scan(m: Term.Match, scope: Scope, existing: List<Scan>, boundHere: MutableList<Variable>): Scan? {
        val v = m.value
        if (v != null && m !== lockMatch && entityVars(m).all { scope.lookup(it) != null }) {
            // the same component of the same entity matched twice: one scan, both value variables
            val (subject, target) = positions(m, scope)
            val twin = existing.firstOrNull {
                it.rel == relOf(m) && it.subject == subject && it.target == target && !it.distinct
            }
            if (twin != null) {
                bindValue(v, twin.alias, scope, boundHere)
                return null
            }
        }

        val alias = nextAlias++
        fun position(r: EntityRef?, column: Column): Expr? = when (r) {
            null -> Expr.Nil // tags and plain components: the nil target
            is EntityVar -> scope.lookup(r) ?: Expr.Col(alias, column).also {
                scope.vars[r] = it
                boundHere += r
            }
            else -> fixedEntity(r)
        }
        val subject = position(m.subject, Column.ENTITY)
        val target = if (isPair(m)) position(m.target ?: AnyEntity, Column.TARGET) else Expr.Nil
        // a wildcard never multiplies rows; a scan that binds a value or is locked cannot be distinct
        val distinct = v == null && m !== lockMatch && (subject == null || target == null || m.component is AnyRelation)
        if (m === lockMatch) lockAlias = alias
        v?.let { bindValue(it, alias, scope, boundHere) }
        return Scan(alias, relOf(m), subject, target, distinct)
    }

    /** Subject and target of [m] when all its variables are bound. */
    private fun positions(m: Term.Match, scope: Scope): Pair<Expr?, Expr?> {
        fun position(r: EntityRef): Expr? = if (r is EntityVar) scope.lookup(r)!! else fixedEntity(r)
        return position(m.subject) to (if (isPair(m)) position(m.target ?: AnyEntity) else Expr.Nil)
    }

    private fun bindValue(v: ValueVar, alias: Int, scope: Scope, boundHere: MutableList<Variable>) {
        valueAlias[v] = alias
        scope.vars[v] = valueExpr(v, alias)
        boundHere += v
    }

    private fun valueExpr(v: ValueVar, alias: Int): Expr =
        if (v.type.kind == ValueType.Kind.JSON) Expr.Col(alias, Column.VALUE) else Expr.Field(alias, emptyList(), Lowering.fieldKind(v.type))

    private fun probe(m: Term.Match, scope: Scope): Pred {
        val (subject, target) = positions(m, scope)
        return Pred.Has(subject, listOfNotNull(relOf(m)), target)
    }

    // ---- predicates -------------------------------------------------------------------------------

    /**
     * The SQL predicate that is true exactly when Kotlin's [e] (or `!e` when not [positive]) is true,
     * and false or null otherwise. Never wraps a nullable leaf in `not`.
     */
    private fun pred(e: IrExpr, scope: Scope, positive: Boolean): Pred = when (e) {
        is IrExpr.Compare -> compare(e, scope, positive)
        is IrExpr.And -> e.operands.map { pred(it, scope, positive) }.let { if (positive) Pred.and(it) else Pred.or(it) }
        is IrExpr.Or -> e.operands.map { pred(it, scope, positive) }.let { if (positive) Pred.or(it) else Pred.and(it) }
        is IrExpr.Not -> pred(e.operand, scope, !positive)
        is IrExpr.IsNull -> isNull(e.operand, scope, positive)
        is IrExpr.Exists -> exists(e.terms, scope).let { if (positive) it else negate(it) }
        is IrExpr.Literal -> if ((e.value as Boolean) == positive) Pred.True else Pred.False
        else -> {
            val x = expr(e, scope)
            if (positive) Pred.Test(x) else Pred.Not(Pred.Test(x))
        }
    }

    private fun negate(p: Pred): Pred = when (p) {
        Pred.True -> Pred.False
        Pred.False -> Pred.True
        else -> Pred.Not(p)
    }

    private fun knownNull(e: IrExpr): Boolean = e is IrExpr.Param && e.index in nullParams

    private fun mayBeNull(e: IrExpr, scope: Scope): Boolean = when (e) {
        is IrExpr.Param -> e.index in nullParams
        is IrExpr.Literal -> false
        is IrExpr.Var -> e.type.nullable || scope.isNullable(e.variable)
        is IrExpr.Field -> e.type.nullable || scope.isNullable(e.variable)
        is IrExpr.Compare, is IrExpr.And, is IrExpr.Or, is IrExpr.Not, is IrExpr.IsNull, is IrExpr.Exists -> false
        is IrExpr.Coalesce -> e.operands.all { mayBeNull(it, scope) }
        is IrExpr.Arith -> mayBeNull(e.left, scope) || mayBeNull(e.right, scope)
        is IrExpr.Negate -> mayBeNull(e.operand, scope)
        is IrExpr.Call -> e.args.any { mayBeNull(it, scope) } || (e.type.nullable && e.args.isEmpty())
        is IrExpr.Aggregate -> e.type.nullable
        is IrExpr.Arg -> true
    }

    private fun isNull(operand: IrExpr, scope: Scope, positive: Boolean): Pred = when {
        knownNull(operand) -> if (positive) Pred.True else Pred.False
        !mayBeNull(operand, scope) -> if (positive) Pred.False else Pred.True
        else -> Pred.IsNull(expr(operand, scope)).let { if (positive) it else Pred.Not(it) }
    }

    private fun compare(e: IrExpr.Compare, scope: Scope, positive: Boolean): Pred {
        val op = if (positive) e.op else e.op.negated()
        if (op == CompareOp.EQ || op == CompareOp.NE) {
            val eq = op == CompareOp.EQ
            val ln = knownNull(e.left)
            val rn = knownNull(e.right)
            when {
                ln && rn -> return if (eq) Pred.True else Pred.False
                ln -> return isNull(e.right, scope, eq)
                rn -> return isNull(e.left, scope, eq)
            }
        }
        val (l, r) = unify(expr(e.left, scope), expr(e.right, scope))
        val lMay = mayBeNull(e.left, scope)
        val rMay = mayBeNull(e.right, scope)
        val sqlOp = when (op) {
            CompareOp.EQ -> if (!lMay || !rMay) CmpOp.EQ else CmpOp.NOT_DISTINCT
            CompareOp.NE -> if (!lMay && !rMay) CmpOp.NE else CmpOp.DISTINCT
            // ordering comparisons: the validator only allows non-null operands
            CompareOp.LT -> CmpOp.LT
            CompareOp.LE -> CmpOp.LE
            CompareOp.GT -> CmpOp.GT
            CompareOp.GE -> CmpOp.GE
        }
        // the column side first, as written in index expressions
        return if (isConstant(l) && !isConstant(r)) Pred.Cmp(sqlOp.flipped, r, l) else Pred.Cmp(sqlOp, l, r)
    }

    private fun isConstant(e: Expr): Boolean = e is Expr.Param || e is Expr.Const || e is Expr.Now ||
        (e is Expr.Cast && isConstant(e.expr))

    /** An entity compared with a UUID stored as a string: compare as text, so the field's index applies. */
    private fun unify(l: Expr, r: Expr): Pair<Expr, Expr> = when {
        l.kind == Kind.STRING && r.kind == Kind.ENTITY -> l to Expr.Cast(r, Kind.STRING)
        l.kind == Kind.ENTITY && r.kind == Kind.STRING -> Expr.Cast(l, Kind.STRING) to r
        else -> l to r
    }

    private fun exists(terms: List<Term>, scope: Scope): Pred {
        val child = Scope(scope)
        val b = block(terms, child, nextBlock++)
        return if (b.scans.isEmpty() && b.optionals.isEmpty()) b.where else Pred.Exists(b)
    }

    // ---- expressions ------------------------------------------------------------------------------

    private fun param(p: IrExpr.Param): Expr =
        if (p.index in nullParams) Expr.Const(null, Lowering.kindOf(p.type)) else Expr.Param(p.index, Lowering.kindOf(p.type))

    private fun expr(e: IrExpr, scope: Scope): Expr = when (e) {
        is IrExpr.Var -> scope.lookup(e.variable) ?: error("variable ${e.variable} is not bound")
        is IrExpr.Field -> {
            scope.lookup(e.variable) ?: error("variable ${e.variable} is not bound")
            Expr.Field(valueAlias.getValue(e.variable), e.path, Lowering.fieldKind(e.type))
        }
        is IrExpr.Param -> param(e)
        is IrExpr.Literal -> Expr.Const(literal(e.value), Lowering.kindOf(e.type))
        is IrExpr.Compare, is IrExpr.And, is IrExpr.Or, is IrExpr.Not, is IrExpr.IsNull, is IrExpr.Exists ->
            Expr.Bool(pred(e, scope, positive = true))
        is IrExpr.Coalesce -> e.operands.map { expr(it, scope) }.let { args ->
            Expr.Call(Fn.COALESCE, args, args.last().kind)
        }
        is IrExpr.Arith -> Expr.Arith(
            when (e.op) {
                IrArithOp.PLUS -> ArithOp.ADD
                IrArithOp.MINUS -> ArithOp.SUB
                IrArithOp.TIMES -> ArithOp.MUL
                IrArithOp.DIV -> ArithOp.DIV
                IrArithOp.REM -> ArithOp.REM
            },
            expr(e.left, scope),
            expr(e.right, scope),
            Lowering.kindOf(e.type),
        )
        is IrExpr.Negate -> {
            val k = Lowering.kindOf(e.type)
            Expr.Arith(ArithOp.SUB, Expr.Const(if (k == Kind.DOUBLE) 0.0 else 0L, k), expr(e.operand, scope), k)
        }
        is IrExpr.Call -> call(e, scope)
        is IrExpr.Aggregate -> Expr.Aggregate(
            when (e.fn) {
                AggregateFn.COUNT -> AggFn.COUNT
                AggregateFn.SUM -> AggFn.SUM
                AggregateFn.MIN -> AggFn.MIN
                AggregateFn.MAX -> AggFn.MAX
                AggregateFn.AVG -> AggFn.AVG
            },
            e.arg?.let { expr(it, scope) },
            e.distinct,
            Lowering.kindOf(e.type).let { if (e.arg is IrExpr.Field && e.arg!!.type.kind == ValueType.Kind.UUID) Kind.STRING else it },
        )
        is IrExpr.Arg -> error("lambda argument ${e.index} was never substituted")
    }

    private fun call(e: IrExpr.Call, scope: Scope): Expr {
        val args = e.args.map { expr(it, scope) }
        val fn = when (e.fn) {
            Functions.LOWERCASE -> Fn.LOWER
            Functions.UPPERCASE -> Fn.UPPER
            Functions.TRIM -> Fn.TRIM
            Functions.LENGTH -> Fn.LENGTH
            Functions.STARTS_WITH -> Fn.STARTS_WITH
            Functions.ENDS_WITH -> Fn.ENDS_WITH
            Functions.CONTAINS -> Fn.CONTAINS
            Functions.NOW -> return Expr.Now
            else -> throw UnsupportedQueryException("function ${e.fn} cannot run in Postgres")
        }
        return Expr.Call(fn, args, fn.kind)
    }

    private fun literal(v: Any): Any = when (v) {
        is Instant -> v.toEpochMilli()
        is Duration -> v.toMillis()
        else -> v
    }
}
