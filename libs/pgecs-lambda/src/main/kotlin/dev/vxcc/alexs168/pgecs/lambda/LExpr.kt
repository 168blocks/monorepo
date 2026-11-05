package dev.vxcc.alexs168.pgecs.lambda

/**
 * Expression tree of a decompiled lambda body.
 *
 * The tree is JVM-level: it names methods and fields by owner, name and descriptor and knows nothing
 * about components or SQL. pgecs-core lowers it into the query IR, deciding there which calls are
 * component getters, whitelisted functions or pgecs helpers.
 *
 * Every node carries [type], the JVM field descriptor of its value (`Z`, `J`, `Ljava/lang/String;`, ...).
 * Booleans are `Z` even where the JVM uses an `int` on the stack.
 */
sealed interface LExpr {
    val type: String

    /** Parameter [index] of the functional interface's method (captured values are not counted). */
    data class Arg(val index: Int, override val type: String) : LExpr

    /**
     * Captured value [index]. The tree is cached per implementing method, so it never holds the value
     * itself; [LambdaInstance.captured] has it.
     */
    data class Captured(val index: Int, override val type: String) : LExpr

    /**
     * A constant from the bytecode (`ldc`, `iconst`, ...) or the value of a `static final` field.
     * Booleans are `true`/`false` with type `Z`; `null` has type [NULL_TYPE].
     */
    data class Const(val value: Any?, override val type: String) : LExpr

    /** A method call. [receiver] is null for static calls. */
    data class Invoke(
        val kind: Kind,
        val owner: String,
        val name: String,
        val descriptor: String,
        val receiver: LExpr?,
        val args: List<LExpr>,
        override val type: String,
    ) : LExpr {
        enum class Kind { STATIC, VIRTUAL, INTERFACE, SPECIAL }

        /** `owner.name`, with the owner in binary (dotted) form. */
        val qualifiedName: String get() = owner.replace('/', '.') + "." + name
    }

    /** `new owner(args)`. */
    data class New(val owner: String, val descriptor: String, val args: List<LExpr>) : LExpr {
        override val type: String get() = "L$owner;"
    }

    /** Instance field read on something other than the lambda itself. */
    data class GetField(val receiver: LExpr, val owner: String, val name: String, override val type: String) : LExpr

    data class Binary(val op: BinOp, val left: LExpr, val right: LExpr, override val type: String) : LExpr

    data class Neg(val operand: LExpr, override val type: String) : LExpr

    /** Primitive conversion (`i2l`, ...) or a `checkcast` to [type]. */
    data class Convert(val operand: LExpr, override val type: String) : LExpr

    /**
     * Comparison. [CmpOp.EQ] and [CmpOp.NE] on references come from `if_acmp`, `ifnull` and
     * `Intrinsics.areEqual` alike: Kotlin `==` semantics, null-safe.
     */
    data class Compare(val op: CmpOp, val left: LExpr, val right: LExpr) : LExpr {
        override val type: String get() = "Z"
    }

    data class And(val left: LExpr, val right: LExpr) : LExpr {
        override val type: String get() = "Z"
    }

    data class Or(val left: LExpr, val right: LExpr) : LExpr {
        override val type: String get() = "Z"
    }

    data class Not(val operand: LExpr) : LExpr {
        override val type: String get() = "Z"
    }

    /** `if (condition) then else otherwise` for non-boolean results. */
    data class Cond(val condition: LExpr, val then: LExpr, val otherwise: LExpr, override val type: String) : LExpr

    /** String concatenation (`"a$b"`, `a + b`). */
    data class Concat(val parts: List<LExpr>) : LExpr {
        override val type: String get() = "Ljava/lang/String;"
    }

    /**
     * An array built in the lambda with constant size and constant indexes (the varargs of
     * `listOf("a", "b")`, `arrayOf(...)`); [type] is the array descriptor (`[Ljava/lang/String;`).
     */
    data class ArrayOf(val elements: List<LExpr>, override val type: String) : LExpr

    /**
     * A collection literal: `listOf(...)`, `setOf(...)`, `emptyList()`, `List.of(...)`,
     * `Arrays.asList(...)`. [type] is `Ljava/util/List;` or `Ljava/util/Set;`.
     */
    data class CollectionOf(val elements: List<LExpr>, override val type: String) : LExpr

    companion object {
        const val NULL_TYPE = "Ljava/lang/Object;"
        const val LIST_TYPE = "Ljava/util/List;"
        const val SET_TYPE = "Ljava/util/Set;"
        val TRUE = Const(true, "Z")
        val FALSE = Const(false, "Z")
    }
}

enum class BinOp(val symbol: String) {
    ADD("+"), SUB("-"), MUL("*"), DIV("/"), REM("%"),
    AND("&"), OR("|"), XOR("^"), SHL("<<"), SHR(">>"), USHR(">>>"),
}

enum class CmpOp(val symbol: String) {
    EQ("=="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">=");

    /** The operator for `!(a op b)`, valid for totally ordered, non-null operands. */
    fun negate(): CmpOp = when (this) {
        EQ -> NE; NE -> EQ; LT -> GE; GE -> LT; GT -> LE; LE -> GT
    }

    /** The operator for `b op' a` given `a op b`. */
    fun flip(): CmpOp = when (this) {
        EQ -> EQ; NE -> NE; LT -> GT; GT -> LT; LE -> GE; GE -> LE
    }
}

/** Readable rendering, used by the golden tests and in error messages. */
fun LExpr.render(): String = when (this) {
    is LExpr.Arg -> "arg$index"
    is LExpr.Captured -> "cap$index"
    is LExpr.Const -> when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Long -> "${value}L"
        is Float -> "${value}f"
        else -> if (type.startsWith("L") && value !is Number && value !is Boolean && value !is Char) {
            "const(${type.removePrefix("L").removeSuffix(";").substringAfterLast('/')})"
        } else value.toString()
    }
    is LExpr.Invoke -> {
        val simpleOwner = owner.substringAfterLast('/')
        val a = args.joinToString(", ") { it.render() }
        if (receiver == null) "$simpleOwner.$name($a)" else "${receiver.render()}.$name($a)"
    }
    is LExpr.New -> "new ${owner.substringAfterLast('/')}(${args.joinToString(", ") { it.render() }})"
    is LExpr.GetField -> "${receiver.render()}.$name"
    is LExpr.Binary -> "(${left.render()} ${op.symbol} ${right.render()})"
    is LExpr.Neg -> "-${operand.render()}"
    is LExpr.Convert -> "(${operand.render()} as $type)"
    is LExpr.Compare -> "(${left.render()} ${op.symbol} ${right.render()})"
    is LExpr.And -> "(${left.render()} && ${right.render()})"
    is LExpr.Or -> "(${left.render()} || ${right.render()})"
    is LExpr.Not -> "!${operand.render()}"
    is LExpr.Cond -> "(if ${condition.render()} then ${then.render()} else ${otherwise.render()})"
    is LExpr.Concat -> "concat(${parts.joinToString(", ") { it.render() }})"
    is LExpr.ArrayOf -> "arrayOf(${elements.joinToString(", ") { it.render() }})"
    is LExpr.CollectionOf -> (if (type == LExpr.SET_TYPE) "setOf" else "listOf") + "(${elements.joinToString(", ") { it.render() }})"
}
