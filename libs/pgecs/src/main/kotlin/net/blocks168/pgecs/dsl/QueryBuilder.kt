package net.blocks168.pgecs.dsl

import java.time.Instant
import java.util.UUID
import java.util.function.Consumer
import net.blocks168.pgecs.Component
import net.blocks168.pgecs.ComponentType
import net.blocks168.pgecs.EntityId
import net.blocks168.pgecs.Relation
import net.blocks168.pgecs.lambda.LambdaDecompiler
import net.blocks168.pgecs.lambda.LambdaInstance
import net.blocks168.pgecs.lambda.QueryPredicate
import net.blocks168.pgecs.lambda.QuerySupplier
import net.blocks168.pgecs.lambda.UntranslatableLambdaException

/** A query that cannot be built: unknown fields, untranslatable lambdas, misuse of the builder. */
class QueryBuildException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A query variable: an entity placeholder named after its declaration. Never a real entity.
 */
class QueryVariable internal constructor(
    val name: String,
    internal val owner: QueryBuilder,
    internal val wildcard: Boolean = false,
) : EntityId(UUID.randomUUID()) {
    override val isVariable: Boolean get() = true

    override val id: UUID
        get() = throw IllegalStateException("query variable $$name has no id outside a query lambda")

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = if (wildcard) "*" else "$$name"
}

/** A component value of an entity, to select from Java: `b.select(acc, b.value(mail, EMAIL))`. */
class ValueRef<T> internal constructor(val entity: EntityId, val component: Component<T>)

/**
 * The body of a Java query: `ecs.query(b -> { ...; return b.select(acc); })`.
 *
 * Deliberately not a `fun interface`: Java lambdas implement it, but Kotlin lambdas cannot, so
 * `ecs.query { }` in Kotlin always picks the [QueryScope] overload.
 */
interface QueryBody<R> {
    fun build(b: QueryBuilder): Selection<R>
}

/**
 * Records a query. This is the Java DSL; the Kotlin DSL ([QueryScope]) adds infix sugar on top.
 *
 * The builder only records terms: lambdas (`where`, `select`, `orderBy`, `filter`) are decompiled
 * from bytecode and lowered into the IR when the query is built, so mistakes (an unknown field, an
 * untranslatable call, a predicate on a tag) throw [QueryBuildException] then, never at execution.
 */
open class QueryBuilder internal constructor() {
    internal val variables = mutableListOf<QueryVariable>()
    internal var terms = mutableListOf<DTerm>()
    internal var selection: DSelect? = null
    internal val orderBy = mutableListOf<Pair<LambdaInstance, Boolean>>()
    internal var limit: Long? = null
    internal var offset: Long? = null
    internal var distinct = false

    /** A new entity variable named [name] (shown in plans and errors). */
    fun entity(name: String): EntityId = QueryVariable(name, this).also { variables += it }

    /** The wildcard: some entity, without adding rows (flecs' `*`). Each use is independent. */
    fun any(): EntityId = QueryVariable("any", this, wildcard = true)

    // ---- terms ----

    /** [entity] has [type] (a tag or a component; for a relation use [pair]). */
    fun has(entity: EntityId, type: ComponentType) {
        if (type is Relation) throw QueryBuildException("relation ${type.name} needs a target; use pair(rel, subject, target)")
        terms += DTerm.Match(entity, type, null, emptyList())
    }

    /** [entity] has [component] with a value for which [predicate] holds. */
    fun <T> has(entity: EntityId, component: Component<T>, predicate: QueryPredicate<T>) {
        terms += DTerm.Match(entity, component, null, listOf(decompile(predicate)))
    }

    /** [entity] does not have [type]. */
    fun hasNot(entity: EntityId, type: ComponentType) {
        terms += DTerm.Group(DTerm.GroupKind.NOT, listOf(DTerm.Match(entity, type, null, emptyList())))
    }

    /** [entity] has at least one of [types]. */
    fun hasAny(entity: EntityId, vararg types: ComponentType) {
        require(types.size >= 2) { "hasAny needs at least two types" }
        terms += DTerm.AnyOf(entity, types.toList(), negated = false)
    }

    /** Optional: the component's value is null in results where [entity] does not have it. */
    fun maybe(entity: EntityId, component: Component<*>) {
        terms += DTerm.Group(DTerm.GroupKind.OPTIONAL, listOf(DTerm.Match(entity, component, null, emptyList())))
    }

    /** The pair `(relation, target)` on [subject]; [target] may be [any]. */
    fun pair(relation: Relation, subject: EntityId, target: EntityId) {
        terms += DTerm.Match(subject, relation, target, emptyList())
    }

    /** Same as [pair]; what KSP-generated infix functions (`mail childOf acc`) call. */
    fun relate(relation: Relation, subject: EntityId, target: EntityId) = pair(relation, subject, target)

    /** None of the terms [block] adds match (`not exists`). */
    fun not(block: Consumer<QueryBuilder>) {
        terms += DTerm.Group(DTerm.GroupKind.NOT, group { block.accept(this) })
    }

    /** The terms [block] adds are matched if they can be (a left join); their values are null otherwise. */
    fun optional(block: Consumer<QueryBuilder>) {
        terms += DTerm.Group(DTerm.GroupKind.OPTIONAL, group { block.accept(this) })
    }

    /** A condition over variables and values, as in `filter { acc[Score].points > 10 }`. */
    fun filter(condition: QuerySupplier<Boolean>) {
        terms += DTerm.Filter(decompile(condition))
    }

    internal fun group(block: () -> Unit): List<DTerm> {
        val outer = terms
        terms = mutableListOf()
        try {
            block()
            if (terms.isEmpty()) throw QueryBuildException("empty group")
            return terms
        } finally {
            terms = outer
        }
    }

    // ---- markers, only meaningful inside lambdas ----

    /** The value of [component] on [entity]: a marker for lambdas, it never executes. */
    open fun <T> get(entity: EntityId, component: Component<T>): T =
        throw IllegalStateException("get(entity, component) only works inside query lambdas")

    /** The time the query executes, bound per execution: a marker for lambdas. */
    fun now(): Instant = throw IllegalStateException("now() only works inside query lambdas")

    /** A component value to select: `b.select(acc, b.value(mail, EMAIL))`. */
    fun <T> value(entity: EntityId, component: Component<T>): ValueRef<T> = ValueRef(entity, component)

    // ---- result ----

    fun select(entity: EntityId): Selection<EntityId> = selectColumns(listOf(entity), single = true)

    fun <T> select(value: ValueRef<T>): Selection<T> = selectColumns(listOf(value), single = true)

    /** Several columns, as a `List<Object>` per row. Each is an [EntityId] or a [ValueRef]. */
    fun select(first: Any, second: Any, vararg rest: Any): Selection<List<Any?>> =
        selectColumns(listOf(first, second) + rest, single = false)

    /** A computed row: `select { acc to mail[EmailComponent].address }`. */
    fun <R> select(projection: QuerySupplier<R>): Selection<R> {
        setSelection(DSelect.Lambda(decompile(projection)))
        return Selection(this)
    }

    private fun <R> selectColumns(columns: List<Any>, single: Boolean): Selection<R> {
        columns.forEach {
            if (it !is EntityId && it !is ValueRef<*>) throw QueryBuildException("cannot select $it: use an entity or value(entity, component)")
        }
        setSelection(DSelect.Columns(columns, single))
        return Selection(this)
    }

    private fun setSelection(s: DSelect) {
        if (selection != null) throw QueryBuildException("select was called twice")
        selection = s
    }

    fun orderBy(key: QuerySupplier<*>) = apply { orderBy += decompile(key) to false }

    fun orderByDescending(key: QuerySupplier<*>) = apply { orderBy += decompile(key) to true }

    fun limit(n: Long) = apply {
        require(n >= 0) { "limit $n < 0" }
        limit = n
    }

    fun offset(n: Long) = apply {
        require(n >= 0) { "offset $n < 0" }
        offset = n
    }

    /** Return each distinct row once. */
    fun distinct() = apply { distinct = true }

    internal fun decompile(lambda: java.io.Serializable): LambdaInstance = try {
        LambdaDecompiler.decompile(lambda)
    } catch (e: UntranslatableLambdaException) {
        throw QueryBuildException(e.message ?: "untranslatable lambda", e)
    }

    /** Lowers what was recorded into a validated query. */
    internal fun <R> build(executor: QueryExecutor?): Query<R> = Lowering(this).lower(executor)

    companion object {
        private val current = ThreadLocal<QueryBuilder?>()

        /**
         * The builder of the `query { }` block running on this thread: how generated infix functions
         * (`mail childOf acc`) find it when neither side is a variable.
         */
        @JvmStatic
        fun current(): QueryBuilder = current.get() ?: throw IllegalStateException("not inside query { }")

        /** The builder [a] or [b] belongs to, or the current one. */
        @JvmStatic
        fun of(a: EntityId, b: EntityId): QueryBuilder =
            (a as? QueryVariable)?.owner ?: (b as? QueryVariable)?.owner ?: current()

        internal fun <B : QueryBuilder, R> record(builder: B, executor: QueryExecutor?, body: (B) -> Selection<R>): Query<R> {
            val previous = current.get()
            current.set(builder)
            val selection = try {
                body(builder)
            } finally {
                current.set(previous)
            }
            if (selection.owner !== builder) throw QueryBuildException("select of another query")
            return builder.build(executor)
        }
    }
}

/** What the builder recorded, before lowering. */
internal sealed interface DTerm {
    data class Match(val subject: EntityId, val type: ComponentType, val target: EntityId?, val where: List<LambdaInstance>) : DTerm

    enum class GroupKind { NOT, OPTIONAL }

    data class Group(val kind: GroupKind, val terms: List<DTerm>) : DTerm

    data class AnyOf(val subject: EntityId, val types: List<ComponentType>, val negated: Boolean) : DTerm

    data class Filter(val condition: LambdaInstance) : DTerm
}

internal sealed interface DSelect {
    data class Lambda(val projection: LambdaInstance) : DSelect

    data class Columns(val columns: List<Any>, val single: Boolean) : DSelect
}
