package dev.vxcc.alexs168.pgecs.ir

/**
 * The first-level IR of one pgecs query: the term graph the DSLs record, before planning.
 *
 * Nodes are [variables], edges are [terms]; [select], [groupBy], [orderBy], [limit], [offset],
 * [distinct] and [lock] say what to return. Result rows are a bag, like flecs' iteration: one row
 * per match of the top-level terms, unless [distinct] or [groupBy] says otherwise.
 *
 * **Shape and cache key.** A query whose [Expr.Literal]s have been lifted into parameters
 * ([parameterize]) is a *shape*: two shapes are [equals] exactly when they are the same query up to
 * parameter values, and their [hashCode] is computed once. [fingerprint] is a stable digest of the
 * same thing, for keys that outlive the JVM (hot shapes recorded at shutdown). Component names,
 * sources, variable names, types and the order of terms are part of the shape; parameter values,
 * including entity ids, are not.
 *
 * Construct with [IrBuilder] or directly, then [validate] (the builder does). The IR itself only
 * checks local invariants in constructors; [validate] checks scoping and typing across the query.
 */
data class QueryIr(
    val variables: List<Variable>,
    /** Types of the bind parameters; [Expr.Param.index] is the position here. */
    val params: List<ValueType>,
    val terms: List<Term>,
    val select: List<Projection>,
    val distinct: Boolean = false,
    val groupBy: List<Expr> = emptyList(),
    val orderBy: List<SortKey> = emptyList(),
    /** Maximum number of rows: a non-null [ValueType.INT] [Expr.Param] or [Expr.Literal]. */
    val limit: Expr? = null,
    /** Rows to skip, like [limit]. */
    val offset: Expr? = null,
    val lock: RowLock? = null,
) {
    /** Whether [select] or [orderBy] use aggregates, or [groupBy] is set. */
    val isAggregate: Boolean
        get() = groupBy.isNotEmpty() ||
            select.any { it.expr.containsAggregate() } ||
            orderBy.any { it.expr.containsAggregate() }

    /** The canonical text of this query; see [Canonical]. */
    val canonicalText: String by lazy(LazyThreadSafetyMode.PUBLICATION) { Canonical.render(this) }

    /** SHA-256 of [canonicalText], lower-case hex: stable across JVMs and versions of this library that print the same text. */
    val fingerprint: String by lazy(LazyThreadSafetyMode.PUBLICATION) { Canonical.fingerprint(canonicalText) }

    private val hash: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
        var h = variables.hashCode()
        h = 31 * h + params.hashCode()
        h = 31 * h + terms.hashCode()
        h = 31 * h + select.hashCode()
        h = 31 * h + distinct.hashCode()
        h = 31 * h + groupBy.hashCode()
        h = 31 * h + orderBy.hashCode()
        h = 31 * h + limit.hashCode()
        h = 31 * h + offset.hashCode()
        h = 31 * h + lock.hashCode()
        h
    }

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean = this === other || (
        other is QueryIr &&
            hash == other.hash &&
            variables == other.variables &&
            params == other.params &&
            terms == other.terms &&
            select == other.select &&
            distinct == other.distinct &&
            groupBy == other.groupBy &&
            orderBy == other.orderBy &&
            limit == other.limit &&
            offset == other.offset &&
            lock == other.lock
        )

    override fun toString(): String = canonicalText

    /** Checks scoping and typing; throws [IrValidationException] listing every problem found. */
    fun validate(): QueryIr = also { Validator.validate(it) }
}

/** One output column: [expr], named [name] in plans, result rows and generated SQL aliases. */
data class Projection(val name: String, val expr: Expr) {
    init {
        require(name.isNotBlank()) { "projection name is blank" }
    }

    override fun toString(): String = "$name = $expr"
}

/**
 * One sort key. Null placement defaults to Kotlin's (`compareValues`: null is smallest), so nulls
 * come first ascending and last descending; Postgres' own default is the opposite, so the SQL
 * compiler always spells it out.
 */
data class SortKey(
    val expr: Expr,
    val descending: Boolean = false,
    val nulls: NullOrder = if (descending) NullOrder.LAST else NullOrder.FIRST,
) {
    override fun toString(): String =
        "$expr ${if (descending) "desc" else "asc"} nulls ${nulls.name.lowercase()}"
}

enum class NullOrder { FIRST, LAST }

/**
 * Row locking for system batches: `FOR <mode> OF <the row of the top-level Match(subject, component)>
 * [SKIP LOCKED | NOWAIT]`. Only the matched component row is locked, never joined rows.
 */
data class RowLock(
    val subject: EntityVar,
    val component: ComponentRef,
    val mode: LockMode = LockMode.UPDATE,
    val wait: LockWait = LockWait.SKIP_LOCKED,
)

enum class LockMode { UPDATE, NO_KEY_UPDATE, SHARE, KEY_SHARE }

enum class LockWait { WAIT, SKIP_LOCKED, NOWAIT }

/** A query shape with the values of its parameters, ready to plan (by [shape]) and execute. */
class BoundQuery(val shape: QueryIr, arguments: List<Any?>) {
    val arguments: List<Any?> = arguments.toList()

    init {
        require(arguments.size == shape.params.size) {
            "query has ${shape.params.size} parameters, got ${arguments.size} arguments"
        }
        arguments.forEachIndexed { i, v -> Arguments.check(i, shape.params[i], v) }
    }

    override fun toString(): String = "$shape with ${arguments.joinToString(", ", "[", "]")}"
}

/** Run-time values of parameters. */
object Arguments {
    /**
     * Checks that [value] fits parameter [index] of [type]: the boxed types of [Expr.Literal] (and
     * `Int`, `Short`, `Byte`, `Float`, widened by [normalize]), null only for a nullable type.
     */
    @JvmStatic
    fun check(index: Int, type: ValueType, value: Any?) {
        if (value == null) {
            require(type.nullable) { "argument $index is null, but parameter type $type is not nullable" }
            return
        }
        val ok = when (type.kind) {
            ValueType.Kind.JSON -> true // encoded by the component's serializer at execution
            else -> runCatching { Expr.Literal(normalize(value), type.asNonNull()) }.isSuccess
        }
        require(ok) { "argument $index (${value::class.java.name}) does not fit parameter type $type" }
    }

    /** Widens `Int`, `Short`, `Byte` to `Long`, `Float` to `Double` and `Char` to `String`. */
    @JvmStatic
    fun normalize(value: Any): Any = when (value) {
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Float -> value.toDouble()
        is Char -> value.toString()
        else -> value
    }
}

internal fun Expr.containsAggregate(): Boolean = this is Expr.Aggregate || children().any { it.containsAggregate() }
