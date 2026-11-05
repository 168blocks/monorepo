package dev.vxcc.alexs168.pgecs.dsl

import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import dev.vxcc.alexs168.pgecs.Component
import dev.vxcc.alexs168.pgecs.ComponentType
import dev.vxcc.alexs168.pgecs.EntityId
import dev.vxcc.alexs168.pgecs.Relation
import dev.vxcc.alexs168.pgecs.lambda.LambdaInstance
import dev.vxcc.alexs168.pgecs.lambda.QueryPredicate

/**
 * The Kotlin query DSL, the receiver of `ecs.query { }`. It reads like flecs' term list:
 *
 * ```kotlin
 * ecs.query {
 *     val acc by entity
 *     val mail by entity
 *     acc has Account
 *     acc has !LobbyDenied
 *     mail childOf acc
 *     mail has EmailComponent.where { it.address.lowercase() == addr.lowercase() }
 *     select { acc to mail[EmailComponent].address }
 * }
 * ```
 *
 * The infix functions and operators are members, so they only exist inside `query { }`.
 */
class QueryScope internal constructor() : QueryBuilder() {

    /** `val acc by entity` declares an entity variable named `acc`. */
    val entity: EntityDeclaration = EntityDeclaration()

    /** The wildcard, flecs' `*`: `ChildOf(mail, any)`. */
    val any: EntityId get() = any()

    inner class EntityDeclaration internal constructor() {
        operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ReadOnlyProperty<Any?, EntityId> {
            return VariableDelegate(entity(property.name))
        }
    }

    /** `!LobbyDenied`, for `acc has !LobbyDenied`. */
    class Negated internal constructor(val type: ComponentType)

    /** `A or B`, for `acc has (A or B)`. */
    class AnyOf internal constructor(val types: List<ComponentType>) {
        infix fun or(other: ComponentType) = AnyOf(types + other)
    }

    /** `EmailComponent.where { ... }`, for `mail has EmailComponent.where { ... }`. */
    class Filtered<T> internal constructor(val component: Component<T>, internal val predicate: LambdaInstance)

    operator fun ComponentType.not(): Negated = Negated(this)

    infix fun ComponentType.or(other: ComponentType): AnyOf = AnyOf(listOf(this, other))

    fun <T> Component<T>.where(predicate: QueryPredicate<T>): Filtered<T> = Filtered(this, decompile(predicate))

    @JvmName("hasInfix")
    infix fun EntityId.has(type: ComponentType) = has(this, type)

    @JvmName("hasInfix")
    infix fun EntityId.has(negated: Negated) = hasNot(this, negated.type)

    @JvmName("hasInfix")
    infix fun EntityId.has(anyOf: AnyOf) {
        terms += DTerm.AnyOf(this, anyOf.types, negated = false)
    }

    @JvmName("hasInfix")
    infix fun <T> EntityId.has(filtered: Filtered<T>) {
        terms += DTerm.Match(this, filtered.component, null, listOf(filtered.predicate))
    }

    /** Optional component: `acc maybe MassComponent`, its value is null where absent. */
    @JvmName("maybeInfix")
    infix fun EntityId.maybe(component: Component<*>) = maybe(this, component)

    /** Optional component with a condition on it. */
    @JvmName("maybeInfix")
    infix fun <T> EntityId.maybe(filtered: Filtered<T>) {
        terms += DTerm.Group(
            DTerm.GroupKind.OPTIONAL,
            listOf(DTerm.Match(this, filtered.component, null, listOf(filtered.predicate))),
        )
    }

    /** `ChildOf(mail, acc)`: the pair `(relation, target)` on [subject]. */
    operator fun Relation.invoke(subject: EntityId, target: EntityId) = pair(this, subject, target)

    /** The value of [component] on this entity, inside lambdas: `mail[EmailComponent].address`. */
    @JvmName("getInfix")
    operator fun <T> EntityId.get(component: Component<T>): T = get(this, component)

    /** None of these terms match. */
    @JvmName("notBlock")
    fun not(block: QueryScope.() -> Unit) {
        terms += DTerm.Group(DTerm.GroupKind.NOT, group { block() })
    }

    /** These terms match if they can; their values are null where they do not. */
    @JvmName("optionalBlock")
    fun optional(block: QueryScope.() -> Unit) {
        terms += DTerm.Group(DTerm.GroupKind.OPTIONAL, group { block() })
    }
}

/**
 * The delegate behind `val acc by entity`. Lambdas that read `acc` capture this delegate and call a
 * synthetic accessor, which the lowering resolves to the variable.
 */
class VariableDelegate internal constructor(val variable: EntityId) : ReadOnlyProperty<Any?, EntityId> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): EntityId = variable
}

/** Builds a query against [executor]; what `ecs.query { }` calls. */
@JvmSynthetic
fun <R> query(executor: QueryExecutor?, block: QueryScope.() -> Selection<R>): Query<R> =
    QueryBuilder.record(QueryScope(), executor) { it.block() }

/** Builds a Java-DSL query against [executor]. */
fun <R> query(executor: QueryExecutor?, body: QueryBody<R>): Query<R> =
    QueryBuilder.record(QueryBuilder(), executor) { body.build(it) }

/**
 * Something queries run against: the `Ecs` facade implements it with the planner's executor.
 * Kotlin callers get `query { }`, Java callers `query(b -> ...)`.
 */
interface Queryable {
    val queryExecutor: QueryExecutor

    @JvmSynthetic
    fun <R> query(block: QueryScope.() -> Selection<R>): Query<R> = query(queryExecutor, block)

    fun <R> query(body: QueryBody<R>): Query<R> = query(queryExecutor, body)
}
