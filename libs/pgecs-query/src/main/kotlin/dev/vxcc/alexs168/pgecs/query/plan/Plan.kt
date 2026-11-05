package dev.vxcc.alexs168.pgecs.query.plan

/**
 * The logical plan of one query for the Postgres source: what the planner optimises and the SQL
 * compiler turns into a single statement.
 *
 * It is a tree of [Block]s. A block is one `select ... from ... where ...` scope: inner [Scan]s of
 * `ecs_component` that bind variables, a [Block.where] predicate, and optional groups that become
 * `left join`s. `exists` subqueries ([Pred.Exists]) are nested blocks that may reference the columns of
 * every enclosing block.
 *
 * Variables do not exist any more at this level. Lowering resolves each variable to one canonical
 * expression, the column of the scan that binds it (or a parameter for a constant entity), and every
 * other use refers to that expression. Two terms on the same variable therefore mention equal
 * expressions, which is what lets the optimiser recognise duplicate and contradicting terms.
 *
 * Everything here is immutable and has structural equality.
 */

/** Scalar types of values the compiler reads, compares and returns. */
enum class Kind(val sqlType: String) {
    /** An entity id (`uuid`). */
    ENTITY("uuid"),

    /** Text; also UUIDs stored as strings (`UuidAsString`) in component values. */
    STRING("text"),

    /** 64-bit integer; also instants stored as epoch milliseconds (`InstantAsEpochMillis`). */
    LONG("bigint"),
    DOUBLE("double precision"),
    BOOLEAN("boolean"),

    /** Any JSON value (`jsonb`). */
    JSON("jsonb"),
}

/** A column of `ecs_component`. */
enum class Column(val sql: String, val kind: Kind) {
    ENTITY("entity", Kind.ENTITY),
    TARGET("target", Kind.ENTITY),
    VALUE("value", Kind.JSON),

    /** The component name; only compared with literal names, which the compiler inlines. */
    REL("rel", Kind.STRING),
}

/** Functions the compiler can translate; anything else is rejected when the query is lowered. */
enum class Fn(val kind: Kind?) {
    LOWER(Kind.STRING),
    UPPER(Kind.STRING),
    TRIM(Kind.STRING),
    LENGTH(Kind.LONG),
    STARTS_WITH(Kind.BOOLEAN),
    ENDS_WITH(Kind.BOOLEAN),
    CONTAINS(Kind.BOOLEAN),
    ABS(null),
    COALESCE(null),
    CONCAT(Kind.STRING),

    /** `text ~ pattern`, PostgreSQL regular expressions. */
    REGEX(Kind.BOOLEAN),

    /** A JSON array contains a scalar: `array @> jsonb_build_array(x)`. */
    JSON_CONTAINS(Kind.BOOLEAN),

    /** Length of a JSON array, null for anything else. */
    JSON_SIZE(Kind.LONG),

    /** Element of a JSON array at a non-negative index, read as the call's kind; null out of range. */
    JSON_GET(null),
}

enum class ArithOp(val sql: String) { ADD("+"), SUB("-"), MUL("*"), DIV("/"), REM("%") }

enum class AggFn { COUNT, SUM, MIN, MAX, AVG }

/** Comparison operators with SQL semantics: unknown (null) when an operand is null, except the two `DISTINCT` ones. */
enum class CmpOp(val sql: String) {
    EQ("="),
    NE("<>"),
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">="),

    /** `is not distinct from`: null-safe equality, never null. */
    NOT_DISTINCT("is not distinct from"),

    /** `is distinct from`: null-safe inequality, never null. */
    DISTINCT("is distinct from"),
    ;

    /** The operator that is true exactly when this one is false, under SQL three-valued logic. */
    val negated: CmpOp
        get() = when (this) {
            EQ -> NE
            NE -> EQ
            LT -> GE
            LE -> GT
            GT -> LE
            GE -> LT
            NOT_DISTINCT -> DISTINCT
            DISTINCT -> NOT_DISTINCT
        }

    /** The operator with its operands swapped (`a < b` is `b > a`). */
    val flipped: CmpOp
        get() = when (this) {
            LT -> GT
            LE -> GE
            GT -> LT
            GE -> LE
            else -> this
        }
}

/** A scalar expression. */
sealed interface Expr {
    /** The expression's type, or null when it is only known from context (a bare `null`). */
    val kind: Kind?

    /** A column of the scan with alias [alias]. */
    data class Col(val alias: Int, val column: Column) : Expr {
        override val kind: Kind get() = column.kind
    }

    /**
     * A field inside the `value` of scan [alias], read as [kind]. An empty [path] is the value itself
     * (a component whose value is a scalar). Path elements are object keys ([String]) or array indexes
     * ([Int]).
     */
    data class Field(val alias: Int, val path: List<Any>, override val kind: Kind) : Expr {
        init {
            require(path.all { it is String || it is Int }) { "path elements must be String or Int: $path" }
        }
    }

    /**
     * Query parameter [index], bound at execution from the caller's arguments. [list] parameters are
     * arrays of [kind], used with `= any(...)`.
     */
    data class Param(val index: Int, override val kind: Kind, val list: Boolean = false) : Expr

    /** A constant; bound as a parameter, never spliced into the SQL text. */
    data class Const(val value: Any?, override val kind: Kind?) : Expr

    /** The nil entity (`00000000-0000-0000-0000-000000000000`), the target of plain components. */
    data object Nil : Expr {
        override val kind: Kind get() = Kind.ENTITY
    }

    data class Call(val fn: Fn, val args: List<Expr>, override val kind: Kind?) : Expr

    data class Arith(val op: ArithOp, val left: Expr, val right: Expr, override val kind: Kind?) : Expr

    /** An aggregate; only valid in projections, `having` and `order by` of a grouped query. */
    data class Aggregate(val fn: AggFn, val arg: Expr?, val distinct: Boolean, override val kind: Kind) : Expr

    /** [expr] converted to [kind]; only entity to text (an entity compared with a UUID stored as a string). */
    data class Cast(val expr: Expr, override val kind: Kind) : Expr

    /** The entity of the registered type [name] (a component, relation or capability), looked up in the catalog. */
    data class TypeEntity(val name: String) : Expr {
        override val kind: Kind get() = Kind.ENTITY
    }

    /** The time of this execution, in epoch milliseconds; bound once per execution. */
    data object Now : Expr {
        override val kind: Kind get() = Kind.LONG
    }

    /** `case when [condition] then [then] else [otherwise] end`. */
    data class If(val condition: Pred, val then: Expr, val otherwise: Expr, override val kind: Kind?) : Expr

    /** A predicate used as a boolean value (in a projection). Kotlin semantics: never null. */
    data class Bool(val pred: Pred) : Expr {
        override val kind: Kind get() = Kind.BOOLEAN
    }
}

/**
 * A predicate with SQL semantics (three-valued). Lowering already translated Kotlin's two-valued
 * semantics, so any rewrite that is valid in SQL's three-valued logic is valid here.
 */
sealed interface Pred {
    data object True : Pred
    data object False : Pred

    data class Cmp(val op: CmpOp, val left: Expr, val right: Expr) : Pred

    data class IsNull(val expr: Expr) : Pred

    /** `left = any(right)` for a list parameter. */
    data class InList(val left: Expr, val right: Expr) : Pred

    /** A boolean-valued expression (such as `starts_with`) used as a predicate. */
    data class Test(val expr: Expr) : Pred

    /**
     * A row `(entity = [subject], rel in [rels], target = [target])` exists. A null [subject] or
     * [target] means any entity; empty [rels] means any relation (a pair, so the target is not nil).
     * This is the probe for tags, plain components and pairs whose variables are all bound elsewhere;
     * it never binds anything. Never null.
     */
    data class Has(val subject: Expr?, val rels: List<String>, val target: Expr?) : Pred {
        init {
            require(rels.isNotEmpty() || target != Expr.Nil) { "any relation with the nil target matches nothing" }
        }
    }

    /** `exists (select 1 from <block>)`. Never null. */
    data class Exists(val block: Block) : Pred

    data class And(val items: List<Pred>) : Pred
    data class Or(val items: List<Pred>) : Pred
    data class Not(val pred: Pred) : Pred

    companion object {
        fun and(items: List<Pred>): Pred {
            val flat = items.flatMap { if (it is And) it.items else listOf(it) }.filter { it != True }
            return when {
                False in flat -> False
                flat.isEmpty() -> True
                flat.size == 1 -> flat[0]
                else -> And(flat)
            }
        }

        fun or(items: List<Pred>): Pred {
            val flat = items.flatMap { if (it is Or) it.items else listOf(it) }.filter { it != False }
            return when {
                True in flat -> True
                flat.isEmpty() -> False
                flat.size == 1 -> flat[0]
                else -> Or(flat)
            }
        }

        fun conjuncts(p: Pred): List<Pred> = when (p) {
            True -> emptyList()
            is And -> p.items.flatMap(::conjuncts)
            else -> listOf(p)
        }
    }
}

/**
 * One row source: `ecs_component` as [alias], restricted to `rel = '[rel]'` (any relation, that is
 * any pair, when [rel] is null).
 *
 * [subject] and [target] are the canonical expressions of the scan's entity and target: either the
 * scan's own column (the scan binds that variable) or an expression from elsewhere that the column must
 * equal. A null [subject] or [target] leaves it unconstrained (a wildcard that binds nothing).
 *
 * With [distinct], the scan is `(select distinct entity, target ...)` over its non-wildcard positions:
 * one row per distinct subject (and target), which is how a wildcard binds a variable without
 * multiplying rows (`ChildOf(mail, *)` holds once for a mail with two parents). Only those columns
 * can be read from such a scan, not the value.
 */
data class Scan(
    val alias: Int,
    val rel: String?,
    val subject: Expr?,
    val target: Expr?,
    val distinct: Boolean = false,
) {

    /** The conditions this scan adds besides `rel = ...`. */
    fun conditions(): List<Pred> = buildList {
        val own = Expr.Col(alias, Column.ENTITY)
        if (subject != null && subject != own) add(Pred.Cmp(CmpOp.EQ, own, subject))
        val ownTarget = Expr.Col(alias, Column.TARGET)
        if (target != null && target != ownTarget) add(Pred.Cmp(CmpOp.EQ, ownTarget, target))
    }
}

/**
 * A select scope. [scans] are joined (inner), [where] filters, and each of [optionals] is left-joined
 * after them. [id] is unique within one query.
 */
data class Block(
    val id: Int,
    val scans: List<Scan>,
    val where: Pred,
    val optionals: List<Block> = emptyList(),
) {
    /** Every alias this block and its optionals introduce (not nested `exists` blocks). */
    val aliases: Set<Int> get() = scans.mapTo(mutableSetOf()) { it.alias }.also { s -> optionals.forEach { s += it.aliases } }
}

data class SelectItem(val name: String, val expr: Expr)

data class OrderKey(val expr: Expr, val descending: Boolean = false, val nullsFirst: Boolean? = null)

/** Row locking for system batches: `for <mode> of <alias> [<wait>]`. */
data class Lock(val alias: Int, val mode: String = "update", val wait: String = "skip locked")

/** A whole query: a root block plus what is done with its rows. */
data class LogicalQuery(
    val root: Block,
    val select: List<SelectItem>,
    val distinct: Boolean = false,
    val groupBy: List<Expr> = emptyList(),
    val having: Pred = Pred.True,
    val orderBy: List<OrderKey> = emptyList(),
    val limit: Expr? = null,
    val offset: Expr? = null,
    val lock: Lock? = null,
) {
    init {
        require(select.isNotEmpty()) { "a query selects at least one value" }
        if (lock != null) {
            require(!distinct && groupBy.isEmpty() && select.none { containsAggregate(it.expr) }) {
                "for update cannot be combined with distinct or aggregation"
            }
        }
    }

    val isAggregate: Boolean get() = groupBy.isNotEmpty() || select.any { containsAggregate(it.expr) }
}

fun containsAggregate(e: Expr): Boolean = when (e) {
    is Expr.Aggregate -> true
    is Expr.Call -> e.args.any(::containsAggregate)
    is Expr.Arith -> containsAggregate(e.left) || containsAggregate(e.right)
    is Expr.If -> containsAggregate(e.then) || containsAggregate(e.otherwise)
    else -> false
}

/** Calls [f] on every sub-expression of [e], including [e]. */
fun walk(e: Expr, f: (Expr) -> Unit) {
    f(e)
    when (e) {
        is Expr.Call -> e.args.forEach { walk(it, f) }
        is Expr.Arith -> { walk(e.left, f); walk(e.right, f) }
        is Expr.Aggregate -> e.arg?.let { walk(it, f) }
        is Expr.Bool -> walk(e.pred, f)
        is Expr.Cast -> walk(e.expr, f)
        is Expr.If -> { walk(e.condition, f); walk(e.then, f); walk(e.otherwise, f) }
        else -> {}
    }
}

/** Calls [f] on every expression in [p], not descending into nested `exists` blocks. */
fun walk(p: Pred, f: (Expr) -> Unit) {
    when (p) {
        is Pred.Cmp -> { walk(p.left, f); walk(p.right, f) }
        is Pred.IsNull -> walk(p.expr, f)
        is Pred.InList -> { walk(p.left, f); walk(p.right, f) }
        is Pred.Test -> walk(p.expr, f)
        is Pred.Has -> { p.subject?.let { walk(it, f) }; p.target?.let { walk(it, f) } }
        is Pred.And -> p.items.forEach { walk(it, f) }
        is Pred.Or -> p.items.forEach { walk(it, f) }
        is Pred.Not -> walk(p.pred, f)
        is Pred.Exists, Pred.True, Pred.False -> {}
    }
}

/**
 * The scan aliases [e] reads, including those of enclosing blocks that `exists` blocks inside it
 * read (a projection or `if` condition `x has T`).
 */
fun aliasesOf(e: Expr): Set<Int> = buildSet {
    walk(e) {
        when (it) {
            is Expr.Col -> add(it.alias)
            is Expr.Field -> add(it.alias)
            is Expr.Bool -> addExistsAliases(it.pred)
            is Expr.If -> addExistsAliases(it.condition)
            else -> {}
        }
    }
}

/** The scan aliases [p] reads, including those of enclosing blocks that nested `exists` blocks read. */
fun aliasesOf(p: Pred): Set<Int> = buildSet {
    walk(p) { e -> addAll(aliasesOf(e)) }
    addExistsAliases(p)
}

/** The free aliases of the `exists` blocks of [p] (not those inside its expressions: [walk] reaches those). */
private fun MutableSet<Int>.addExistsAliases(p: Pred) {
    when (p) {
        is Pred.Exists -> addAll(freeAliases(p.block))
        is Pred.And -> p.items.forEach { addExistsAliases(it) }
        is Pred.Or -> p.items.forEach { addExistsAliases(it) }
        is Pred.Not -> addExistsAliases(p.pred)
        else -> {}
    }
}

/** The aliases of enclosing blocks that [block] (with its optionals and nested blocks) refers to. */
fun freeAliases(block: Block): Set<Int> {
    val used = mutableSetOf<Int>()
    fun visit(b: Block) {
        b.scans.forEach { s -> s.conditions().forEach { used += aliasesOf(it) } }
        used += aliasesOf(b.where)
        b.optionals.forEach(::visit)
    }
    visit(block)
    return used - block.aliases
}
