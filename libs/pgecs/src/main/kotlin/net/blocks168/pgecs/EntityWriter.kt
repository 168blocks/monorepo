package net.blocks168.pgecs

/**
 * The receiver of `create { }` and `update(e) { }`: writes to one entity inside a transaction.
 * The transaction layer implements it; pgecs-ksp generates per-component accessors on top
 * (`email = Email("aea")`, `childOf = acc`).
 *
 * ```kotlin
 * create { +Account; +SmpMainAccess }
 * create { email = Email("aea"); childOf = acc }      // generated accessors
 * create { this[EmailComponent] = Email("aea"); set(ChildOf, acc) }   // without the processor
 * update(acc) { -SmpMainAccess; +OwnedBy(team) }
 * ```
 */
interface EntityWriter {
    /** The entity being written. */
    val entity: EntityId

    /** Sets [component]'s value; null removes it. */
    operator fun <T> set(component: Component<T>, value: T?)

    /** The current value of [component], or null. */
    operator fun <T> get(component: Component<T>): T?

    fun add(tag: Tag)

    fun remove(tag: Tag)

    /** Adds the pair `(relation, target)`; for an exclusive relation it replaces the old pair. */
    fun add(relation: Relation, target: EntityId)

    fun remove(relation: Relation, target: EntityId)

    /** Sets the target of exclusive [relation], replacing the old one; null removes it. */
    fun set(relation: Relation, target: EntityId?) {
        require(relation.exclusive) { "relation ${relation.name} is not exclusive; use add/remove" }
        if (target == null) removeAll(relation) else add(relation, target)
    }

    /** Removes every pair of [relation]. */
    fun removeAll(relation: Relation)

    /** A pair to add or remove: `+OwnedBy(acc)`. */
    class PairRef internal constructor(val relation: Relation, val target: EntityId)

    operator fun Tag.unaryPlus() = add(this)

    operator fun Tag.unaryMinus() = remove(this)

    operator fun Relation.invoke(target: EntityId): PairRef = PairRef(this, target)

    operator fun PairRef.unaryPlus() = add(relation, target)

    operator fun PairRef.unaryMinus() = remove(relation, target)
}

/**
 * Overrides the name pgecs-ksp generates for a component or relation (`email` for
 * `EmailComponent`, `childOf` for `ChildOf`).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class PgecsName(val value: String)
