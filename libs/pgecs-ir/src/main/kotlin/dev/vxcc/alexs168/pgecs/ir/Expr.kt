package dev.vxcc.alexs168.pgecs.ir

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A typed value expression: value predicates ([Term.Filter]), projections, sort keys, grouping and
 * paging. Every node carries its [type]; [QueryIr.validate] checks that the types fit together.
 *
 * Semantics are Kotlin's, not SQL's, so a query means what its lambdas say: [Compare] with
 * [CompareOp.EQ] is Kotlin `==` (null-safe), [And] / [Or] are two-valued, [Arith] on [ValueType.Kind.INT]
 * is 64-bit integer arithmetic. The SQL compiler is responsible for preserving that (for example `=`
 * only where neither side can be null, `is not distinct from` otherwise, see [Nullability]).
 */
sealed interface Expr {
    val type: ValueType

    /** The value of a variable: the entity id of an [EntityVar], the whole value of a [ValueVar]. */
    data class Var(val variable: Variable) : Expr {
        override val type: ValueType get() = variable.type

        override fun toString(): String = variable.toString()
    }

    /**
     * A field inside the value of [variable], following the JSON object keys in [path]
     * (`it.address` is `Field(it, ["address"], TEXT)`). Keys are the serialized names
     * (`@SerialName` already resolved). The whole value of a scalar-root component
     * (`Component<Instant>`) is [Var], not a [Field] with an empty path.
     *
     * [type] is the field's declared type, nullable if the field is (`val verifiedAt: Instant?`).
     * A key missing from a stored value reads as null, like a Kotlin default of null.
     */
    data class Field(val variable: ValueVar, val path: List<String>, override val type: ValueType) : Expr {
        init {
            require(path.isNotEmpty()) { "field path is empty; use Expr.Var for the whole value" }
            require(path.all { it.isNotEmpty() }) { "empty key in field path $path" }
        }

        override fun toString(): String = variable.toString() + path.joinToString("") { ".$it" }
    }

    /**
     * Bind parameter number [index] (from 0, the position in [QueryIr.params]): a captured value,
     * a lifted constant, or a fixed entity id ([type] [ValueType.UUID]) in an entity position.
     * Parameter values are never part of the query's shape.
     */
    data class Param(val index: Int, override val type: ValueType) : Expr, EntityRef {
        init {
            require(index >= 0) { "parameter index $index < 0" }
        }

        override fun toString(): String = "?$index"
    }

    /**
     * A constant. [value] is a [Boolean], [Long], [Double], [String], [UUID], [Instant], [Duration]
     * or, for [ValueType.Kind.JSON], a JSON text, matching [type]'s kind; null is never a literal
     * (`x == null` is [IsNull]). Use [of] to get the normalised boxed type.
     *
     * Literals are part of what lowering produces, but not of a query's shape: [parameterize] turns
     * every literal into a [Param] before a query is planned or cached.
     */
    data class Literal(val value: Any, override val type: ValueType) : Expr, EntityRef {
        init {
            require(!type.nullable) { "a literal is never null; use Expr.IsNull" }
            val ok = when (type.kind) {
                ValueType.Kind.BOOL -> value is Boolean
                ValueType.Kind.INT -> value is Long
                ValueType.Kind.FLOAT -> value is Double
                ValueType.Kind.TEXT, ValueType.Kind.JSON -> value is String
                ValueType.Kind.UUID -> value is UUID
                ValueType.Kind.INSTANT -> value is Instant
                ValueType.Kind.DURATION -> value is Duration
            }
            require(ok) { "literal ${value::class.java.name} '$value' does not fit type $type" }
        }

        override fun toString(): String = when (value) {
            is String -> Canonical.quote(value)
            else -> "$value"
        }

        companion object {
            /** A literal of the natural type of [value], widening `Int`, `Short`, `Byte` and `Float`. */
            @JvmStatic
            fun of(value: Any): Literal = when (value) {
                is Boolean -> Literal(value, ValueType.BOOL)
                is Long -> Literal(value, ValueType.INT)
                is Int -> Literal(value.toLong(), ValueType.INT)
                is Short -> Literal(value.toLong(), ValueType.INT)
                is Byte -> Literal(value.toLong(), ValueType.INT)
                is Double -> Literal(value, ValueType.FLOAT)
                is Float -> Literal(value.toDouble(), ValueType.FLOAT)
                is String -> Literal(value, ValueType.TEXT)
                is Char -> Literal(value.toString(), ValueType.TEXT)
                is UUID -> Literal(value, ValueType.UUID)
                is Instant -> Literal(value, ValueType.INSTANT)
                is Duration -> Literal(value, ValueType.DURATION)
                else -> throw IllegalArgumentException("no IR literal for ${value::class.java.name}")
            }
        }
    }

    /**
     * Parameter number [index] of a decompiled lambda, before the lambda is attached to a term:
     * `it` in `where { it.address == addr }` is `Arg(0)` until the DSL substitutes the term's value
     * variable with [bindArgs]. Never valid in a finished query.
     */
    data class Arg(val index: Int, override val type: ValueType) : Expr {
        init {
            require(index >= 0) { "argument index $index < 0" }
        }

        override fun toString(): String = "%$index"
    }

    /** `left op right`, Kotlin semantics: [CompareOp.EQ] / [CompareOp.NE] are null-safe (`==`). */
    data class Compare(val op: CompareOp, val left: Expr, val right: Expr) : Expr {
        override val type: ValueType get() = ValueType.BOOL

        override fun toString(): String = "($left ${op.symbol} $right)"
    }

    /** Conjunction of two or more operands (use [and] to build one, flattened). */
    data class And(val operands: List<Expr>) : Expr {
        init {
            require(operands.size >= 2) { "And needs at least 2 operands" }
        }

        override val type: ValueType get() = ValueType.BOOL

        override fun toString(): String = operands.joinToString(" && ", "(", ")")
    }

    /** Disjunction of two or more operands (use [or] to build one, flattened). */
    data class Or(val operands: List<Expr>) : Expr {
        init {
            require(operands.size >= 2) { "Or needs at least 2 operands" }
        }

        override val type: ValueType get() = ValueType.BOOL

        override fun toString(): String = operands.joinToString(" || ", "(", ")")
    }

    data class Not(val operand: Expr) : Expr {
        override val type: ValueType get() = ValueType.BOOL

        override fun toString(): String = "!$operand"
    }

    /** `operand == null`. */
    data class IsNull(val operand: Expr) : Expr {
        override val type: ValueType get() = ValueType.BOOL

        override fun toString(): String = "($operand == null)"
    }

    /**
     * Kotlin `?:` chain: the first non-null operand. [type] is the last operand's type with the
     * nullability of the whole chain.
     */
    data class Coalesce(val operands: List<Expr>) : Expr {
        init {
            require(operands.size >= 2) { "Coalesce needs at least 2 operands" }
        }

        override val type: ValueType get() = operands.last().type

        override fun toString(): String = operands.joinToString(" ?: ", "(", ")")
    }

    /**
     * Binary arithmetic. Integer operations are on 64-bit integers, truncating division as in
     * Kotlin; [ValueType.Kind.INSTANT] ± [ValueType.Kind.DURATION] and differences of instants are
     * allowed. See [Types.arith] for the rules.
     */
    /**
     * Kotlin `if (condition) then else otherwise` as an expression (SQL `case when`). Both branches
     * have the same kind; the result is nullable if either branch is.
     */
    data class If(val condition: Expr, val then: Expr, val otherwise: Expr) : Expr {
        init {
            require(condition.type.kind == ValueType.Kind.BOOL) { "if condition is ${condition.type}, expected bool" }
            require(then.type.kind == otherwise.type.kind) { "if branches differ: ${then.type} and ${otherwise.type}" }
        }

        override val type: ValueType get() = then.type.copy(nullable = then.type.nullable || otherwise.type.nullable)

        override fun toString(): String = "(if $condition then $then else $otherwise)"
    }

    data class Arith(val op: ArithOp, val left: Expr, val right: Expr) : Expr {
        override val type: ValueType = Types.arith(op, left.type, right.type)
            ?: throw IllegalArgumentException("cannot apply ${op.symbol} to ${left.type} and ${right.type}")

        override fun toString(): String = "($left ${op.symbol} $right)"
    }

    data class Negate(val operand: Expr) : Expr {
        init {
            require(operand.type.isNumeric || operand.type.kind == ValueType.Kind.DURATION) {
                "cannot negate ${operand.type}"
            }
        }

        override val type: ValueType get() = operand.type

        override fun toString(): String = "-$operand"
    }

    /**
     * A call of function [fn] with result [type]. Built-in functions ([Functions]) are checked
     * against their signatures; others belong to a source (`mc:near`), which checks them when it
     * is asked whether it can run the term.
     */
    data class Call(val fn: FunctionId, val args: List<Expr>, override val type: ValueType) : Expr {
        override fun toString(): String = args.joinToString(", ", "${fn.name}(", ")")
    }

    /**
     * Whether [terms] match, with this expression's variables as correlation: a `not exists`,
     * `or` of terms, or `has a child that ...`. Variables first bound in [terms] are local to this
     * expression. `acc has !LobbyDenied` is `Not(Exists([Match(acc, LobbyDenied)]))`, and
     * `acc has (A or B)` is `Or(Exists([Match(acc, A)]), Exists([Match(acc, B)]))`.
     */
    data class Exists(val terms: List<Term>) : Expr {
        init {
            require(terms.isNotEmpty()) { "Exists without terms" }
        }

        override val type: ValueType get() = ValueType.BOOL

        override fun toString(): String = terms.joinToString(", ", "exists(", ")")
    }

    /**
     * An aggregate over the rows of a group ([QueryIr.groupBy]), only in [QueryIr.select] and
     * [QueryIr.orderBy]. [arg] null is `count(*)` and only valid for [AggregateFn.COUNT].
     */
    data class Aggregate(val fn: AggregateFn, val arg: Expr?, val distinct: Boolean = false) : Expr {
        init {
            require(arg != null || (fn == AggregateFn.COUNT && !distinct)) { "${fn.name.lowercase()} needs an argument" }
        }

        override val type: ValueType = Types.aggregate(fn, arg?.type)
            ?: throw IllegalArgumentException("cannot apply ${fn.name.lowercase()} to ${arg?.type}")

        override fun toString(): String =
            "${fn.name.lowercase()}(${if (distinct) "distinct " else ""}${arg ?: "*"})"
    }
}

enum class CompareOp(val symbol: String) {
    EQ("=="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">=");

    val isOrdering: Boolean get() = this != EQ && this != NE

    /** The same comparison with the operands swapped (`a < b` is `b > a`). */
    fun flipped(): CompareOp = when (this) {
        EQ -> EQ
        NE -> NE
        LT -> GT
        LE -> GE
        GT -> LT
        GE -> LE
    }

    /** The comparison that holds exactly when this one does not, for non-null operands. */
    fun negated(): CompareOp = when (this) {
        EQ -> NE
        NE -> EQ
        LT -> GE
        LE -> GT
        GT -> LE
        GE -> LT
    }
}

enum class ArithOp(val symbol: String) { PLUS("+"), MINUS("-"), TIMES("*"), DIV("/"), REM("%") }

enum class AggregateFn { COUNT, SUM, MIN, MAX, AVG }

/** Flattening conjunction: `and()` is `true`, `and(x)` is `x`. */
fun and(vararg operands: Expr): Expr = and(operands.asList())

fun and(operands: List<Expr>): Expr {
    val flat = operands.flatMap { if (it is Expr.And) it.operands else listOf(it) }
    return when (flat.size) {
        0 -> Expr.Literal(true, ValueType.BOOL)
        1 -> flat.single()
        else -> Expr.And(flat)
    }
}

/** Flattening disjunction: `or()` is `false`, `or(x)` is `x`. */
fun or(vararg operands: Expr): Expr = or(operands.asList())

fun or(operands: List<Expr>): Expr {
    val flat = operands.flatMap { if (it is Expr.Or) it.operands else listOf(it) }
    return when (flat.size) {
        0 -> Expr.Literal(false, ValueType.BOOL)
        1 -> flat.single()
        else -> Expr.Or(flat)
    }
}
