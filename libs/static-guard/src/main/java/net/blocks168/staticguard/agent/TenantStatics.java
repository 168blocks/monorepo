package net.blocks168.staticguard.agent;

import java.lang.reflect.Array;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Containment: per-tenant copies of selected static fields ("static virtualisation", as in IBM's
 * multi-tenant JVM). {@link GuardTransformer} rewrites, for the fields of the {@code isolate=} list,
 *
 * <pre>
 *   getstatic f          -&gt;  getstatic f; box; ldc id; invokestatic get(Object,int)Object; unbox
 *   value; putstatic f   -&gt;  value; box; getstatic f; box; ldc id; invokestatic put(Object,Object,int)Object; unbox; putstatic f
 * </pre>
 *
 * so the real static stays the value of threads of no tenant (bootstrap) and each tenant has its own
 * slot. A tenant's slot starts as the real value at its first access ({@link Seed#SHARE}), a copy of
 * it ({@link Seed#COPY}: arrays, and {@code clone()} of Cloneable collections), or null/zero
 * ({@link Seed#RESET}). The code stays branch-free, so the transformed class needs no new frames.
 *
 * <p>Only {@code getstatic}/{@code putstatic} are redirected: reflection, {@code VarHandle},
 * {@code Unsafe} and {@code <clinit>} (which runs once, shared) still see the real field.
 */
public final class TenantStatics {
    private TenantStatics() {}

    public enum Seed { SHARE, COPY, RESET }

    private static final Object NULL = new Object();
    private static final Map<Long, Object> slots = new ConcurrentHashMap<>();
    private static final Map<Integer, Seed> seeds = new ConcurrentHashMap<>();

    /** How tenants' copies of {@code owner/Name.field} start (see {@link GuardTransformer#isolate}). */
    static void seed(String field, Seed seed) { seeds.put(Guard.id(field), seed); }

    private static long key(int tenant, int id) { return ((long) tenant << 32) | (id & 0xffffffffL); }

    public static Object get(Object real, int id) {
        int t = Tenants.current();
        if (t == 0) return real;
        Object v = slots.computeIfAbsent(key(t, id), k -> wrap(seed(real, id)));
        return v == NULL ? null : v;
    }

    /** Stores {@code value} in the tenant's slot and returns what the real static should hold afterwards. */
    public static Object put(Object value, Object real, int id) {
        int t = Tenants.current();
        if (t == 0) return value;
        slots.put(key(t, id), wrap(value));
        return real;
    }

    /** Drops a tenant's copies (when its instance stops). */
    public static void forget(int tenant) {
        slots.keySet().removeIf(k -> (int) (k >>> 32) == tenant);
    }

    private static Object wrap(Object v) { return v == null ? NULL : v; }

    private static Object seed(Object real, int id) {
        Seed s = seeds.getOrDefault(id, Seed.SHARE);
        if (real == null || s == Seed.SHARE) return real;
        if (s == Seed.RESET) return zero(real);
        Class<?> c = real.getClass();
        if (c.isArray()) {
            int n = Array.getLength(real);
            Object copy = Array.newInstance(c.getComponentType(), n);
            System.arraycopy(real, 0, copy, 0, n);
            return copy;
        }
        if (real instanceof Cloneable) {
            try {
                return c.getMethod("clone").invoke(real);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // fall through: not publicly cloneable
            }
        }
        System.err.println("[static-guard] cannot copy " + Guard.name(id) + " (" + c.getName() + "), sharing it");
        return real;
    }

    /** Null for references, the boxed zero for primitives (the real value tells which). */
    private static Object zero(Object real) {
        return switch (real) {
            case Integer i -> 0;
            case Long l -> 0L;
            case Boolean b -> false;
            case Double d -> 0.0;
            case Float f -> 0.0f;
            case Short s -> (short) 0;
            case Byte b -> (byte) 0;
            case Character c -> (char) 0;
            default -> null;
        };
    }
}
