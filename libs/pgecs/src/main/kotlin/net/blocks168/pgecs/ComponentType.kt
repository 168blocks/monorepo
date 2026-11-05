package net.blocks168.pgecs

import java.lang.reflect.ParameterizedType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import net.blocks168.pgecs.ir.ComponentKind
import net.blocks168.pgecs.ir.ComponentRef
import net.blocks168.pgecs.ir.SourceId
import net.blocks168.pgecs.ir.ValueType
import net.blocks168.pgecs.lambda.QueryFunction
import net.blocks168.pgecs.lambda.QueryPredicate
import net.blocks168.pgecs.schema.Cleanup
import net.blocks168.pgecs.schema.IndexSpec
import net.blocks168.pgecs.schema.RuleSpec
import net.blocks168.pgecs.serial.SerialTypes

/**
 * A registered type: a [Component] (with a value), a [Tag], a [Relation] or a [Capability]. Declared
 * as Kotlin objects (`object Account : Tag("b8:iam:account:v1")`) or, from Java, as static final
 * fields (`static final Tag ACCOUNT = new Tag("b8:iam:account:v1")`).
 *
 * [name] follows `<project>:<namespace(s)>:<name>[:<version>]`; `pgecs:` is reserved for the
 * library's own types.
 */
sealed class ComponentType(val name: String, val source: SourceId) {
    init {
        require(NAME.matches(name)) {
            "invalid component name '$name': expected non-empty colon-separated segments without whitespace or quotes"
        }
    }

    /** The descriptor the query IR uses. */
    abstract val ref: ComponentRef

    /** Short name for generated variables: the last segment that is not a version (`email` for `b8:iam:email:v1`). */
    internal val shortName: String
        get() {
            val segments = name.split(':')
            val last = segments.lastOrNull { !VERSION.matches(it) } ?: segments.last()
            return last.replace(Regex("[^A-Za-z0-9_]"), "_")
        }

    /** Names are unique in the catalog, so two handles with one name are the same type. */
    final override fun equals(other: Any?): Boolean = this === other || (other is ComponentType && other.name == name)

    final override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = name

    private companion object {
        val NAME = Regex("""[^:\s'"\\]+(:[^:\s'"\\]+)+""")
        val VERSION = Regex("v[0-9]+")
    }
}

/**
 * A component with a value of type [T], stored as JSON through [serializer].
 *
 * ```kotlin
 * object EmailComponent : Component<Email>("b8:iam:email:v1")   // serializer<Email>() is found from T
 * static final Component<Email> EMAIL = new Component<>("b8:iam:email:v1", Email.Companion.serializer());
 * ```
 */
open class Component<T> : ComponentType {
    /** How values are stored; also the source of the JSON Schema and of query field paths. */
    val serializer: KSerializer<T>

    constructor(name: String, serializer: KSerializer<T>, source: SourceId = SourceId.POSTGRES) : super(name, source) {
        this.serializer = serializer
    }

    /** Finds the serializer of [T] from the subclass' type argument (`object X : Component<Email>(...)`). */
    constructor(name: String, source: SourceId = SourceId.POSTGRES) : super(name, source) {
        @Suppress("UNCHECKED_CAST")
        this.serializer = serializer(typeArgument(javaClass, name)) as KSerializer<T>
    }

    /** The IR type of the whole value. */
    val valueType: ValueType get() = SerialTypes.valueType(serializer.descriptor).asNonNull()

    private val declaredIndexes = mutableListOf<IndexSpec>()
    private class Rule<T>(val description: String, val kotlinOnly: Boolean, val check: QueryPredicate<T>)

    private val rules = mutableListOf<Rule<T>>()

    private val lambdaIndexes = mutableListOf<Triple<String?, Boolean, QueryFunction<T, *>>>()

    /** Indexes declared with [index] and [unique]; freeze builds them. */
    val indexes: List<IndexSpec> get() = declaredIndexes.toList() + lambdaIndexSpecs

    private val lambdaIndexSpecs: List<IndexSpec> by lazy {
        lambdaIndexes.map { (key, unique, f) ->
            val sql = IndexExpressions.render(this, f)
            val k = key ?: ((if (unique) "u_" else "i_") + IndexExpressions.digest(sql))
            IndexSpec(k, listOf("($sql)"), unique = unique)
        }
    }

    /**
     * A unique index on [f] of the value, as in `unique { it.address.lowercase() }`: two entities can
     * never hold values with equal [f], even under concurrency. [f] is decompiled and rendered exactly
     * as the query compiler renders it, so `where { it.address.lowercase() == x.lowercase() }` uses it.
     */
    protected fun unique(key: String? = null, f: QueryFunction<T, *>) {
        lambdaIndexes += Triple(key, true, f)
    }

    /** A (non-unique) index on [f] of the value, as in `index { it }` for range scans on an instant. */
    protected fun index(key: String? = null, f: QueryFunction<T, *>) {
        lambdaIndexes += Triple(key, false, f)
    }

    /**
     * Declares a per-component index (partial on this component's rows). [IndexSpec.expressions] must
     * be exactly the SQL the query compiler emits for the indexed field (`SqlCompiler.fieldSql`),
     * for example `((value #>> '{}')::bigint)` for a scalar instant root or `(lower(value->>'address'))`.
     */
    protected fun index(spec: IndexSpec) {
        check(declaredIndexes.none { it.key == spec.key }) { "component $name: index ${spec.key} is declared twice" }
        declaredIndexes += spec
    }

    /**
     * A rule every value must satisfy (pgecs design, *Validation rules*), as in
     * `validate("no spaces") { !it.address.contains(' ') }`. The rule is decompiled and rendered to SQL
     * like a query predicate, and Postgres checks it on every write, next to the JSON Schema; freeze
     * fails if it cannot be rendered. A rule that cannot run in SQL (calling a library) is marked
     * [kotlinOnly]. Every rule also runs in Kotlin before a write is buffered, so a failing value
     * throws [ComponentValidationException] at the write itself.
     *
     * Rules are additive like the rest of the schema: adding one checks the stored values at freeze,
     * while changing or dropping one needs a new version of the component.
     */
    @JvmOverloads
    protected fun validate(description: String, kotlinOnly: Boolean = false, check: QueryPredicate<T>) {
        require(rules.none { it.description == description }) { "component $name: rule '$description' is declared twice" }
        rules += Rule(description, kotlinOnly, check)
    }

    /** Runs the rules on [value] in Kotlin. */
    fun check(value: T) {
        for (r in rules) {
            if (!r.check.test(value)) throw ComponentValidationException("$name: ${r.description} does not hold for $value")
        }
    }

    /** The rules Postgres checks, rendered to SQL over the `value` column. */
    val sqlRules: List<RuleSpec> by lazy {
        rules.filter { !it.kotlinOnly }.map { r ->
            val sql = try {
                IndexExpressions.render(this, r.check)
            } catch (e: net.blocks168.pgecs.dsl.QueryBuildException) {
                throw net.blocks168.pgecs.dsl.QueryBuildException(
                    "$name: rule '${r.description}' cannot be checked in SQL (${e.message}); mark it kotlinOnly = true if it must run in Kotlin only",
                    e,
                )
            }
            RuleSpec(r.description, sql)
        }
    }

    override val ref: ComponentRef by lazy { ComponentRef(name, ComponentKind.VALUE, valueType, source) }

    private companion object {
        fun typeArgument(cls: Class<*>, name: String): java.lang.reflect.Type {
            var c: Class<*> = cls
            while (c.superclass != Component::class.java) {
                c = c.superclass ?: break
            }
            val t = c.genericSuperclass as? ParameterizedType
                ?: throw IllegalArgumentException(
                    "component $name: no type argument to find its serializer from; pass the serializer explicitly",
                )
            return t.actualTypeArguments[0]
        }
    }
}

/** A component without a value. */
open class Tag @JvmOverloads constructor(name: String, source: SourceId = SourceId.POSTGRES) : ComponentType(name, source) {
    override val ref: ComponentRef by lazy { ComponentRef(name, ComponentKind.TAG, null, source) }
}

/**
 * A relationship: pairs `(relation, target)` on an entity, such as `(pgecs:child-of, <account>)`.
 * Traits have flecs semantics and are enforced by Postgres (docs/pgecs.md, "Flecs concepts mapped"):
 *
 * - [exclusive]: at most one pair per entity, so adding a second replaces the first;
 * - [acyclic]: pairs never form a cycle;
 * - [symmetric]: `(R, b)` on `a` implies `(R, a)` on `b`;
 * - [onDeleteTarget]: what deleting a target does to the pairs pointing at it.
 */
open class Relation @JvmOverloads constructor(
    name: String,
    val exclusive: Boolean = false,
    source: SourceId = SourceId.POSTGRES,
    val acyclic: Boolean = false,
    val symmetric: Boolean = false,
    val onDeleteTarget: Cleanup = Cleanup.REMOVE,
) : ComponentType(name, source) {
    override val ref: ComponentRef by lazy { ComponentRef(name, ComponentKind.RELATION, null, source) }
}

/**
 * An exclusive relation: at most one pair per entity, and adding a second replaces the first
 * (`childOf = acc` in writes). A separate type so that code generation and the write API can tell.
 */
open class ExclusiveRelation @JvmOverloads constructor(
    name: String,
    source: SourceId = SourceId.POSTGRES,
    acyclic: Boolean = false,
    onDeleteTarget: Cleanup = Cleanup.REMOVE,
) : Relation(name, exclusive = true, source, acyclic = acyclic, onDeleteTarget = onDeleteTarget)

/** Something an endpoint can do, with a payload of type [T]; an entity, never matched as a component. */
open class Capability<T> @JvmOverloads constructor(
    name: String,
    val serializer: KSerializer<T>,
    source: SourceId = SourceId.POSTGRES,
) : ComponentType(name, source) {
    override val ref: ComponentRef by lazy { ComponentRef(name, ComponentKind.CAPABILITY, null, source) }
}

/** `pgecs:child-of`: exclusive, acyclic, and deleting the parent deletes its children. */
object ChildOf : ExclusiveRelation("pgecs:child-of", acyclic = true, onDeleteTarget = Cleanup.DELETE)

/** A value failed a Kotlin-side check of its component ([Component.validate]). */
class ComponentValidationException(message: String) : IllegalArgumentException(message)

/** Renders `index { }` / `unique { }` lambdas to SQL with the query compiler. */
internal object IndexExpressions {
    fun render(component: Component<*>, f: java.io.Serializable): String {
        val ir = net.blocks168.pgecs.dsl.QueryBuilder.forIndex().valueFunction(component, f)
        val plan = net.blocks168.pgecs.query.lower.Lowering.lower(ir).query
        return try {
            net.blocks168.pgecs.query.sql.SqlCompiler.rowExpression(plan)
        } catch (e: IllegalArgumentException) {
            throw net.blocks168.pgecs.dsl.QueryBuildException("${component.name}: ${e.message}", e)
        }
    }

    fun digest(sql: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(sql.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
}
