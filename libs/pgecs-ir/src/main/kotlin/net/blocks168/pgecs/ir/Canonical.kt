package net.blocks168.pgecs.ir

import java.security.MessageDigest

/**
 * The canonical text form of a [QueryIr]: an s-expression that names every part of the shape and
 * nothing else, so that two queries have the same text exactly when they are [QueryIr.equals]. It
 * is what [QueryIr.fingerprint] hashes and what plans and errors print.
 *
 * ```
 * (query v1
 *   (vars (entity 0 acc) (entity 1 mail) (value 2 m text))
 *   (params text)
 *   (terms
 *     (match (var 0) (component "b8:iam:account:v1" tag postgres) _ _)
 *     (match (var 1) (component "pgecs:child-of" relation postgres) (var 0) _)
 *     (match (var 1) (component "b8:iam:email:v1" value text postgres) _ (var 2))
 *     (filter (eq (field 2 "address" text) (param 0 text))))
 *   (select ("acc" (var 0)))
 *   (distinct false) (group-by) (order-by) (limit _) (offset _) (lock _))
 * ```
 *
 * The leading version ([VERSION]) changes whenever the format does, so fingerprints recorded by an
 * older library never match by accident. Strings are quoted with `"` and escape `"`, `\` and
 * control characters as `\u{hex}`, so the text is unambiguous.
 */
object Canonical {
    const val VERSION = 1

    @JvmStatic
    fun render(q: QueryIr): String = buildString {
        append("(query v").append(VERSION)
        append("\n  (vars")
        q.variables.forEach { append(' '); variable(it) }
        append(")\n  (params")
        q.params.forEach { append(' ').append(type(it)) }
        append(")\n  (terms")
        q.terms.forEach { append("\n    "); term(it) }
        append(")\n  (select")
        q.select.forEach { append(" (").append(quote(it.name)).append(' '); expr(it.expr); append(')') }
        append(")\n  (distinct ").append(q.distinct).append(')')
        append(" (group-by")
        q.groupBy.forEach { append(' '); expr(it) }
        append(") (order-by")
        q.orderBy.forEach {
            append(" (")
            expr(it.expr)
            append(if (it.descending) " desc" else " asc").append(" nulls-").append(it.nulls.name.lowercase())
            append(')')
        }
        append(") (limit ")
        optional(q.limit)
        append(") (offset ")
        optional(q.offset)
        append(") (lock ")
        val lock = q.lock
        if (lock == null) {
            append('_')
        } else {
            append("(var ").append(lock.subject.index).append(") ")
            component(lock.component)
            append(' ').append(lock.mode.name.lowercase()).append(' ').append(lock.wait.name.lowercase())
        }
        append("))")
    }

    /**
     * Reads canonical text back into the query it was rendered from: `parse(render(q)) == q`.
     * Throws [CanonicalParseException] on malformed text or a different [VERSION] (a shape recorded
     * by an older library is simply recompiled from its call site instead). Does not validate.
     */
    @JvmStatic
    fun parse(text: String): QueryIr = CanonicalParser.parse(text)

    /** SHA-256 of [text] as UTF-8, lower-case hex. */
    @JvmStatic
    fun fingerprint(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** [s] as a canonical string literal. */
    @JvmStatic
    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c < ' ' || c == '\u007f' -> append("\\u{").append(Integer.toHexString(c.code)).append('}')
                else -> append(c)
            }
        }
        append('"')
    }

    private fun type(t: ValueType): String = t.toString()

    private fun StringBuilder.optional(e: Expr?) {
        if (e == null) append('_') else expr(e)
    }

    private fun StringBuilder.variable(v: Variable) {
        when (v) {
            is EntityVar -> append("(entity ").append(v.index).append(' ').append(v.name).append(')')
            is ValueVar -> append("(value ").append(v.index).append(' ').append(v.name).append(' ')
                .append(type(v.type)).append(')')
        }
    }

    private fun StringBuilder.component(c: ComponentSlot) {
        when (c) {
            is ComponentRef -> {
                append("(component ").append(quote(c.name)).append(' ').append(c.kind.name.lowercase())
                c.valueType?.let { append(' ').append(type(it)) }
                append(' ').append(c.source.name).append(')')
            }
            is AnyRelation -> append("(any-relation ").append(c.source.name).append(')')
        }
    }

    private fun StringBuilder.entity(e: EntityRef?) {
        when (e) {
            null -> append('_')
            is EntityVar -> append("(var ").append(e.index).append(')')
            AnyEntity -> append("(any)")
            is TypeEntity -> append("(type-entity ").append(quote(e.name)).append(')')
            is Expr.Param -> expr(e)
            is Expr.Literal -> expr(e)
        }
    }

    private fun StringBuilder.term(t: Term) {
        when (t) {
            is Term.Match -> {
                append("(match ")
                entity(t.subject)
                append(' ')
                component(t.component)
                append(' ')
                entity(t.target)
                append(' ')
                if (t.value == null) append('_') else append("(var ").append(t.value.index).append(')')
                append(')')
            }
            is Term.Filter -> {
                append("(filter ")
                expr(t.predicate)
                append(')')
            }
            is Term.Optional -> {
                append("(optional")
                t.terms.forEach { append(' '); term(it) }
                append(')')
            }
        }
    }

    private fun StringBuilder.list(head: String, items: List<Expr>) {
        append('(').append(head)
        items.forEach { append(' '); expr(it) }
        append(')')
    }

    private fun StringBuilder.expr(e: Expr) {
        when (e) {
            is Expr.Var -> append("(var ").append(e.variable.index).append(')')
            is Expr.Field -> {
                append("(field ").append(e.variable.index)
                e.path.forEach { append(' ').append(quote(it)) }
                append(' ').append(type(e.type)).append(')')
            }
            is Expr.Param -> append("(param ").append(e.index).append(' ').append(type(e.type)).append(')')
            is Expr.Literal -> {
                append("(literal ").append(type(e.type)).append(' ')
                when (val v = e.value) {
                    is String -> append(quote(v))
                    is Double -> append(java.lang.Double.doubleToLongBits(v).toULong().toString(16))
                    else -> append(quote(v.toString()))
                }
                append(')')
            }
            is Expr.Arg -> append("(arg ").append(e.index).append(' ').append(type(e.type)).append(')')
            is Expr.Compare -> list(e.op.name.lowercase(), listOf(e.left, e.right))
            is Expr.And -> list("and", e.operands)
            is Expr.Or -> list("or", e.operands)
            is Expr.Not -> list("not", listOf(e.operand))
            is Expr.IsNull -> list("is-null", listOf(e.operand))
            is Expr.Coalesce -> list("coalesce", e.operands)
            is Expr.Arith -> list(e.op.name.lowercase(), listOf(e.left, e.right))
            is Expr.Negate -> list("negate", listOf(e.operand))
            is Expr.Call -> {
                append("(call ").append(e.fn.name).append(' ').append(type(e.type))
                e.args.forEach { append(' '); expr(it) }
                append(')')
            }
            is Expr.Exists -> {
                append("(exists")
                e.terms.forEach { append(' '); term(it) }
                append(')')
            }
            is Expr.Aggregate -> {
                append('(').append(e.fn.name.lowercase())
                if (e.distinct) append(" distinct")
                append(' ')
                optional(e.arg)
                append(')')
            }
        }
    }
}
