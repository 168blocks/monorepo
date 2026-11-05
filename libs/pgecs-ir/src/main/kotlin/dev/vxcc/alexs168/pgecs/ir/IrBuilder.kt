package dev.vxcc.alexs168.pgecs.ir

/**
 * Builds a [QueryIr] term by term, numbering variables and parameters in declaration order. The
 * DSLs and tests use it; [build] validates.
 *
 * ```kotlin
 * val q = IrBuilder().run {
 *     val acc = entity("acc")
 *     val mail = entity("mail")
 *     val m = value("m", Email.valueType)
 *     match(acc, Account)
 *     filter(Expr.Not(exists { match(acc, LobbyDenied) }))
 *     match(mail, ChildOf, target = acc)
 *     match(mail, Email, value = m)
 *     filter(Expr.Compare(CompareOp.EQ, Expr.Field(m, listOf("address"), ValueType.TEXT), param(ValueType.TEXT)))
 *     select("acc", Expr.Var(acc))
 *     build()
 * }
 * ```
 */
class IrBuilder : TermsBuilder(Counters()) {
    private val select = mutableListOf<Projection>()
    private val groupBy = mutableListOf<Expr>()
    private val orderBy = mutableListOf<SortKey>()
    private var limit: Expr? = null
    private var offset: Expr? = null
    private var distinct = false
    private var lock: RowLock? = null

    fun entity(name: String): EntityVar = EntityVar(counters.variables.size, name).also { counters.variables += it }

    fun value(name: String, type: ValueType): ValueVar =
        ValueVar(counters.variables.size, name, type).also { counters.variables += it }

    /** A new bind parameter of [type]. */
    fun param(type: ValueType): Expr.Param = Expr.Param(counters.params.size, type).also { counters.params += type }

    fun select(name: String, expr: Expr) = apply { select += Projection(name, expr) }

    fun select(v: Variable) = select(v.name, Expr.Var(v))

    fun groupBy(expr: Expr) = apply { groupBy += expr }

    fun orderBy(expr: Expr, descending: Boolean = false) = apply { orderBy += SortKey(expr, descending) }

    fun orderBy(key: SortKey) = apply { orderBy += key }

    fun limit(expr: Expr) = apply { limit = expr }

    fun offset(expr: Expr) = apply { offset = expr }

    fun distinct() = apply { distinct = true }

    fun lock(lock: RowLock) = apply { this.lock = lock }

    /** The query, without validating it. */
    fun buildUnchecked(): QueryIr = QueryIr(
        variables = counters.variables.toList(),
        params = counters.params.toList(),
        terms = terms.toList(),
        select = select.toList(),
        distinct = distinct,
        groupBy = groupBy.toList(),
        orderBy = orderBy.toList(),
        limit = limit,
        offset = offset,
        lock = lock,
    )

    /** The query, validated ([QueryIr.validate]). */
    fun build(): QueryIr = buildUnchecked().validate()
}

/** The terms of a query, an [Term.Optional] group or an [Expr.Exists]. */
open class TermsBuilder internal constructor(internal val counters: Counters) {
    internal val terms = mutableListOf<Term>()

    fun match(subject: EntityRef, component: ComponentSlot, target: EntityRef? = null, value: ValueVar? = null) =
        apply { terms += Term.Match(subject, component, target, value) }

    fun filter(predicate: Expr) = apply { terms += Term.Filter(predicate) }

    fun optional(block: TermsBuilder.() -> Unit) = apply {
        terms += Term.Optional(TermsBuilder(counters).apply(block).terms.toList())
    }

    /** An [Expr.Exists] over the terms [block] adds; variables are declared on the [IrBuilder] as usual. */
    fun exists(block: TermsBuilder.() -> Unit): Expr.Exists = Expr.Exists(TermsBuilder(counters).apply(block).terms.toList())
}

internal class Counters {
    val variables = mutableListOf<Variable>()
    val params = mutableListOf<ValueType>()
}
