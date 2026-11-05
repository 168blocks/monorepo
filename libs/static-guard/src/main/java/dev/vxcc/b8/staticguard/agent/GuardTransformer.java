package dev.vxcc.b8.staticguard.agent;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.AccessFlag;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;

/**
 * Inserts the {@link Guard} hooks (and the {@link TenantStatics} redirection for isolated fields) into
 * every class in scope, with the JDK ClassFile API. {@code <clinit>} is left alone: class init runs
 * once and is shared by design.
 */
public final class GuardTransformer implements ClassFileTransformer {
    public enum Gets { ALL, WATCH, NONE }

    public static final List<String> DEFAULT_EXCLUDE = List.of(
        "java/", "javax/", "jdk/", "sun/", "com/sun/", "kotlin/", "kotlinx/",
        "dev/vxcc/b8/staticguard/agent/", "org/objectweb/asm/", "org/spongepowered/asm/",
        "net/fabricmc/loader/", "org/slf4j/", "org/apache/logging/", "io/netty/");

    private static final ClassDesc GUARD = ClassDesc.of(Guard.class.getName());
    private static final ClassDesc TS = ClassDesc.of(TenantStatics.class.getName());
    private static final MethodTypeDesc HOOK = MethodTypeDesc.of(CD_void, CD_int);
    private static final MethodTypeDesc TS_GET = MethodTypeDesc.of(CD_Object, CD_Object, CD_int);
    private static final MethodTypeDesc TS_PUT = MethodTypeDesc.of(CD_Object, CD_Object, CD_Object, CD_int);

    private final List<String> include;
    private final List<String> exclude;
    private final Gets gets;
    private final Set<String> watched = ConcurrentHashMap.newKeySet();
    final AtomicInteger transformed = new AtomicInteger();
    final AtomicInteger failed = new AtomicInteger();
    final Set<String> failedClasses = ConcurrentHashMap.newKeySet();

    public GuardTransformer(List<String> include, List<String> exclude, Gets gets) {
        this.include = include;
        this.exclude = exclude;
        this.gets = gets;
    }

    private final Set<String> isolated = ConcurrentHashMap.newKeySet();

    /** Gives {@code owner/Name.field} per-tenant copies ({@link TenantStatics}) in classes transformed from now on. */
    public void isolate(String field, TenantStatics.Seed seed) {
        TenantStatics.seed(field, seed);
        isolated.add(field);
    }

    public void watch(String field) {
        watched.add(field);
        Guard.watch(field);
    }

    boolean inScope(String internalName) {
        for (String e : exclude) if (internalName.startsWith(e)) return false;
        if (include.isEmpty()) return true;
        for (String i : include) if (internalName.startsWith(i)) return true;
        return false;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain pd, byte[] bytes) {
        if (className == null || !inScope(className)) return null;
        try {
            return transform(bytes, loader);
        } catch (Throwable t) {
            failed.incrementAndGet();
            if (failedClasses.size() < 50) failedClasses.add(className + ": " + t);
            return null;
        }
    }

    /** Transformed bytes, or null if the class has nothing to instrument. */
    public byte[] transform(byte[] bytes, ClassLoader loader) {
        ClassLoader l = loader != null ? loader : ClassLoader.getSystemClassLoader();
        ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
            ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofResourceParsing(l))));
        ClassModel cm = cf.parse(bytes);
        String self = cm.thisClass().asInternalName();
        for (FieldModel f : cm.fields()) {
            if (!f.flags().has(AccessFlag.STATIC)) continue;
            Guard.declare(self + "." + f.fieldName().stringValue(), f.flags().has(AccessFlag.FINAL),
                isMutableType(f.fieldType().stringValue()));
        }
        if (!needsWork(cm)) return null;
        byte[] out = cf.transformClass(cm, ClassTransform.transformingMethodBodies(
            m -> !m.methodName().equalsString("<clinit>"), this::rewrite));
        transformed.incrementAndGet();
        return out;
    }

    private boolean needsWork(ClassModel cm) {
        for (MethodModel m : cm.methods()) {
            if (m.methodName().equalsString("<clinit>")) continue;
            CodeModel code = m.code().orElse(null);
            if (code == null) continue;
            for (CodeElement e : code) {
                if (e instanceof FieldInstruction fi && instrument(fi)) return true;
            }
        }
        return false;
    }

    private boolean instrument(FieldInstruction fi) {
        if (!inScope(fi.owner().asInternalName())) return false;
        if (fi.opcode() == Opcode.PUTSTATIC) return true;
        if (fi.opcode() != Opcode.GETSTATIC) return false;
        String f = field(fi);
        return gets == Gets.ALL || isolated.contains(f) || (gets == Gets.WATCH && watched.contains(f));
    }

    private static String field(FieldInstruction fi) {
        return fi.owner().asInternalName() + "." + fi.name().stringValue();
    }

    private void rewrite(CodeBuilder cob, CodeElement coe) {
        if (!(coe instanceof FieldInstruction fi) || !instrument(fi)) {
            cob.with(coe);
            return;
        }
        String f = field(fi);
        int id = Guard.id(f);
        ClassDesc type = fi.typeSymbol();
        if (isolated.contains(f)) {
            if (fi.opcode() == Opcode.GETSTATIC) {
                cob.with(fi);
                box(cob, type);
                cob.loadConstant(id);
                cob.invokestatic(TS, "get", TS_GET);
                unbox(cob, type);
            } else {
                box(cob, type);
                cob.getstatic(fi.owner().asSymbol(), fi.name().stringValue(), type);
                box(cob, type);
                cob.loadConstant(id);
                cob.invokestatic(TS, "put", TS_PUT);
                unbox(cob, type);
                cob.with(fi);
            }
            return;
        }
        cob.loadConstant(id);
        cob.invokestatic(GUARD, fi.opcode() == Opcode.PUTSTATIC ? "put" : "get", HOOK);
        cob.with(fi);
    }

    private static ClassDesc wrapper(ClassDesc prim) {
        return switch (prim.descriptorString()) {
            case "I" -> ConstantDescs.CD_Integer;
            case "J" -> ConstantDescs.CD_Long;
            case "Z" -> ConstantDescs.CD_Boolean;
            case "D" -> ConstantDescs.CD_Double;
            case "F" -> ConstantDescs.CD_Float;
            case "S" -> ConstantDescs.CD_Short;
            case "B" -> ConstantDescs.CD_Byte;
            case "C" -> ConstantDescs.CD_Character;
            default -> throw new IllegalArgumentException(prim.descriptorString());
        };
    }

    private static void box(CodeBuilder cob, ClassDesc type) {
        if (!type.isPrimitive()) return;
        ClassDesc w = wrapper(type);
        cob.invokestatic(w, "valueOf", MethodTypeDesc.of(w, type));
    }

    private static void unbox(CodeBuilder cob, ClassDesc type) {
        if (!type.isPrimitive()) {
            if (!type.equals(CD_Object)) cob.checkcast(type);
            return;
        }
        ClassDesc w = wrapper(type);
        cob.checkcast(w);
        String name = switch (type.descriptorString()) {
            case "I" -> "intValue";
            case "J" -> "longValue";
            case "Z" -> "booleanValue";
            case "D" -> "doubleValue";
            case "F" -> "floatValue";
            case "S" -> "shortValue";
            case "B" -> "byteValue";
            default -> "charValue";
        };
        cob.invokevirtual(w, name, MethodTypeDesc.of(type));
    }

    /** Same idea as the scanner's: collections, arrays, atomics, builders. */
    static boolean isMutableType(String desc) {
        if (desc.startsWith("[")) return true;
        if (!desc.startsWith("L")) return false;
        String t = desc.substring(1, desc.length() - 1);
        String simple = t.substring(t.lastIndexOf('/') + 1);
        if (t.startsWith("java/util/concurrent/")) return !simple.endsWith("TimeUnit") && !simple.contains("Executor");
        if (t.startsWith("java/util/") || t.startsWith("it/unimi/dsi/fastutil/") || t.startsWith("com/google/common/collect/")) {
            if (simple.contains("Immutable") || simple.contains("Unmodifiable")) return false;
            for (String k : List.of("Map", "List", "Set", "Collection", "Queue", "Deque", "Table", "Multiset", "BitSet", "Stack", "Vector", "Hashtable", "Properties", "Random")) {
                if (simple.contains(k)) return true;
            }
            return false;
        }
        return t.equals("java/lang/StringBuilder") || t.equals("java/lang/StringBuffer");
    }
}
