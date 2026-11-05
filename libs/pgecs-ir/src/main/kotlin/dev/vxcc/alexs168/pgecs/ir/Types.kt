package dev.vxcc.alexs168.pgecs.ir

import dev.vxcc.alexs168.pgecs.ir.ValueType.Kind

/** Typing rules of the IR. */
object Types {

    /**
     * Result type of `left op right`, or null if the operation is not allowed. Nullable if either
     * operand is.
     *
     * - `INT op INT` is `INT`; with a `FLOAT` operand, `FLOAT`.
     * - `INSTANT ± DURATION` is `INSTANT`, `DURATION + INSTANT` is `INSTANT`,
     *   `INSTANT - INSTANT` is `DURATION`.
     * - `DURATION ± DURATION` is `DURATION`; `DURATION * INT`, `INT * DURATION` and `DURATION / INT`
     *   are `DURATION`.
     */
    @JvmStatic
    fun arith(op: ArithOp, left: ValueType, right: ValueType): ValueType? {
        val l = left.kind
        val r = right.kind
        val kind = when {
            left.isNumeric && right.isNumeric -> if (l == Kind.FLOAT || r == Kind.FLOAT) Kind.FLOAT else Kind.INT
            l == Kind.INSTANT && r == Kind.DURATION && (op == ArithOp.PLUS || op == ArithOp.MINUS) -> Kind.INSTANT
            l == Kind.DURATION && r == Kind.INSTANT && op == ArithOp.PLUS -> Kind.INSTANT
            l == Kind.INSTANT && r == Kind.INSTANT && op == ArithOp.MINUS -> Kind.DURATION
            l == Kind.DURATION && r == Kind.DURATION && (op == ArithOp.PLUS || op == ArithOp.MINUS) -> Kind.DURATION
            l == Kind.DURATION && r == Kind.INT && (op == ArithOp.TIMES || op == ArithOp.DIV) -> Kind.DURATION
            l == Kind.INT && r == Kind.DURATION && op == ArithOp.TIMES -> Kind.DURATION
            else -> return null
        }
        return ValueType(kind, left.nullable || right.nullable)
    }

    /**
     * Result type of an aggregate, or null if not allowed. `count` is a non-null `INT`; `sum` keeps
     * numeric and duration types; `avg` is `FLOAT` for numbers; `min` / `max` work on ordered types.
     * Everything but `count` is nullable (no rows in the group, as in SQL and as Kotlin's
     * `minOrNull`).
     */
    @JvmStatic
    fun aggregate(fn: AggregateFn, arg: ValueType?): ValueType? = when (fn) {
        AggregateFn.COUNT -> ValueType.INT
        AggregateFn.SUM -> arg?.takeIf { it.isNumeric || it.kind == Kind.DURATION }?.asNullable()
        AggregateFn.AVG -> arg?.takeIf { it.isNumeric }?.let { ValueType(Kind.FLOAT, nullable = true) }
        AggregateFn.MIN, AggregateFn.MAX -> arg?.takeIf { it.isOrdered }?.asNullable()
    }

    /**
     * Whether two values can be compared with [op]: the same kind (or both numeric), and for
     * ordering comparisons an ordered kind.
     */
    @JvmStatic
    fun comparable(op: CompareOp, left: ValueType, right: ValueType): Boolean {
        val sameKind = left.kind == right.kind || (left.isNumeric && right.isNumeric)
        return sameKind && (!op.isOrdering || left.isOrdered)
    }
}
