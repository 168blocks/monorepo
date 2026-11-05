package net.blocks168.pgecs

import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.StructureKind
import net.blocks168.pgecs.schema.ComponentKind
import net.blocks168.pgecs.schema.ComponentSpec
import net.blocks168.pgecs.schema.Traits
import net.blocks168.pgecs.serial.JsonSchemas

/**
 * The component, tag, relation and capability types of one Gradle module. pgecs-ksp generates one per
 * module with a `META-INF/services` entry, so [Pgecs.connect] finds every module on the class path
 * through [ServiceLoader]; Java modules can implement it by hand.
 */
interface PgecsModule {
    /** Every type the module declares. Touching them here is what initialises the Kotlin objects. */
    fun types(): List<ComponentType>
}

/** A type that is not part of the frozen registry was used. */
class UnregisteredComponentException(type: String) :
    IllegalStateException("component $type is not registered: add it to a PgecsModule (pgecs-ksp does this) or register it before freeze")

/**
 * The registered types of one [Ecs]. Open until [Ecs.freeze], read-only afterwards. Two handles with
 * one name must declare the same type; a second, different declaration is an error.
 */
class Registry internal constructor() {
    private val byName = ConcurrentHashMap<String, ComponentType>()

    @Volatile
    var frozen: Boolean = false
        private set

    /** Registers [types]; throws after freeze or when a name is declared twice differently. */
    fun register(vararg types: ComponentType) = register(types.asList())

    fun register(types: Collection<ComponentType>) {
        check(!frozen) { "the registry is frozen; register types before Ecs.freeze()" }
        for (t in types) {
            val old = byName.putIfAbsent(t.name, t)
            if (old != null && old !== t && old.toSpec() != t.toSpec()) {
                throw IllegalArgumentException("component ${t.name} is declared twice, differently: ${old::class.java.name} and ${t::class.java.name}")
            }
        }
    }

    /** Registers the types of every [PgecsModule] that [loader] finds. */
    fun registerModules(loader: ClassLoader? = Thread.currentThread().contextClassLoader ?: javaClass.classLoader) {
        for (m in ServiceLoader.load(PgecsModule::class.java, loader)) register(m.types())
    }

    operator fun get(name: String): ComponentType? = byName[name]

    val types: Collection<ComponentType> get() = byName.values

    /** Throws [UnregisteredComponentException] when [type] is not in the frozen registry. */
    fun require(type: ComponentType) {
        if (frozen && byName[type.name] == null) throw UnregisteredComponentException(type.name)
    }

    /** What freeze applies, per type; the library's own (`pgecs:`) types are left to freeze itself. */
    fun specs(): List<ComponentSpec> = byName.values
        .filter { !it.name.startsWith("pgecs:") || it.name !in LIBRARY_SPECS }
        .map { it.toSpec() }
        .sortedBy { it.name }

    internal fun freeze() {
        frozen = true
    }

    internal companion object {
        /** Names of types that freeze registers itself (CoreComponents). */
        val LIBRARY_SPECS = net.blocks168.pgecs.schema.CoreComponents.ALL.map { it.name }.toSet()
    }
}

/** The freeze spec of this handle: kind, traits, JSON Schema and declared indexes. */
fun ComponentType.toSpec(): ComponentSpec = when (this) {
    is Tag -> ComponentSpec(name, ComponentKind.TAG)
    is Relation -> ComponentSpec(
        name,
        ComponentKind.RELATION,
        traits = Traits(exclusive = exclusive, acyclic = acyclic, symmetric = symmetric, onDeleteTarget = onDeleteTarget),
    )
    is Component<*> -> ComponentSpec(
        name,
        ComponentKind.VALUE,
        jsonSchema = JsonSchemas.of(serializer.descriptor).toString(),
        indexes = indexes,
        rules = sqlRules,
    )
    is Capability<*> -> ComponentSpec(name, ComponentKind.CAPABILITY, jsonSchema = JsonSchemas.of(serializer.descriptor).toString())
}

/**
 * Whether stored values of [component] are merged with what is written ([object roots][StructureKind.CLASS]
 * and sealed hierarchies), so that fields written by a newer JVM survive a write by an older one.
 */
internal val Component<*>.mergesOnWrite: Boolean
    get() {
        val d = serializer.descriptor.let { if (it.isInline) it.getElementDescriptor(0) else it }
        return d.kind == StructureKind.CLASS || d.kind == PolymorphicKind.SEALED
    }
