package net.blocks168.pgecs.ir

import net.blocks168.pgecs.ir.ValueType.Kind

/**
 * Name of a function in [Expr.Call]. Built-in functions live in the `pgecs` namespace and are listed
 * in [Functions]; a source's own functions use the source's namespace (`mc:near`).
 */
data class FunctionId(val name: String) {
    init {
        require(NAME.matches(name)) { "invalid function id '$name', expected <namespace>:<name>" }
    }

    override fun toString(): String = name

    private companion object {
        val NAME = Regex("[a-z][a-z0-9-]*:[a-z][A-Za-z0-9-]*")
    }
}

/**
 * Built-in functions: the closed set every source that runs value predicates must understand, with
 * Kotlin semantics. Each maps from a whitelisted JVM method that the lambda decompiler recognises.
 */
object Functions {

    /** Parameter kinds and result type of a built-in. */
    data class Signature(val params: List<Kind>, val result: ValueType)

    /** `String.lowercase()` (`Locale.ROOT`, as Kotlin). */
    @JvmField val LOWERCASE = FunctionId("pgecs:lowercase")

    /** `String.uppercase()` (`Locale.ROOT`). */
    @JvmField val UPPERCASE = FunctionId("pgecs:uppercase")

    /** `String.trim()`: Kotlin trims `Char.isWhitespace`, a superset of SQL `btrim`'s default. */
    @JvmField val TRIM = FunctionId("pgecs:trim")

    /** `String.length`, in UTF-16 code units as on the JVM. */
    @JvmField val LENGTH = FunctionId("pgecs:length")

    /** `String.startsWith(prefix)`: a literal prefix, no pattern characters. */
    @JvmField val STARTS_WITH = FunctionId("pgecs:startsWith")

    /** `String.endsWith(suffix)`. */
    @JvmField val ENDS_WITH = FunctionId("pgecs:endsWith")

    /** `String.contains(other)`. */
    @JvmField val CONTAINS = FunctionId("pgecs:contains")

    /**
     * `now()`: the time a query executes, bound at every execution, never cached in a plan. One
     * value per execution, so every `now()` in a query sees the same instant.
     */
    @JvmField val NOW = FunctionId("pgecs:now")

    private val signatures: Map<FunctionId, Signature> = mapOf(
        LOWERCASE to Signature(listOf(Kind.TEXT), ValueType.TEXT),
        UPPERCASE to Signature(listOf(Kind.TEXT), ValueType.TEXT),
        TRIM to Signature(listOf(Kind.TEXT), ValueType.TEXT),
        LENGTH to Signature(listOf(Kind.TEXT), ValueType.INT),
        STARTS_WITH to Signature(listOf(Kind.TEXT, Kind.TEXT), ValueType.BOOL),
        ENDS_WITH to Signature(listOf(Kind.TEXT, Kind.TEXT), ValueType.BOOL),
        CONTAINS to Signature(listOf(Kind.TEXT, Kind.TEXT), ValueType.BOOL),
        NOW to Signature(emptyList(), ValueType.INSTANT),
    )

    /** All built-ins. */
    @JvmStatic
    val builtins: Set<FunctionId> get() = signatures.keys

    @JvmStatic
    fun isBuiltin(fn: FunctionId): Boolean = fn in signatures

    @JvmStatic
    fun signature(fn: FunctionId): Signature? = signatures[fn]

    /**
     * A call of built-in [fn] with its result type; nullable if any argument is (string functions
     * propagate null, like `?.`).
     */
    @JvmStatic
    fun call(fn: FunctionId, vararg args: Expr): Expr.Call {
        val sig = requireNotNull(signatures[fn]) { "$fn is not a built-in function" }
        require(args.size == sig.params.size) { "$fn takes ${sig.params.size} arguments, got ${args.size}" }
        val nullable = args.any { it.type.nullable }
        return Expr.Call(fn, args.asList(), if (nullable) sig.result.asNullable() else sig.result)
    }
}
