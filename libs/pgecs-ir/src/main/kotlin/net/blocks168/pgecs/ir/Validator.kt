package net.blocks168.pgecs.ir

/** A query that breaks the IR's scoping or typing rules; [problems] lists all of them. */
class IrValidationException(val problems: List<String>, query: QueryIr) : IllegalArgumentException(
    "invalid query IR:\n" + problems.joinToString("\n") { "  - $it" } + "\nin:\n" + query.canonicalText,
)

/**
 * Whether expressions can evaluate to null, which is more than their declared type says: a variable
 * bound only inside a [Term.Optional] is null outside it when the group does not match, and so is
 * every field of it.
 */
object Nullability {

    /**
     * Variables of [q] that are null at the top level when their [Term.Optional] does not match:
     * bound by a top-level optional group and by no top-level [Term.Match].
     */
    @JvmStatic
    fun optionalVariables(q: QueryIr): Set<Variable> = bindings(q.terms).filterValues { it }.keys

    /**
     * Whether [e] can be null, in a context where the variables in [nullVariables] can be null
     * (for the top level of a query, [optionalVariables]).
     */
    @JvmStatic
    fun mayBeNull(e: Expr, nullVariables: Set<Variable>): Boolean = when (e) {
        is Expr.Var -> e.type.nullable || e.variable in nullVariables
        is Expr.Field -> e.type.nullable || e.variable in nullVariables
        is Expr.Param -> e.type.nullable
        is Expr.Literal -> false
        is Expr.Arg -> e.type.nullable
        is Expr.Compare, is Expr.And, is Expr.Or, is Expr.Not, is Expr.IsNull, is Expr.Exists -> false
        is Expr.Coalesce -> e.operands.all { mayBeNull(it, nullVariables) }
        is Expr.If -> mayBeNull(e.then, nullVariables) || mayBeNull(e.otherwise, nullVariables)
        is Expr.Arith -> mayBeNull(e.left, nullVariables) || mayBeNull(e.right, nullVariables)
        is Expr.Negate -> mayBeNull(e.operand, nullVariables)
        is Expr.Call -> !Functions.neverNull(e.fn) && (e.type.nullable || e.args.any { mayBeNull(it, nullVariables) })
        is Expr.Aggregate -> e.type.nullable
    }

    /**
     * Variables bound by [terms] directly or through optional groups, mapped to whether they are
     * bound only optionally. Bindings inside [Expr.Exists] are not included.
     */
    internal fun bindings(terms: List<Term>): Map<Variable, Boolean> {
        val out = LinkedHashMap<Variable, Boolean>()
        fun visit(ts: List<Term>, optional: Boolean) {
            for (t in ts) {
                when (t) {
                    is Term.Match -> listOfNotNull(t.subject as? EntityVar, t.target as? EntityVar, t.value).forEach { v ->
                        out[v] = (out[v] ?: true) && optional
                    }
                    is Term.Optional -> visit(t.terms, true)
                    is Term.Filter -> {}
                }
            }
        }
        visit(terms, false)
        return out
    }
}

/** The checks behind [QueryIr.validate]. */
internal class Validator private constructor(private val q: QueryIr) {
    private val problems = mutableListOf<String>()
    private val owner = HashMap<Variable, Any>()
    private val valueBinders = HashMap<ValueVar, Int>()
    private val usedParams = HashSet<Int>()

    private fun problem(msg: String) {
        problems += msg
    }

    companion object {
        fun validate(q: QueryIr) {
            val v = Validator(q)
            v.run()
            if (v.problems.isNotEmpty()) throw IrValidationException(v.problems.distinct(), q)
        }
    }

    private fun run() {
        q.variables.forEachIndexed { i, v ->
            if (v.index != i) problem("variable ${v.name} has index ${v.index} but is declared at position $i")
        }
        q.variables.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { problem("variable name $it is declared twice") }

        // Top level: a variable is visible (null when only bound optionally) in everything below.
        val top = checkScope(q.terms, emptyMap(), "the query")

        if (q.select.isEmpty()) problem("the query selects nothing")
        q.select.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { problem("projection name $it is used twice") }
        val aggregate = q.isAggregate
        for (p in q.select) {
            expr(p.expr, top, "select ${p.name}", allowAggregate = true)
            if (aggregate && !p.expr.containsAggregate() && p.expr !in q.groupBy) {
                problem("select ${p.name} is neither an aggregate nor in group-by: ${p.expr}")
            }
        }
        for (g in q.groupBy) expr(g, top, "group-by", allowAggregate = false)
        for (s in q.orderBy) {
            expr(s.expr, top, "order-by", allowAggregate = true)
            if (s.expr.type.kind == ValueType.Kind.JSON) problem("cannot order by a JSON value: ${s.expr}")
            if (aggregate && !s.expr.containsAggregate() && s.expr !in q.groupBy) {
                problem("order-by ${s.expr} is neither an aggregate nor in group-by")
            }
            if (q.distinct && q.select.none { it.expr == s.expr }) {
                problem("with distinct, order-by ${s.expr} must be one of the selected expressions")
            }
        }
        paging(q.limit, "limit")
        paging(q.offset, "offset")
        q.lock?.let { lock(it, top, aggregate) }

        for (v in q.variables) if (v !in owner) problem("variable ${v.name} is never bound by a match")
        q.params.indices.filter { it !in usedParams }.forEach { problem("parameter $it is never used") }
    }

    /**
     * Checks a conjunction of [terms] that opens a scope (the query or an [Expr.Exists]) inside
     * [outer], and returns the variables visible in it.
     */
    private fun checkScope(terms: List<Term>, outer: Map<Variable, Boolean>, where: String): Map<Variable, Boolean> {
        val bound = Nullability.bindings(terms)
        val visible = LinkedHashMap(outer)
        val scope = Any()
        for ((v, optional) in bound) {
            if (v in outer) continue // correlation with the enclosing scope
            if (owner.putIfAbsent(v, scope) != null) {
                problem("variable ${v.name} is bound in two separate scopes (another exists, or outside of $where)")
            }
            visible[v] = optional
        }
        checkTerms(terms, visible, where)
        return visible
    }

    /** Checks [terms] in which [visible] variables are visible, mapped to whether they may be null. */
    private fun checkTerms(terms: List<Term>, visible: Map<Variable, Boolean>, where: String) {
        for (t in terms) {
            when (t) {
                is Term.Match -> match(t, where)
                is Term.Filter -> {
                    expr(t.predicate, visible, "filter in $where", allowAggregate = false)
                    requireBool(t.predicate, visible, "filter in $where")
                }
                is Term.Optional -> {
                    // Inside its own group, a variable that the group matches directly is not null.
                    val inner = LinkedHashMap(visible)
                    Nullability.bindings(t.terms).filterValues { !it }.keys.forEach { inner[it] = false }
                    checkTerms(t.terms, inner, "optional group in $where")
                }
            }
        }
    }

    private fun match(m: Term.Match, where: String) {
        val c = m.component
        val what = "$m in $where"
        when (c) {
            is AnyRelation -> {
                if (m.target == null) problem("$what: a wildcard relation needs a target")
                if (m.value != null) problem("$what: a wildcard relation cannot bind a value")
            }
            is ComponentRef -> when (c.kind) {
                ComponentKind.CAPABILITY ->
                    problem("$what: capability ${c.name} is not a component; use it as a target (TypeEntity)")
                ComponentKind.RELATION -> if (m.target == null) problem("$what: relation ${c.name} needs a target")
                ComponentKind.TAG, ComponentKind.VALUE -> if (m.target != null) {
                    problem("$what: ${c.name} is not a relation and cannot have a target")
                }
            }
        }
        val v = m.value
        if (v != null) {
            valueBinders.merge(v, 1, Int::plus)
            if (valueBinders[v]!! > 1) problem("value variable ${v.name} is bound by more than one match")
            val vt = (c as? ComponentRef)?.valueType
            if (c is ComponentRef && vt == null) problem("$what: ${c.name} has no value to bind")
            if (vt != null && vt != v.type) problem("$what: ${v.name} is ${v.type} but ${c} values are $vt")
        }
        entity(m.subject, what)
        m.target?.let { entity(it, what) }
    }

    private fun entity(e: EntityRef, what: String) {
        when (e) {
            is EntityVar -> declared(e)
            is Expr.Param -> {
                param(e)
                if (e.type != ValueType.UUID) problem("$what: entity parameter ${e.index} is ${e.type}, expected uuid")
            }
            is Expr.Literal -> if (e.type != ValueType.UUID) problem("$what: entity literal is ${e.type}, expected uuid")
            AnyEntity, is TypeEntity -> {}
        }
    }

    private fun declared(v: Variable) {
        if (q.variables.getOrNull(v.index) != v) problem("variable ${v.name} (index ${v.index}) is not declared")
    }

    private fun param(p: Expr.Param) {
        usedParams += p.index
        val declared = q.params.getOrNull(p.index)
        if (declared == null) {
            problem("parameter ${p.index} is not declared (${q.params.size} parameters)")
        } else if (declared != p.type) {
            problem("parameter ${p.index} is used as ${p.type} but declared ${declared}")
        }
    }

    private fun requireBool(e: Expr, visible: Map<Variable, Boolean>, where: String) {
        if (e.type.kind != ValueType.Kind.BOOL) problem("$where: expected a boolean, got ${e.type}: $e")
        else if (mayBeNull(e, visible)) problem("$where: boolean may be null: $e")
    }

    private fun mayBeNull(e: Expr, visible: Map<Variable, Boolean>): Boolean =
        Nullability.mayBeNull(e, visible.filterValues { it }.keys)

    private fun expr(e: Expr, visible: Map<Variable, Boolean>, where: String, allowAggregate: Boolean) {
        fun sub(x: Expr) = expr(x, visible, where, allowAggregate)
        when (e) {
            is Expr.Var -> {
                declared(e.variable)
                if (e.variable !in visible) problem("$where: variable ${e.variable.name} is not bound here")
            }
            is Expr.Field -> {
                declared(e.variable)
                if (e.variable !in visible) problem("$where: variable ${e.variable.name} is not bound here")
                if (e.variable.type.kind != ValueType.Kind.JSON) {
                    problem("$where: ${e.variable.name} is ${e.variable.type}, it has no field ${e.path.joinToString(".")}")
                }
            }
            is Expr.Param -> {
                param(e)
                if (e.type.list) problem("$where: list parameter ${e.index} outside of ${Functions.IN}")
            }
            is Expr.Literal -> {}
            is Expr.Arg -> problem("$where: lambda argument ${e.index} was never substituted")
            is Expr.Compare -> {
                sub(e.left); sub(e.right)
                if (!Types.comparable(e.op, e.left.type, e.right.type)) {
                    problem("$where: cannot compare ${e.left.type} ${e.op.symbol} ${e.right.type}: $e")
                }
                if (e.op.isOrdering && (mayBeNull(e.left, visible) || mayBeNull(e.right, visible))) {
                    problem("$where: ordering comparison of a value that may be null: $e")
                }
            }
            is Expr.And -> e.operands.forEach { sub(it); requireBool(it, visible, where) }
            is Expr.Or -> e.operands.forEach { sub(it); requireBool(it, visible, where) }
            is Expr.Not -> { sub(e.operand); requireBool(e.operand, visible, where) }
            is Expr.IsNull -> sub(e.operand)
            is Expr.Coalesce -> {
                e.operands.forEach(::sub)
                if (e.operands.map { it.type.kind }.distinct().size > 1) problem("$where: ?: of different types: $e")
            }
            is Expr.If -> {
                sub(e.condition); sub(e.then); sub(e.otherwise)
                requireBool(e.condition, visible, where)
            }
            is Expr.Arith -> { sub(e.left); sub(e.right) }
            is Expr.Negate -> sub(e.operand)
            is Expr.Call -> {
                val listArg = if (e.fn == Functions.IN) e.args.getOrNull(1) as? Expr.Param else null
                e.args.forEach { if (it === listArg) param(listArg) else sub(it) }
                val sig = Functions.signature(e.fn)
                if (sig != null) {
                    val kinds = e.args.map { it.type.kind }
                    val fits = kinds.size == sig.params.size && kinds.indices.all { i ->
                        val t = e.args[i].type
                        val want = sig.params[i]
                        when {
                            e.fn == Functions.IN && i == 1 -> t.list
                            want == null -> !t.list && t.kind != ValueType.Kind.JSON
                            else -> !t.list && t.kind == want
                        }
                    }
                    if (!fits) problem("$where: ${e.fn} takes ${sig.params}, got ${e.args.map { it.type }}")
                    if (e.fn == Functions.IN && (listArg == null || listArg.type.kind != e.args[0].type.kind)) {
                        problem("$where: ${e.fn} needs a list parameter of ${e.args[0].type.kind}: $e")
                    }
                    if (sig.result != null && e.type.asNonNull() != sig.result.asNonNull()) problem("$where: ${e.fn} returns ${sig.result}, not ${e.type}")
                    if (e.fn == Functions.CONCAT && e.args.any { mayBeNull(it, visible) }) {
                        problem("$where: string concatenation of a value that may be null (Kotlin would print \"null\"): $e")
                    }
                }
            }
            is Expr.Exists -> checkScope(e.terms, visible, "exists in $where")
            is Expr.Aggregate -> {
                if (!allowAggregate) problem("$where: aggregate not allowed here: $e")
                e.arg?.let {
                    if (it.containsAggregate()) problem("$where: nested aggregate: $e")
                    expr(it, visible, where, allowAggregate = false)
                }
            }
        }
    }

    private fun paging(e: Expr?, what: String) {
        when (e) {
            null -> {}
            is Expr.Param -> {
                param(e)
                if (e.type != ValueType.INT) problem("$what must be a non-null int, got ${e.type}")
            }
            is Expr.Literal -> if (e.type != ValueType.INT || (e.value as Long) < 0) {
                problem("$what must be a non-negative int, got $e")
            }
            else -> problem("$what must be a parameter or literal, got $e")
        }
    }

    private fun lock(lock: RowLock, top: Map<Variable, Boolean>, aggregate: Boolean) {
        val matched = q.terms.any { it is Term.Match && it.subject == lock.subject && it.component == lock.component }
        if (!matched) problem("lock: no top-level match ${lock.component}(${lock.subject}) to lock")
        if (top[lock.subject] != false) problem("lock: ${lock.subject} is not bound by a top-level match")
        if (aggregate || q.distinct) problem("lock: cannot lock rows of an aggregate or distinct query")
        if (lock.component.source != SourceId.POSTGRES) problem("lock: ${lock.component} is not a Postgres component")
    }
}
