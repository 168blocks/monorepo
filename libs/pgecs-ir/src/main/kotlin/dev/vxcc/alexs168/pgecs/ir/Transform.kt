package dev.vxcc.alexs168.pgecs.ir

/** Direct sub-expressions, in a fixed order. [Expr.Exists] yields the expressions of its terms. */
fun Expr.children(): List<Expr> = when (this) {
    is Expr.Var, is Expr.Field, is Expr.Param, is Expr.Literal, is Expr.Arg -> emptyList()
    is Expr.Compare -> listOf(left, right)
    is Expr.And -> operands
    is Expr.Or -> operands
    is Expr.Not -> listOf(operand)
    is Expr.IsNull -> listOf(operand)
    is Expr.Coalesce -> operands
    is Expr.If -> listOf(condition, then, otherwise)
    is Expr.Arith -> listOf(left, right)
    is Expr.Negate -> listOf(operand)
    is Expr.Call -> args
    is Expr.Exists -> terms.flatMap { it.expressions() }
    is Expr.Aggregate -> listOfNotNull(arg)
}

/** Expressions directly inside a term (entity references that are [Expr.Param] or [Expr.Literal] included). */
fun Term.expressions(): List<Expr> = when (this) {
    is Term.Match -> listOfNotNull(subject as? Expr, target as? Expr)
    is Term.Filter -> listOf(predicate)
    is Term.Optional -> terms.flatMap { it.expressions() }
}

/**
 * Rebuilds this expression bottom-up: children first, then [f] on the rebuilt node; [f] returns
 * its argument to keep it. Recurses into the terms of [Expr.Exists], including entity references
 * that are expressions (a [Expr.Param] or [Expr.Literal] in an entity position must stay one of
 * those).
 */
fun Expr.transform(f: (Expr) -> Expr): Expr {
    val rebuilt = when (this) {
        is Expr.Var, is Expr.Field, is Expr.Param, is Expr.Literal, is Expr.Arg -> this
        is Expr.Compare -> Expr.Compare(op, left.transform(f), right.transform(f))
        is Expr.And -> Expr.And(operands.map { it.transform(f) })
        is Expr.Or -> Expr.Or(operands.map { it.transform(f) })
        is Expr.Not -> Expr.Not(operand.transform(f))
        is Expr.IsNull -> Expr.IsNull(operand.transform(f))
        is Expr.Coalesce -> Expr.Coalesce(operands.map { it.transform(f) })
        is Expr.If -> Expr.If(condition.transform(f), then.transform(f), otherwise.transform(f))
        is Expr.Arith -> Expr.Arith(op, left.transform(f), right.transform(f))
        is Expr.Negate -> Expr.Negate(operand.transform(f))
        is Expr.Call -> Expr.Call(fn, args.map { it.transform(f) }, type)
        is Expr.Exists -> Expr.Exists(terms.map { it.transform(f) })
        is Expr.Aggregate -> Expr.Aggregate(fn, arg?.transform(f), distinct)
    }
    return f(rebuilt)
}

/** [Expr.transform] over every expression of this term. */
fun Term.transform(f: (Expr) -> Expr): Term = when (this) {
    is Term.Match -> copy(subject = subject.transformEntity(f), target = target?.transformEntity(f))
    is Term.Filter -> Term.Filter(predicate.transform(f))
    is Term.Optional -> Term.Optional(terms.map { it.transform(f) })
}

private fun EntityRef.transformEntity(f: (Expr) -> Expr): EntityRef = when (this) {
    is Expr -> {
        val r = transform(f)
        require(r is EntityRef) { "an entity reference must stay a parameter or literal, got $r" }
        r
    }
    else -> this
}

/**
 * [Expr.transform] over every expression of the query, in a fixed order (terms, select, groupBy,
 * orderBy, limit, offset), which is also the order [Canonical] prints them in.
 */
fun QueryIr.transform(f: (Expr) -> Expr): QueryIr = copy(
    terms = terms.map { it.transform(f) },
    select = select.map { it.copy(expr = it.expr.transform(f)) },
    groupBy = groupBy.map { it.transform(f) },
    orderBy = orderBy.map { it.copy(expr = it.expr.transform(f)) },
    limit = limit?.transform(f),
    offset = offset?.transform(f),
)

/**
 * Substitutes the lambda arguments [Expr.Arg] `i` with `args[i]`: how a decompiled `where { }`
 * lambda is attached to the value variable of its term. Types must match exactly.
 */
fun Expr.bindArgs(args: List<Expr>): Expr = transform { e ->
    if (e is Expr.Arg) {
        val a = args.getOrNull(e.index) ?: throw IllegalArgumentException("no value for lambda argument ${e.index}")
        require(a.type.asNonNull() == e.type.asNonNull()) { "lambda argument ${e.index} is ${e.type}, got ${a.type}" }
        a
    } else {
        e
    }
}

/**
 * Renumbers parameters: [Expr.Param] `i` becomes `mapping(i)`. Used to merge the parameters of a
 * decompiled lambda (numbered from 0 per lambda) into the query's.
 */
fun Expr.mapParams(mapping: (Int) -> Int): Expr = transform { e ->
    if (e is Expr.Param) Expr.Param(mapping(e.index), e.type) else e
}

/**
 * Lifts every [Expr.Literal] of this query into a new parameter, appended after the existing ones
 * in [Canonical] order, and returns the resulting shape with all values: [arguments] for the
 * existing parameters followed by the literals' values. The result is what the plan cache is keyed
 * by.
 */
fun QueryIr.parameterize(arguments: List<Any?> = emptyList()): BoundQuery {
    require(arguments.size == params.size) { "query has ${params.size} parameters, got ${arguments.size} arguments" }
    val newParams = params.toMutableList()
    val values = arguments.toMutableList()
    val lifted = transform { e ->
        if (e is Expr.Literal) {
            newParams += e.type
            values += e.value
            Expr.Param(newParams.size - 1, e.type)
        } else {
            e
        }
    }
    return BoundQuery(lifted.copy(params = newParams.toList()), values)
}

/** Every variable referenced anywhere in this expression (including entity positions inside [Expr.Exists]). */
fun Expr.variables(): Set<Variable> = buildSet { collectVariables(this@variables, this) }

private fun collectVariables(e: Expr, out: MutableSet<Variable>) {
    when (e) {
        is Expr.Var -> out += e.variable
        is Expr.Field -> out += e.variable
        is Expr.Exists -> e.terms.forEach { collectTermVariables(it, out) }
        else -> e.children().forEach { collectVariables(it, out) }
    }
}

internal fun collectTermVariables(t: Term, out: MutableSet<Variable>) {
    when (t) {
        is Term.Match -> {
            (t.subject as? EntityVar)?.let(out::add)
            (t.target as? EntityVar)?.let(out::add)
            t.value?.let(out::add)
        }
        is Term.Filter -> collectVariables(t.predicate, out)
        is Term.Optional -> t.terms.forEach { collectTermVariables(it, out) }
    }
}
