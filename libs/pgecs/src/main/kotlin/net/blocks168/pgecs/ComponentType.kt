package net.blocks168.pgecs

import java.lang.reflect.ParameterizedType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import net.blocks168.pgecs.ir.ComponentKind
import net.blocks168.pgecs.ir.ComponentRef
import net.blocks168.pgecs.ir.SourceId
import net.blocks168.pgecs.ir.ValueType
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
 * [exclusive] relations hold at most one pair per entity, so adding a second replaces the first.
 */
open class Relation @JvmOverloads constructor(
    name: String,
    val exclusive: Boolean = false,
    source: SourceId = SourceId.POSTGRES,
) : ComponentType(name, source) {
    override val ref: ComponentRef by lazy { ComponentRef(name, ComponentKind.RELATION, null, source) }
}

/** Something an endpoint can do, with a payload of type [T]; an entity, never matched as a component. */
open class Capability<T> @JvmOverloads constructor(
    name: String,
    val serializer: KSerializer<T>,
    source: SourceId = SourceId.POSTGRES,
) : ComponentType(name, source) {
    override val ref: ComponentRef by lazy { ComponentRef(name, ComponentKind.CAPABILITY, null, source) }
}

/** `pgecs:child-of`: exclusive, acyclic, and deleting the parent deletes its children. */
object ChildOf : Relation("pgecs:child-of", exclusive = true)
