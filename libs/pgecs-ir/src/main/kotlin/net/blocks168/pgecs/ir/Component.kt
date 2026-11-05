package net.blocks168.pgecs.ir

/**
 * A source of components: Postgres, the in-process in-memory ECS, the live Minecraft server, ...
 * Every component type is owned by exactly one source (pgecs design, *Federated planner*).
 */
data class SourceId(val name: String) {
    init {
        require(NAME.matches(name)) { "invalid source id: '$name'" }
    }

    override fun toString(): String = name

    companion object {
        private val NAME = Regex("[a-z][a-z0-9-]*")

        @JvmField val POSTGRES = SourceId("postgres")
    }
}

/** What a component type is, as in the catalog (`ecs_component_type.kind`). */
enum class ComponentKind {
    /** No value, no target: `acc has Account`. */
    TAG,

    /** A JSON value, no target: `mail has EmailComponent`. */
    VALUE,

    /** A pair `(relation, target)`, with or without a value: `mail childOf acc`. */
    RELATION,

    /**
     * Not matchable as a component. A capability is an entity used as the target of
     * `(pgecs:can, <capability>)`; refer to it with [TypeEntity].
     */
    CAPABILITY,
}

/** What can stand in the relationship position of a [Term.Match]: a component, or the wildcard. */
sealed interface ComponentSlot {
    val source: SourceId
}

/**
 * A registered component, tag, relation or capability type as the IR sees it: a plain descriptor,
 * without the Kotlin handle (`Component<T>`, `Tag`, `Relation`) or its serializer.
 *
 * Everything here is part of a query's shape (and so of the plan-cache key): component names are
 * inlined into SQL as literals, so that Postgres can use partial indexes (`where rel = '...'`).
 *
 * @property valueType the type of the component's value: required for [ComponentKind.VALUE], absent
 *   for [ComponentKind.TAG], optional for [ComponentKind.RELATION] (pairs that carry data). Never
 *   nullable: a value component that is present has a value.
 * @property source the source that owns the component.
 */
data class ComponentRef(
    val name: String,
    val kind: ComponentKind,
    val valueType: ValueType? = null,
    override val source: SourceId = SourceId.POSTGRES,
) : ComponentSlot {
    init {
        require(NAME.matches(name)) {
            "invalid component name '$name': expected non-empty colon-separated segments without whitespace or quotes"
        }
        when (kind) {
            ComponentKind.TAG -> require(valueType == null) { "tag $name cannot have a value type" }
            ComponentKind.VALUE -> require(valueType != null) { "value component $name needs a value type" }
            ComponentKind.RELATION, ComponentKind.CAPABILITY -> {}
        }
        require(valueType == null || !valueType.nullable) { "value type of $name cannot be nullable" }
    }

    val isPair: Boolean get() = kind == ComponentKind.RELATION

    override fun toString(): String = name

    companion object {
        /** Same pattern as the `ecs_component_type.name` check in Postgres. */
        private val NAME = Regex("""[^:\s'"\\]+(:[^:\s'"\\]+)+""")
    }
}

/**
 * Wildcard relationship, flecs' `(*, Tgt)`: any relation of [source] whose target is the
 * [Term.Match.target]. It cannot bind a value, since the relations' value types differ.
 */
data class AnyRelation(override val source: SourceId = SourceId.POSTGRES) : ComponentSlot {
    override fun toString(): String = "*"
}
