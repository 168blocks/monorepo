package net.blocks168.pgecs.lambda

import java.io.Serializable
import kotlin.jvm.internal.CallableReference
import kotlin.jvm.internal.ClassBasedDeclarationContainer
import kotlin.jvm.internal.PackageReference
import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.classfile.MethodModel
import java.lang.constant.MethodTypeDesc
import java.lang.invoke.MethodHandleInfo
import java.lang.invoke.SerializedLambda
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** A lambda that cannot be translated: the message names the construct and its source line. */
class UntranslatableLambdaException(message: String) : RuntimeException(message)

/**
 * A decompiled lambda body, independent of the values it captured; cached per implementing method.
 *
 * @property paramTypes JVM descriptors of the functional interface method's parameters ([LExpr.Arg]).
 * @property capturedTypes JVM descriptors of the captured values ([LExpr.Captured]).
 * @property location `Class.method` of the implementation, for messages.
 */
data class DecompiledLambda(
    val paramTypes: List<String>,
    val capturedTypes: List<String>,
    val returnType: String,
    val body: LExpr,
    val location: String,
)

/** A decompiled lambda together with the values one instance of it captured. */
class LambdaInstance(val decompiled: DecompiledLambda, val captured: List<Any?>) {
    val body: LExpr get() = decompiled.body
    override fun toString(): String = "LambdaInstance(${decompiled.body.render()}, captured=$captured)"
}

/** Reads `static final` fields for `getstatic`, so Kotlin objects and constants become [LExpr.Const]. */
internal fun interface StaticFieldReader {
    fun read(owner: String, name: String, descriptor: String): LExpr?
}

/**
 * Decompiles Serializable lambdas ([QueryPredicate], [QueryFunction], [QuerySupplier], or any other
 * Serializable functional interface) into [LExpr] trees.
 *
 * Two shapes of lambda are understood:
 * - `invokedynamic` lambdas (javac, and kotlinc's SAM conversions): `writeReplace` yields a
 *   [SerializedLambda] naming the implementing method and holding the captured values.
 * - Lambdas compiled to their own class: the functional method of that class is read directly, and
 *   its instance fields are the captured values.
 *
 * Results are cached per implementing method; only the captured values differ between instances.
 */
object LambdaDecompiler {
    private val cache = ConcurrentHashMap<String, DecompiledLambda>()

    fun decompile(lambda: Serializable): LambdaInstance {
        val serialized = serializedForm(lambda)
        return if (serialized != null) fromSerialized(lambda, serialized) else fromClass(lambda)
    }

    /** Number of cached implementations, for tests and metrics. */
    val cacheSize: Int get() = cache.size

    private fun serializedForm(lambda: Any): SerializedLambda? {
        val m = try {
            lambda.javaClass.getDeclaredMethod("writeReplace")
        } catch (_: NoSuchMethodException) {
            return null
        }
        m.isAccessible = true
        return m.invoke(lambda) as? SerializedLambda
    }

    private fun loaderOf(lambda: Any): ClassLoader =
        lambda.javaClass.classLoader ?: ClassLoader.getSystemClassLoader()

    private fun fromSerialized(lambda: Any, sl: SerializedLambda): LambdaInstance {
        val captured = List(sl.capturedArgCount) { sl.getCapturedArg(it) }
        val key = "${sl.implMethodKind}:${sl.implClass}.${sl.implMethodName}${sl.implMethodSignature}"
        val loader = loaderOf(lambda)
        val decompiled = cache.computeIfAbsent(key) { buildSerialized(loader, sl) }
        return LambdaInstance(resolveCallableReference(decompiled, captured), captured)
    }

    /**
     * kotlinc compiles a SAM-converted reference such as `Email::address` to a lambda that captures
     * the `KProperty1` and calls `invoke` on it. The reference names the getter, so the call is
     * rewritten to it. The rewrite depends on a captured value, so it happens per instance.
     */
    private fun resolveCallableReference(d: DecompiledLambda, captured: List<Any?>): DecompiledLambda {
        val body = d.body as? LExpr.Invoke ?: return d
        val recv = body.receiver as? LExpr.Captured ?: return d
        if (body.name != "invoke" && body.name != "get") return d
        val ref = captured[recv.index] as? CallableReference ?: return d
        if (ref.boundReceiver !== CallableReference.NO_RECEIVER) return d
        val container = ref.owner as? ClassBasedDeclarationContainer ?: return d
        val owner = container.jClass.name.replace('.', '/')
        val sig = ref.signature
        val name = sig.substringBefore('(')
        val desc = sig.substring(name.length)
        val ret = MethodTypeDesc.ofDescriptor(desc).returnType().descriptorString()
        val call = when {
            name == "<init>" -> LExpr.New(owner, desc, body.args)
            container is PackageReference -> Simplify.call(LExpr.Invoke(LExpr.Invoke.Kind.STATIC, owner, name, desc, null, body.args, ret))
            body.args.isEmpty() -> return d
            else -> Simplify.call(
                LExpr.Invoke(LExpr.Invoke.Kind.VIRTUAL, owner, name, desc, body.args[0], body.args.drop(1), ret),
            )
        }
        return d.copy(body = call)
    }

    private fun buildSerialized(loader: ClassLoader, sl: SerializedLambda): DecompiledLambda {
        val implClass = sl.implClass
        val implType = MethodTypeDesc.ofDescriptor(sl.implMethodSignature)
        val samType = MethodTypeDesc.ofDescriptor(sl.instantiatedMethodType)
        val kind = sl.implMethodKind
        val hasReceiver = kind == MethodHandleInfo.REF_invokeVirtual || kind == MethodHandleInfo.REF_invokeInterface ||
            kind == MethodHandleInfo.REF_invokeSpecial
        // The values the implementation receives: the receiver (for instance methods), then its parameters.
        val valueTypes = buildList {
            if (hasReceiver) add("L$implClass;")
            implType.parameterList().forEach { add(it.descriptorString()) }
        }
        val capturedCount = sl.capturedArgCount
        val values = valueTypes.mapIndexed { i, t ->
            if (i < capturedCount) LExpr.Captured(i, t) else LExpr.Arg(i - capturedCount, t)
        }
        val returnType = samType.returnType().descriptorString()
        val location = "${implClass.replace('/', '.')}.${sl.implMethodName}"
        val model = classModel(loader, implClass)
        val method = model.methods().firstOrNull {
            it.methodName().equalsString(sl.implMethodName) && it.methodType().equalsString(sl.implMethodSignature)
        } ?: throw UntranslatableLambdaException("implementation $location${sl.implMethodSignature} not found")

        val synthetic = method.flags().has(java.lang.reflect.AccessFlag.SYNTHETIC) ||
            sl.implMethodName.startsWith("lambda$") || sl.implMethodName.contains("\$lambda")
        val body = if (synthetic && kind != MethodHandleInfo.REF_newInvokeSpecial) {
            val slots = HashMap<Int, LExpr>()
            var slot = 0
            for (v in values) {
                slots[slot] = v
                slot += if (v.type == "J" || v.type == "D") 2 else 1
            }
            execute(loader, model, method, location, slots, thisSlot = false, returnType = returnType)
        } else {
            methodReference(kind, sl, values, implType)
        }
        return DecompiledLambda(
            paramTypes = samType.parameterList().map { it.descriptorString() },
            capturedTypes = values.take(capturedCount).map { it.type },
            returnType = returnType,
            body = body,
            location = sourceLocation(model, location),
        )
    }

    /** `Email::address`, `String::length`, `::Pair`: the body is the call itself. */
    private fun methodReference(kind: Int, sl: SerializedLambda, values: List<LExpr>, implType: MethodTypeDesc): LExpr {
        val ret = implType.returnType().descriptorString()
        return when (kind) {
            MethodHandleInfo.REF_newInvokeSpecial -> LExpr.New(sl.implClass, sl.implMethodSignature, values)
            MethodHandleInfo.REF_invokeStatic -> Simplify.call(
                LExpr.Invoke(LExpr.Invoke.Kind.STATIC, sl.implClass, sl.implMethodName, sl.implMethodSignature, null, values, ret),
            )
            else -> Simplify.call(
                LExpr.Invoke(
                    if (kind == MethodHandleInfo.REF_invokeInterface) LExpr.Invoke.Kind.INTERFACE else LExpr.Invoke.Kind.VIRTUAL,
                    sl.implClass, sl.implMethodName, sl.implMethodSignature, values[0], values.drop(1), ret,
                ),
            )
        }
    }

    private fun fromClass(lambda: Any): LambdaInstance {
        val cls = lambda.javaClass
        if (cls.isHidden) {
            throw UntranslatableLambdaException(
                "${cls.name} is a hidden lambda class without writeReplace; is its interface Serializable?",
            )
        }
        val fields = instanceFields(cls)
        val captured = fields.map { f -> f.isAccessible = true; f.get(lambda) }
        val decompiled = cache.computeIfAbsent("class:${cls.name}") { buildFromClass(cls, fields.map { it.name }) }
        return LambdaInstance(resolveCallableReference(decompiled, captured), captured)
    }

    private fun instanceFields(cls: Class<*>) = cls.declaredFields.filter { !Modifier.isStatic(it.modifiers) }

    private fun buildFromClass(cls: Class<*>, fieldNames: List<String>): DecompiledLambda {
        val sam = functionalMethod(cls)
            ?: throw UntranslatableLambdaException("${cls.name} implements no functional interface")
        val loader = cls.classLoader ?: ClassLoader.getSystemClassLoader()
        val internal = cls.name.replace('.', '/')
        val model = classModel(loader, internal)
        // The non-bridge implementation of the functional method.
        val method = model.methods().filter { it.methodName().equalsString(sam.name) && !it.flags().has(java.lang.reflect.AccessFlag.BRIDGE) }
            .firstOrNull { MethodTypeDesc.ofDescriptor(it.methodType().stringValue()).parameterCount() == sam.parameterCount }
            ?: throw UntranslatableLambdaException("${cls.name}.${sam.name} not found")
        val type = MethodTypeDesc.ofDescriptor(method.methodType().stringValue())
        val slots = HashMap<Int, LExpr>()
        var slot = 1
        type.parameterList().forEachIndexed { i, p ->
            val d = p.descriptorString()
            slots[slot] = LExpr.Arg(i, d)
            slot += if (d == "J" || d == "D") 2 else 1
        }
        val fieldTypes = fieldNames.map { n -> model.fields().first { it.fieldName().equalsString(n) }.fieldType().stringValue() }
        val location = "${cls.name}.${sam.name}"
        val body = execute(
            loader, model, method, location, slots, thisSlot = true,
            returnType = type.returnType().descriptorString(),
            thisField = { name, _ ->
                val i = fieldNames.indexOf(name)
                if (i < 0) throw UntranslatableLambdaException("unknown field $name of ${cls.name}")
                LExpr.Captured(i, fieldTypes[i])
            },
        )
        return DecompiledLambda(
            paramTypes = type.parameterList().map { it.descriptorString() },
            capturedTypes = fieldTypes,
            returnType = type.returnType().descriptorString(),
            body = body,
            location = sourceLocation(model, location),
        )
    }

    private fun functionalMethod(cls: Class<*>): java.lang.reflect.Method? {
        var c: Class<*>? = cls
        while (c != null) {
            for (i in c.interfaces) {
                val abstract = i.methods.filter { Modifier.isAbstract(it.modifiers) }
                if (abstract.size == 1) return abstract[0]
            }
            c = c.superclass
        }
        return null
    }

    private fun execute(
        loader: ClassLoader,
        model: ClassModel,
        method: MethodModel,
        location: String,
        slots: Map<Int, LExpr>,
        thisSlot: Boolean,
        returnType: String,
        thisField: ((String, String) -> LExpr)? = null,
    ): LExpr {
        val code = method.code().orElseThrow { UntranslatableLambdaException("$location has no code") }
        val where = sourceLocation(model, location)
        val exec = SymbolicExecutor(where, code, returnType, staticReader(loader), thisField, enumConstants = { owner ->
            runCatching { Class.forName(owner.replace('/', '.'), false, loader) }.getOrNull()?.enumConstants?.toList()
        })
        return exec.run(slots, thisSlot)
    }

    private fun sourceLocation(model: ClassModel, location: String): String {
        val file = model.findAttribute(Attributes.sourceFile()).map { it.sourceFile().stringValue() }.orElse(null)
        return if (file != null) "$location, $file" else location
    }

    private fun staticReader(loader: ClassLoader) = StaticFieldReader { owner, name, desc ->
        val cls = try {
            Class.forName(owner.replace('/', '.'), true, loader)
        } catch (_: ClassNotFoundException) {
            return@StaticFieldReader null
        }
        val f = generateSequence<Class<*>>(cls) { it.superclass }
            .firstNotNullOfOrNull { c -> c.declaredFields.firstOrNull { it.name == name } } ?: return@StaticFieldReader null
        if (!Modifier.isStatic(f.modifiers) || !Modifier.isFinal(f.modifiers)) return@StaticFieldReader null
        if (!f.trySetAccessible()) return@StaticFieldReader null
        LExpr.Const(f.get(null), desc)
    }

    private fun classModel(loader: ClassLoader, internalName: String): ClassModel {
        val bytes = loader.getResourceAsStream("$internalName.class")?.use { it.readAllBytes() }
            ?: throw UntranslatableLambdaException("class file of ${internalName.replace('/', '.')} not found")
        return ClassFile.of().parse(bytes)
    }
}
