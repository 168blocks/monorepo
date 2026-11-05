package net.blocks168.pgecs.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.isInternal
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.Variance
import java.security.MessageDigest

class PgecsProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        PgecsProcessor(environment.codeGenerator, environment.logger)
}

/**
 * Generates, per package, for every Kotlin `object` that is a pgecs [Relation][RELATION],
 * [Component][COMPONENT] or [Tag][TAG]:
 *
 * - Relations: an infix query function, `infix fun EntityId.childOf(target: EntityId)`, so
 *   `mail childOf acc` works inside `query { }` (it finds the query through its variables).
 * - Exclusive relations (`ExclusiveRelation`): a set-only `EntityWriter.childOf` property (`childOf = acc`).
 * - Value components: a set-only `EntityWriter.email` property (`email = Email("aea")`, null
 *   removes). The `Component` suffix is dropped.
 *
 * Tags and non-exclusive relations need nothing generated: `+Account`, `-Account`, `+OwnedBy(acc)`
 * are members of `EntityWriter`. `@PgecsName("...")` overrides a generated name; two generated
 * names colliding in one package are a build error. Getters of the generated properties are
 * `@Deprecated(level = ERROR)`, so reading one does not compile: reads go through `get(...)`.
 */
class PgecsProcessor(private val codeGenerator: CodeGenerator, private val logger: KSPLogger) : SymbolProcessor {
    private var done = false

    private class Handle(
        val decl: KSClassDeclaration,
        val kind: Kind,
        val name: String,
        /** The component's value type, rendered. */
        val valueType: String?,
        /** Whether the relation is an `ExclusiveRelation`. */
        val exclusive: Boolean,
    ) {
        val qualified: String get() = decl.qualifiedName!!.asString()
        val visibility: String get() = if (decl.isInternal()) "internal " else ""
    }

    enum class Kind { COMPONENT, TAG, RELATION }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (done) return emptyList()
        done = true
        val handles = resolver.getAllFiles()
            .flatMap { f -> f.declarations.filterIsInstance<KSClassDeclaration>().flatMap(::withNested) }
            .mapNotNull(::handleOf)
            .toList()
        handles.groupBy { it.decl.packageName.asString() }.forEach { (pkg, list) -> generate(pkg, list) }
        return emptyList()
    }

    private fun withNested(d: KSClassDeclaration): Sequence<KSClassDeclaration> =
        sequenceOf(d) + d.declarations.filterIsInstance<KSClassDeclaration>().flatMap(::withNested)

    private fun handleOf(d: KSClassDeclaration): Handle? {
        if (d.classKind != ClassKind.OBJECT || d.isPrivate() || d.isCompanionObject) return null
        val supers = d.getAllSuperTypes().toList()
        val names = supers.map { it.declaration.qualifiedName?.asString() }
        val kind = when {
            COMPONENT in names -> Kind.COMPONENT
            RELATION in names -> Kind.RELATION
            TAG in names -> Kind.TAG
            else -> return null
        }
        val override = d.annotations.firstOrNull { it.shortName.asString() == "PgecsName" }
            ?.arguments?.firstOrNull()?.value as? String
        val simple = d.simpleName.asString()
        val base = override ?: when (kind) {
            Kind.COMPONENT -> if (simple.endsWith("Component") && simple != "Component") simple.removeSuffix("Component") else simple
            else -> simple
        }
        val name = override ?: base.replaceFirstChar { it.lowercaseChar() }
        val valueType = if (kind == Kind.COMPONENT) {
            supers.first { it.declaration.qualifiedName?.asString() == COMPONENT }.arguments.firstOrNull()?.let(::render)
        } else null
        return Handle(d, kind, name, valueType, exclusive = EXCLUSIVE_RELATION in names)
    }

    private fun render(arg: KSTypeArgument): String = when (arg.variance) {
        Variance.STAR -> "*"
        else -> render(arg.type!!.resolve())
    }

    private fun render(t: KSType): String {
        val base = t.declaration.qualifiedName?.asString() ?: t.declaration.simpleName.asString()
        val args = if (t.arguments.isEmpty()) "" else t.arguments.joinToString(", ", "<", ">") { render(it) }
        return base + args + if (t.isMarkedNullable) "?" else ""
    }

    private fun generate(pkg: String, handles: List<Handle>) {
        val byName = handles.groupBy { it.name }
        var ok = true
        for ((name, same) in byName) if (same.size > 1) {
            ok = false
            logger.error(
                "pgecs: generated name '$name' collides in package $pkg: ${same.joinToString { it.qualified }}; " +
                    "rename one or use @PgecsName",
                same[1].decl,
            )
        }
        if (!ok) return
        val code = buildString {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(handles.joinToString(",") { it.qualified }.toByteArray())
                .take(4).joinToString("") { "%02x".format(it) }
            appendLine("// Generated by pgecs-ksp. Do not edit.")
            appendLine("@file:JvmName(\"PgecsGenerated_$hash\")")
            appendLine("@file:Suppress(\"unused\", \"RedundantVisibilityModifier\")")
            appendLine()
            if (pkg.isNotEmpty()) appendLine("package $pkg").appendLine()
            for (h in handles.sortedBy { it.name }) {
                val v = h.visibility
                when (h.kind) {
                    Kind.RELATION -> {
                        appendLine("/** `subject ${h.name} target` inside `query { }`: the pair (${h.qualified}, target). */")
                        appendLine(
                            "${v}infix fun net.blocks168.pgecs.EntityId.${h.name}(target: net.blocks168.pgecs.EntityId): Unit =\n" +
                                "    net.blocks168.pgecs.dsl.QueryBuilder.of(this, target).relate(${h.qualified}, this, target)",
                        )
                        appendLine()
                        if (h.exclusive) {
                            setter(v, h.name, "net.blocks168.pgecs.EntityId?", "set(${h.qualified}, value)")
                        }
                    }
                    Kind.COMPONENT -> setter(v, h.name, nullable(h.valueType ?: "Any"), "set(${h.qualified}, value)")
                    Kind.TAG -> {}
                }
            }
        }
        val sources: Array<KSFile> = handles.mapNotNull { it.decl.containingFile }.distinct().toTypedArray()
        codeGenerator.createNewFile(Dependencies(aggregating = true, *sources), pkg, "PgecsGenerated").use {
            it.write(code.toByteArray())
        }
    }

    private fun nullable(t: String) = if (t.endsWith("?")) t else "$t?"

    private fun StringBuilder.setter(visibility: String, name: String, type: String, body: String) {
        appendLine("/** Write-only: `$name = x` in `create { }` / `update(e) { }`; null removes it. Read with `get(...)`. */")
        appendLine("${visibility}var net.blocks168.pgecs.EntityWriter.$name: $type")
        appendLine("    @Deprecated(\"write-only; read with get(...)\", level = DeprecationLevel.ERROR)")
        appendLine("    get() = throw UnsupportedOperationException(\"write-only\")")
        appendLine("    set(value) { $body }")
        appendLine()
    }

    companion object {
        const val COMPONENT = "net.blocks168.pgecs.Component"
        const val TAG = "net.blocks168.pgecs.Tag"
        const val RELATION = "net.blocks168.pgecs.Relation"
        const val EXCLUSIVE_RELATION = "net.blocks168.pgecs.ExclusiveRelation"
    }
}
