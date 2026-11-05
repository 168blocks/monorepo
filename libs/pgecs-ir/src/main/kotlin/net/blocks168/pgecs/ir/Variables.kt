package net.blocks168.pgecs.ir

/**
 * Something that names an entity in a [Term.Match], as subject or target: an [EntityVar], the
 * wildcard [AnyEntity], the entity of a registered type ([TypeEntity]), or a fixed entity id given
 * as an [Expr.Param] (or, before [parameterize], an [Expr.Literal]) of type [ValueType.UUID].
 */
sealed interface EntityRef

/**
 * A query variable: a node of the term graph. Variables are declared once per query in
 * [QueryIr.variables], numbered by declaration order ([index] is the position there), so plans,
 * column order and the cache key are deterministic.
 *
 * [name] is the name the DSL user gave (`val acc by entity`); it shows in plans and errors and is
 * part of the query's shape.
 */
sealed interface Variable {
    val index: Int
    val name: String
    val type: ValueType
}

/** An entity variable (`$acc`). Its value is the entity id. */
data class EntityVar(override val index: Int, override val name: String) : Variable, EntityRef {
    init {
        requireVariable(index, name)
    }

    override val type: ValueType get() = ValueType.UUID

    override fun toString(): String = "$$name"
}

/**
 * A value variable: the value of a component on an entity, bound by the [Term.Match] that names it
 * as [Term.Match.value]. [type] is the component's [ComponentRef.valueType].
 */
data class ValueVar(override val index: Int, override val name: String, override val type: ValueType) : Variable {
    init {
        requireVariable(index, name)
        require(!type.nullable) { "value variable $name cannot have a nullable type; optional values come from Term.Optional" }
    }

    override fun toString(): String = "$$name"
}

/**
 * Flecs' `_` / `*` in an entity position: some entity, existentially. A wildcard never multiplies
 * result rows: `ChildOf(mail, any)` holds once for a `mail` with any number of parents (a semi-join,
 * `exists` in SQL). Each occurrence is independent of the others.
 */
data object AnyEntity : EntityRef {
    override fun toString(): String = "*"
}

/**
 * The entity of a registered component, relation or capability type (UUIDv5 of its name), for
 * example the capability in `(pgecs:can, b8:iam:smtp)`. Part of the query's shape, like component
 * names.
 */
data class TypeEntity(val name: String) : EntityRef {
    init {
        require(name.isNotBlank()) { "type entity name is blank" }
    }

    override fun toString(): String = "#$name"
}

private val VARIABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

private fun requireVariable(index: Int, name: String) {
    require(index >= 0) { "variable index $index < 0" }
    require(VARIABLE_NAME.matches(name)) { "invalid variable name '$name'" }
}
