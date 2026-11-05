package net.blocks168.pgecs.ir

/**
 * Logical type of an IR value: a variable, a JSON field of a component value, a parameter or the
 * result of an expression.
 *
 * The kind also fixes how a value is stored inside a component's JSON value, which is what the SQL
 * compiler casts to (`(value->>'f')::bigint`, ...). It is decided once, from the component's
 * `SerialDescriptor` (the standard pgecs serializers are recognised by `serialName`), by whoever
 * lowers into the IR.
 *
 * | Kind | Kotlin / Java | Stored in JSON as |
 * | --- | --- | --- |
 * | [Kind.BOOL] | `Boolean` | `true` / `false` |
 * | [Kind.INT] | `Long`, `Int`, `Short`, `Byte` | number (64-bit integer) |
 * | [Kind.FLOAT] | `Double`, `Float` | number |
 * | [Kind.TEXT] | `String`, `Char`, enums | string |
 * | [Kind.UUID] | entity ids, `UUID` / `Uuid` (`UuidAsString`) | string |
 * | [Kind.INSTANT] | `Instant` (`InstantAsEpochMillis`) | number, epoch milliseconds |
 * | [Kind.DURATION] | `Duration` (`DurationAsMillis`) | number, milliseconds |
 * | [Kind.JSON] | objects, lists, maps, anything else | any JSON |
 *
 * [nullable] is the declared nullability (a `String?` field, an optional parameter). A non-null
 * field of a variable bound only inside an [Term.Optional] can still evaluate to null outside of it;
 * see [Nullability].
 *
 * [list] types (`text[]`) are lists of non-null values of [kind]. Only parameters have them, the
 * right side of [Functions.IN] (`it.name in names`); lists stored in component values are [Kind.JSON].
 */
data class ValueType(val kind: Kind, val nullable: Boolean = false, val list: Boolean = false) {

    enum class Kind { BOOL, INT, FLOAT, TEXT, UUID, INSTANT, DURATION, JSON }

    val isNumeric: Boolean get() = kind == Kind.INT || kind == Kind.FLOAT

    /** Whether values of the kind have a total order that SQL and Kotlin agree on (`<`, `orderBy`). */
    val isOrdered: Boolean get() = !list && kind != Kind.BOOL && kind != Kind.JSON

    fun asNullable(): ValueType = if (nullable) this else copy(nullable = true)

    fun asNonNull(): ValueType = if (nullable) copy(nullable = false) else this

    /** The type of one element of this list type. */
    fun element(): ValueType = ValueType(kind)

    override fun toString(): String = kind.name.lowercase() + (if (list) "[]" else "") + if (nullable) "?" else ""

    companion object {
        @JvmField val BOOL = ValueType(Kind.BOOL)
        @JvmField val INT = ValueType(Kind.INT)
        @JvmField val FLOAT = ValueType(Kind.FLOAT)
        @JvmField val TEXT = ValueType(Kind.TEXT)
        @JvmField val UUID = ValueType(Kind.UUID)
        @JvmField val INSTANT = ValueType(Kind.INSTANT)
        @JvmField val DURATION = ValueType(Kind.DURATION)
        @JvmField val JSON = ValueType(Kind.JSON)

        /** A list of non-null [kind] values. */
        @JvmStatic
        fun listOf(kind: Kind): ValueType = ValueType(kind, list = true)
    }
}
