package dev.vxcc.alexs168.pgecs.ir

import dev.vxcc.alexs168.pgecs.ir.ValueType.Kind

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

    /**
     * Parameter kinds and result type of a built-in. A null parameter kind takes any scalar (not
     * JSON, not a list); a null [result] is given by each call ([callTyped]).
     */
    data class Signature(val params: List<Kind?>, val result: ValueType?)

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
     * String concatenation, `a + b` / string templates on non-null strings. Not null-propagating:
     * Kotlin prints a null as `"null"`, so validation rejects arguments that may be null.
     */
    @JvmField val CONCAT = FunctionId("pgecs:concat")

    /**
     * `now()`: the time a query executes, bound at every execution, never cached in a plan. One
     * value per execution, so every `now()` in a query sees the same instant.
     */
    @JvmField val NOW = FunctionId("pgecs:now")

    /**
     * `value in list`: [list] is a list parameter of [value]'s kind (a captured `Collection`).
     * Never null: false when [value] is null, as `list.contains(null)` is for lists without nulls.
     */
    @JvmField val IN = FunctionId("pgecs:in")

    /**
     * `text ~ pattern`: a PostgreSQL regular expression (ARE) search, true when the pattern matches
     * anywhere in the text. Full matches (`Regex.matches`) anchor the pattern when lowering.
     */
    @JvmField val REGEX = FunctionId("pgecs:regex")

    /** `list.contains(x)` on a JSON array (a `List` field of a component value), for scalar [x]. */
    @JvmField val JSON_CONTAINS = FunctionId("pgecs:jsonContains")

    /** `list.size` of a JSON array; null when the value is not an array (a null list). */
    @JvmField val JSON_SIZE = FunctionId("pgecs:jsonSize")

    /**
     * `list[i]` of a JSON array, read as the call's type like a field. Out of range (where Kotlin
     * would throw) it is SQL null even when the type is not nullable, so the row does not match.
     */
    @JvmField val JSON_GET = FunctionId("pgecs:jsonGet")

    private val signatures: Map<FunctionId, Signature> = mapOf(
        LOWERCASE to Signature(listOf(Kind.TEXT), ValueType.TEXT),
        UPPERCASE to Signature(listOf(Kind.TEXT), ValueType.TEXT),
        TRIM to Signature(listOf(Kind.TEXT), ValueType.TEXT),
        LENGTH to Signature(listOf(Kind.TEXT), ValueType.INT),
        STARTS_WITH to Signature(listOf(Kind.TEXT, Kind.TEXT), ValueType.BOOL),
        ENDS_WITH to Signature(listOf(Kind.TEXT, Kind.TEXT), ValueType.BOOL),
        CONTAINS to Signature(listOf(Kind.TEXT, Kind.TEXT), ValueType.BOOL),
        CONCAT to Signature(listOf(Kind.TEXT, Kind.TEXT), ValueType.TEXT),
        NOW to Signature(emptyList(), ValueType.INSTANT),
        IN to Signature(listOf(null, null), ValueType.BOOL),
        REGEX to Signature(listOf(Kind.TEXT, Kind.TEXT), ValueType.BOOL),
        JSON_CONTAINS to Signature(listOf(Kind.JSON, null), ValueType.BOOL),
        JSON_SIZE to Signature(listOf(Kind.JSON), ValueType.INT.asNullable()),
        JSON_GET to Signature(listOf(Kind.JSON, Kind.INT), null),
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
        val result = requireNotNull(sig.result) { "$fn has no fixed result type; use callTyped" }
        return callTyped(fn, result, *args)
    }

    /** A call of built-in [fn] with result [type] (for [JSON_GET], whose type is the element's). */
    @JvmStatic
    fun callTyped(fn: FunctionId, type: ValueType, vararg args: Expr): Expr.Call {
        val sig = requireNotNull(signatures[fn]) { "$fn is not a built-in function" }
        require(args.size == sig.params.size) { "$fn takes ${sig.params.size} arguments, got ${args.size}" }
        require(fn != CONCAT || args.none { it.type.nullable }) { "$fn of a nullable string; Kotlin would print \"null\"" }
        if (fn == IN) {
            require(args[1].type.list && args[1].type.kind == args[0].type.kind) { "$fn needs a list of ${args[0].type.kind}, got ${args[1].type}" }
            return Expr.Call(fn, args.asList(), ValueType.BOOL)
        }
        val nullable = args.any { it.type.nullable } || type.nullable
        return Expr.Call(fn, args.asList(), if (nullable) type.asNullable() else type)
    }

    /** Whether [fn] is never null, whatever its arguments ([IN]). */
    @JvmStatic
    fun neverNull(fn: FunctionId): Boolean = fn == IN
}
