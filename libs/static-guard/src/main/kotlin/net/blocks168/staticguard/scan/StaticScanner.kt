package net.blocks168.staticguard.scan

import java.io.ByteArrayInputStream
import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.classfile.CodeElement
import java.lang.classfile.Instruction
import java.lang.classfile.Opcode
import java.lang.classfile.instruction.ArrayLoadInstruction
import java.lang.classfile.instruction.ArrayStoreInstruction
import java.lang.classfile.instruction.BranchInstruction
import java.lang.classfile.instruction.ConstantInstruction
import java.lang.classfile.instruction.ConvertInstruction
import java.lang.classfile.instruction.FieldInstruction
import java.lang.classfile.instruction.InvokeDynamicInstruction
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.classfile.instruction.LoadInstruction
import java.lang.classfile.instruction.NewObjectInstruction
import java.lang.classfile.instruction.OperatorInstruction
import java.lang.classfile.instruction.StackInstruction
import java.lang.classfile.instruction.TypeCheckInstruction
import java.lang.reflect.AccessFlag
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * Finds process-global mutable state in class files: what would be shared between several
 * independent "instances" (servers, tenants, ...) running in one JVM.
 *
 * It is a per-method, intraprocedural pass over the bytecode (JDK ClassFile API), so it is fast
 * enough for a whole game jar plus mods, and both over- and under-approximates:
 *
 * * it cannot tell bootstrap-time writes (done once, shared on purpose) from writes while an
 *   instance runs; the runtime tracer ([net.blocks168.staticguard.agent.StaticGuardAgent]) can;
 * * it follows the value of a `getstatic` only through straight-line code up to the first use
 *   (`MAP.put(k, v)`, `ARRAY[i] = v`), not through locals, fields or calls;
 * * reflection, `VarHandle`, `Unsafe` and JNI writes are invisible.
 *
 * @param instanceTypes internal names of per-instance types (`net/minecraft/server/MinecraftServer`):
 *   any static field of such a type is [Kind.INSTANCE_STATIC].
 * @param nested also scan jars inside jars (Fabric jar-in-jar, `META-INF/jars/`).
 */
class StaticScanner(
    private val instanceTypes: Set<String> = emptySet(),
    private val nested: Boolean = true,
) {
    private val findings = mutableListOf<Finding>()
    var classesScanned = 0
        private set

    fun result(): List<Finding> = findings.toList()

    /** Scans a jar, a directory of classes, or a single `.class` file. */
    fun scan(path: Path) {
        when {
            path.isDirectory() -> Files.walk(path).use { s ->
                s.filter { it.name.endsWith(".class") }.sorted().forEach { scanClass(it.readBytes(), path.name) }
            }
            path.name.endsWith(".class") -> scanClass(path.readBytes(), path.name)
            else -> scanJar(path.readBytes(), path.name)
        }
    }

    fun scanJar(bytes: ByteArray, origin: String) {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val n = e.name
                when {
                    n.endsWith(".class") && !n.startsWith("META-INF/versions/") && !n.endsWith("module-info.class") ->
                        scanClass(zip.readAllBytes(), origin)
                    nested && n.endsWith(".jar") -> scanJar(zip.readAllBytes(), "$origin!/$n")
                }
            }
        }
    }

    fun scanClass(bytes: ByteArray, origin: String) {
        val cm = try {
            ClassFile.of().parse(bytes)
        } catch (e: IllegalArgumentException) {
            return // not a class file we can read (newer version, broken)
        }
        classesScanned++
        ClassPass(cm, origin).run()
    }

    private inner class ClassPass(val cm: ClassModel, val origin: String) {
        val self = cm.thisClass().asInternalName()
        val mixin = cm.findAttribute(Attributes.runtimeInvisibleAnnotations()).map { a ->
            a.annotations().any { it.classSymbol().descriptorString() == MIXIN }
        }.orElse(false)

        fun add(kind: Kind, field: String, site: String) {
            findings += Finding(kind, field, site, origin, mixin)
        }

        fun run() {
            val mutated = mutableSetOf<String>() // fields of this class with a mutation finding
            for (m in cm.methods()) {
                val code = m.code().orElse(null) ?: continue
                val mname = m.methodName().stringValue()
                val site = "$self.$mname"
                val elements: List<CodeElement> = code.elementList()
                val insns = elements.filterIsInstance<Instruction>()
                val nullChecked = mutableSetOf<String>()
                insns.forEachIndexed { i, ins ->
                    if (ins is FieldInstruction && ins.opcode() == Opcode.GETSTATIC) {
                        val next = insns.getOrNull(i + 1)
                        if (next is BranchInstruction && (next.opcode() == Opcode.IFNULL || next.opcode() == Opcode.IFNONNULL)) {
                            nullChecked += key(ins)
                        }
                    }
                }
                insns.forEachIndexed { i, ins ->
                    when (ins) {
                        is FieldInstruction -> when (ins.opcode()) {
                            Opcode.PUTSTATIC -> {
                                val k = key(ins)
                                val ownerInit = mname == "<clinit>" && ins.owner().asInternalName() == self
                                if (!ownerInit) {
                                    add(if (k in nullChecked) Kind.LAZY_INIT else Kind.STATIC_WRITE, k, site)
                                    if (ins.owner().asInternalName() == self) mutated += ins.name().stringValue()
                                }
                            }
                            Opcode.GETSTATIC -> if (mname != "<clinit>" || ins.owner().asInternalName() != self) {
                                val handle = ins.typeSymbol().descriptorString() in NOT_STATE
                                when (val use = if (handle) null else followStatic(insns, i)) {
                                    null -> {}
                                    is Use.Mutate -> {
                                        add(if (use.cache) Kind.STATIC_CACHE else Kind.STATIC_CONTAINER_MUTATION, "${key(ins)} .${use.method}", site)
                                        if (ins.owner().asInternalName() == self) mutated += ins.name().stringValue()
                                    }
                                    Use.ArrayStore -> {
                                        add(Kind.STATIC_ARRAY_STORE, key(ins), site)
                                        if (ins.owner().asInternalName() == self) mutated += ins.name().stringValue()
                                    }
                                }
                            }
                            else -> {}
                        }
                        is InvokeInstruction -> {
                            val callee = ins.owner().asInternalName() + "." + ins.name().stringValue()
                            if (callee in JVM_GLOBAL || JVM_GLOBAL_OWNERS.any { callee.startsWith(it) }) add(Kind.JVM_GLOBAL, callee, site)
                        }
                        else -> {}
                    }
                }
            }
            for (f in cm.fields()) {
                if (!f.flags().has(AccessFlag.STATIC)) continue
                val name = f.fieldName().stringValue()
                val desc = f.fieldType().stringValue()
                val k = "$self.$name:$desc"
                val type = desc.removePrefix("L").removeSuffix(";")
                when {
                    type in instanceTypes -> add(Kind.INSTANCE_STATIC, k, self)
                    type == "java/lang/ThreadLocal" || type == "java/lang/InheritableThreadLocal" -> add(Kind.THREAD_LOCAL, k, self)
                    name !in mutated && f.flags().has(AccessFlag.FINAL) && isMutableType(desc) -> add(Kind.MUTABLE_TYPE, k, self)
                }
            }
        }
    }

    private sealed interface Use {
        data class Mutate(val method: String, val cache: Boolean) : Use
        data object ArrayStore : Use
    }

    /**
     * Follows the value pushed by the `getstatic` at [start] through straight-line code until it is
     * used. Values are counted, not slots (long/double count as one). Gives up (null) on anything it
     * does not model: branches, `dup`, the value escaping into a local, a field or an argument.
     */
    private fun followStatic(insns: List<Instruction>, start: Int): Use? {
        var depth = 1 // values on the stack above the one below our value
        val ours = 0 // position of our value from the bottom of that window (never moves: only the top changes)
        fun popsOurs(n: Int) = depth - n <= ours
        for (i in start + 1 until minOf(insns.size, start + 64)) {
            when (val ins = insns[i]) {
                is LoadInstruction, is ConstantInstruction, is NewObjectInstruction -> depth++
                is FieldInstruction -> when (ins.opcode()) {
                    Opcode.GETSTATIC -> depth++
                    Opcode.GETFIELD -> if (popsOurs(1)) return null
                    Opcode.PUTSTATIC -> if (popsOurs(1)) return null else depth--
                    else -> { // PUTFIELD: objectref, value
                        if (depth - 2 == ours) return Use.Mutate("<putfield ${ins.name().stringValue()}>", false)
                        if (popsOurs(2)) return null
                        depth -= 2
                    }
                }
                is TypeCheckInstruction -> if (ins.opcode() == Opcode.INSTANCEOF && popsOurs(1)) return null
                is ConvertInstruction -> if (popsOurs(1)) return null
                is ArrayLoadInstruction -> if (popsOurs(2)) return null else depth--
                is ArrayStoreInstruction -> {
                    if (depth - 3 == ours) return Use.ArrayStore
                    if (popsOurs(3)) return null
                    depth -= 3
                }
                is OperatorInstruction -> {
                    val unary = ins.opcode() == Opcode.ARRAYLENGTH || ins.opcode().name.endsWith("NEG")
                    val n = if (unary) 1 else 2
                    if (popsOurs(n)) return null
                    depth -= n - 1
                }
                is InvokeInstruction -> {
                    val static = ins.opcode() == Opcode.INVOKESTATIC
                    val type = ins.typeSymbol()
                    val n = type.parameterCount() + if (static) 0 else 1
                    if (popsOurs(n)) {
                        if (static || depth - n != ours) return null // passed as an argument: escapes
                        val name = ins.name().stringValue()
                        return when {
                            name in CACHE_METHODS -> Use.Mutate(name, true)
                            MUTATOR.matches(name) -> Use.Mutate(name, false)
                            else -> null
                        }
                    }
                    depth -= n
                    if (type.returnType().descriptorString() != "V") depth++
                }
                is InvokeDynamicInstruction -> {
                    val type = ins.typeSymbol()
                    if (popsOurs(type.parameterCount())) return null // captured by a lambda: escapes
                    depth -= type.parameterCount()
                    if (type.returnType().descriptorString() != "V") depth++
                }
                is StackInstruction -> if (ins.opcode() == Opcode.POP && !popsOurs(1)) depth-- else return null
                else -> return null
            }
        }
        return null
    }

    private fun key(ins: FieldInstruction) =
        "${ins.owner().asInternalName()}.${ins.name().stringValue()}:${ins.typeSymbol().descriptorString()}"

    companion object {
        const val MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;"

        val MUTATOR = Regex(
            "^(set|add|put|remove|register|clear|push|pop|offer|poll|insert|delete|increment|decrement|" +
                "replace|merge|compute|getAnd|compareAnd|weakCompare|lazySet|sort|fill|retain|drain|trim).*|" +
                "append|updateAndGet|accumulateAndGet",
        )

        /**
         * Static fields whose methods act on other state, not on the object in the field: ThreadLocal
         * (per thread, reported as [Kind.THREAD_LOCAL]), VarHandles, field updaters, Unsafe.
         */
        val NOT_STATE = setOf(
            "Ljava/lang/ThreadLocal;", "Ljava/lang/InheritableThreadLocal;", "Ljava/lang/invoke/VarHandle;",
            "Ljava/lang/invoke/MethodHandle;", "Ljava/util/concurrent/atomic/AtomicIntegerFieldUpdater;",
            "Ljava/util/concurrent/atomic/AtomicLongFieldUpdater;", "Ljava/util/concurrent/atomic/AtomicReferenceFieldUpdater;",
            "Lsun/misc/Unsafe;", "Ljdk/internal/misc/Unsafe;", "Ljava/lang/foreign/MemorySegment;",
        )
        /** Mutators that are usually memoisation; checked before [MUTATOR]. */
        val CACHE_METHODS = setOf("computeIfAbsent")

        /** Methods that change state of the whole JVM, not of one instance. */
        val JVM_GLOBAL = setOf(
            "java/lang/System.exit", "java/lang/Runtime.exit", "java/lang/Runtime.halt",
            "java/lang/System.setProperty", "java/lang/System.clearProperty", "java/lang/System.setProperties",
            "java/lang/System.setOut", "java/lang/System.setErr", "java/lang/System.setIn",
            "java/lang/Runtime.addShutdownHook", "java/lang/Runtime.removeShutdownHook",
            "java/lang/Thread.setDefaultUncaughtExceptionHandler",
            "java/util/Locale.setDefault", "java/util/TimeZone.setDefault",
            "java/net/URL.setURLStreamHandlerFactory", "java/net/URLConnection.setContentHandlerFactory",
            "java/net/URLConnection.setDefaultUseCaches", "java/net/URLConnection.setDefaultAllowUserInteraction",
            "java/net/Authenticator.setDefault", "java/net/ProxySelector.setDefault", "java/net/CookieHandler.setDefault",
            "java/net/ResponseCache.setDefault",
            "javax/net/ssl/HttpsURLConnection.setDefaultSSLSocketFactory", "javax/net/ssl/HttpsURLConnection.setDefaultHostnameVerifier",
            "javax/net/ssl/SSLContext.setDefault",
            "java/security/Security.addProvider", "java/security/Security.insertProviderAt",
            "java/security/Security.removeProvider", "java/security/Security.setProperty",
            "java/util/logging/LogManager.reset", "java/util/logging/LogManager.readConfiguration",
            "java/lang/System.setSecurityManager",
        )

        /** Logging reconfiguration is process-wide too. */
        val JVM_GLOBAL_OWNERS = listOf("org/apache/logging/log4j/core/config/Configurator.")

        private val MUTABLE_EXACT = setOf(
            "java/util/Map", "java/util/HashMap", "java/util/LinkedHashMap", "java/util/TreeMap",
            "java/util/IdentityHashMap", "java/util/WeakHashMap", "java/util/EnumMap", "java/util/SortedMap",
            "java/util/NavigableMap", "java/util/List", "java/util/ArrayList", "java/util/LinkedList",
            "java/util/Set", "java/util/HashSet", "java/util/LinkedHashSet", "java/util/TreeSet", "java/util/EnumSet",
            "java/util/Collection", "java/util/Deque", "java/util/ArrayDeque", "java/util/Queue", "java/util/BitSet",
            "java/util/PriorityQueue", "java/util/Stack", "java/util/Vector", "java/util/Hashtable",
            "java/util/Properties", "java/util/Random", "java/lang/StringBuilder", "java/lang/StringBuffer",
        )

        fun isMutableType(desc: String): Boolean {
            if (desc.startsWith("[")) return true
            val t = desc.removePrefix("L").removeSuffix(";")
            if (t in MUTABLE_EXACT) return true
            if (t.startsWith("java/util/concurrent/")) return !t.endsWith("TimeUnit") && !t.endsWith("Executor") && !t.endsWith("ExecutorService")
            val simple = t.substringAfterLast('/')
            if (t.startsWith("it/unimi/dsi/fastutil/") || t.startsWith("com/google/common/collect/")) {
                return !simple.contains("Immutable") && !simple.contains("Unmodifiable") &&
                    listOf("Map", "List", "Set", "Multimap", "Table", "Queue", "Deque", "Multiset").any { simple.contains(it) }
            }
            return false
        }
    }
}
