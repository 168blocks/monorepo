package dev.vxcc.alexs168.pgecs.lambda

/**
 * Smart constructors that keep decompiled trees small and in a canonical shape: Kotlin intrinsics and
 * boxing are removed, `compareTo(..) < 0` becomes `<`, booleans compared with 0/1 become themselves
 * or their negation, and constants fold.
 */
internal object Simplify {
    private val BOXES = mapOf(
        "java/lang/Integer" to "I", "java/lang/Long" to "J", "java/lang/Short" to "S", "java/lang/Byte" to "B",
        "java/lang/Double" to "D", "java/lang/Float" to "F", "java/lang/Boolean" to "Z", "java/lang/Character" to "C",
    )
    private val UNBOX = setOf(
        "intValue", "longValue", "shortValue", "byteValue", "doubleValue", "floatValue", "booleanValue", "charValue",
    )

    fun not(e: LExpr): LExpr = when (e) {
        LExpr.TRUE -> LExpr.FALSE
        LExpr.FALSE -> LExpr.TRUE
        is LExpr.Not -> e.operand
        is LExpr.Compare -> LExpr.Compare(e.op.negate(), e.left, e.right)
        else -> LExpr.Not(e)
    }

    fun and(a: LExpr, b: LExpr): LExpr = when {
        a == LExpr.FALSE || b == LExpr.FALSE -> LExpr.FALSE
        a == LExpr.TRUE -> b
        b == LExpr.TRUE -> a
        else -> LExpr.And(a, b)
    }

    fun or(a: LExpr, b: LExpr): LExpr = when {
        a == LExpr.TRUE || b == LExpr.TRUE -> LExpr.TRUE
        a == LExpr.FALSE -> b
        b == LExpr.FALSE -> a
        else -> LExpr.Or(a, b)
    }

    /** `if (c) a else b` for booleans. */
    fun choose(c: LExpr, a: LExpr, b: LExpr): LExpr = when {
        // Shared continuations (`(x && y) || z` jumps to the code of z from two places).
        b is LExpr.Or && b.right == a -> or(and(not(c), b.left), a)
        b is LExpr.Or && b.left == a -> or(a, and(not(c), b.right))
        a is LExpr.Or && a.right == b -> or(and(c, a.left), b)
        a is LExpr.Or && a.left == b -> or(b, and(c, a.right))
        b is LExpr.And && b.right == a -> and(or(c, b.left), a)
        a is LExpr.And && a.right == b -> and(or(not(c), a.left), b)
        a == LExpr.TRUE && b == LExpr.FALSE -> c
        a == LExpr.FALSE && b == LExpr.TRUE -> not(c)
        a == LExpr.TRUE -> or(c, b)
        a == LExpr.FALSE -> and(not(c), b)
        b == LExpr.TRUE -> or(not(c), a)
        b == LExpr.FALSE -> and(c, a)
        else -> or(and(c, a), and(not(c), b))
    }

    private fun isZero(e: LExpr) = e is LExpr.Const && e.type == "I" && e.value == 0
    private fun isOne(e: LExpr) = e is LExpr.Const && e.type == "I" && e.value == 1

    fun compare(op: CmpOp, l: LExpr, r: LExpr): LExpr {
        if (l is LExpr.Const && r !is LExpr.Const) return compare(op.flip(), r, l)
        // a.compareTo(b) op 0  ->  a op b ; Intrinsics.compare(a, b) op 0 -> a op b
        if (isZero(r) && l is LExpr.Invoke && l.type == "I") {
            if (l.name == "compareTo" && l.receiver != null && l.args.size == 1) return LExpr.Compare(op, l.receiver, l.args[0])
            if (l.owner == SymbolicExecutor.KOTLIN_INTRINSICS && l.name == "compare" && l.args.size == 2) {
                return LExpr.Compare(op, l.args[0], l.args[1])
            }
        }
        if (l.type == "Z" && (isZero(r) || isOne(r))) {
            when {
                op == CmpOp.NE && isZero(r) || op == CmpOp.EQ && isOne(r) -> return l
                op == CmpOp.EQ && isZero(r) || op == CmpOp.NE && isOne(r) -> return not(l)
            }
        }
        if (l.type == "Z" && r is LExpr.Const && r.type == "Z" && (op == CmpOp.EQ || op == CmpOp.NE)) {
            val positive = (r.value == true) == (op == CmpOp.EQ)
            return if (positive) l else not(l)
        }
        if (l is LExpr.Const && r is LExpr.Const) {
            val a = l.value; val b = r.value
            if (op == CmpOp.EQ) return if (a == b) LExpr.TRUE else LExpr.FALSE
            if (op == CmpOp.NE) return if (a != b) LExpr.TRUE else LExpr.FALSE
            if (a is Int && b is Int) {
                val c = a.compareTo(b)
                val res = when (op) { CmpOp.LT -> c < 0; CmpOp.LE -> c <= 0; CmpOp.GT -> c > 0; else -> c >= 0 }
                return if (res) LExpr.TRUE else LExpr.FALSE
            }
        }
        return LExpr.Compare(op, l, r)
    }

    /** Factories of collection literals: owner/name to the literal's type (list or set). */
    private val FACTORIES = mapOf(
        "kotlin/collections/CollectionsKt" to mapOf(
            "listOf" to LExpr.LIST_TYPE, "mutableListOf" to LExpr.LIST_TYPE, "arrayListOf" to LExpr.LIST_TYPE,
            "emptyList" to LExpr.LIST_TYPE,
        ),
        "kotlin/collections/SetsKt" to mapOf(
            "setOf" to LExpr.SET_TYPE, "mutableSetOf" to LExpr.SET_TYPE, "hashSetOf" to LExpr.SET_TYPE,
            "linkedSetOf" to LExpr.SET_TYPE, "emptySet" to LExpr.SET_TYPE,
        ),
        "java/util/List" to mapOf("of" to LExpr.LIST_TYPE),
        "java/util/Set" to mapOf("of" to LExpr.SET_TYPE),
        "java/util/Arrays" to mapOf("asList" to LExpr.LIST_TYPE),
    )

    /**
     * `listOf("a", "b")` and friends as a [LExpr.CollectionOf]: one array argument (varargs), or
     * the elements themselves (`listOf(x)`, `List.of(a, b)`), or none.
     */
    private fun collection(c: LExpr.Invoke): LExpr? {
        if (c.receiver != null) return null
        val type = FACTORIES[c.owner]?.get(c.name) ?: return null
        val elements = when {
            c.args.size == 1 && c.args[0] is LExpr.ArrayOf -> (c.args[0] as LExpr.ArrayOf).elements
            c.args.none { it.type.startsWith("[") } -> c.args
            else -> return null
        }
        return LExpr.CollectionOf(elements, type)
    }

    /** Rewrites calls that are only Kotlin/JVM plumbing. */
    fun call(c: LExpr.Invoke): LExpr {
        val n = c.args.size
        collection(c)?.let { return it }
        when {
            (c.owner == SymbolicExecutor.KOTLIN_INTRINSICS && c.name == "areEqual" && n == 2) ||
                (c.owner == "java/util/Objects" && c.name == "equals" && n == 2) ->
                return compare(CmpOp.EQ, c.args[0], c.args[1])
            c.receiver != null && c.name == "equals" && c.descriptor == "(Ljava/lang/Object;)Z" ->
                return compare(CmpOp.EQ, c.receiver, c.args[0])
            c.owner == SymbolicExecutor.KOTLIN_INTRINSICS && c.name == "stringPlus" && n == 2 ->
                return LExpr.Concat(c.args)
            c.receiver == null && c.name == "valueOf" && n == 1 && BOXES[c.owner] == c.args[0].type ->
                return c.args[0]
            c.receiver != null && c.name in UNBOX && n == 0 && (c.owner in BOXES || c.owner == "java/lang/Number") -> {
                val r = c.receiver
                return if (r.type == c.type) r else LExpr.Convert(r, c.type)
            }
        }
        return c
    }
}
