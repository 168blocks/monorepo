package net.blocks168.staticguard.agent;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Runtime side of the instrumentation: {@link GuardTransformer} inserts {@code Guard.put(id)} before
 * {@code putstatic} and {@code Guard.get(id)} before {@code getstatic} of watched fields.
 *
 * <p>Per field it remembers the last tenant that wrote it (writes from threads of no tenant, such as
 * bootstrap, are shared on purpose and not attributed) and the first tenant that read it, and reports:
 * <ul>
 *   <li>{@code CROSS_WRITE}: two tenants wrote the field (one of them sees the other's value);</li>
 *   <li>{@code CROSS_READ}: a tenant read a field another tenant wrote;</li>
 *   <li>{@code SHARED_READ}: two tenants read a watched field (a static container both use).</li>
 * </ul>
 * In {@code strict} mode a {@code CROSS_WRITE} throws {@link SharedStateViolation} at the write.
 *
 * <p>Trace mode (agent option {@code trace}) also counts every write, with up to a few thread names
 * per field, split at {@code sealAfterMs} into startup and runtime writes: for a single instance it
 * shows which statics change while it runs, which is what would leak between co-hosted instances.
 */
public final class Guard {
    private Guard() {}

    static final int MAX_FIELDS = 1 << 20;
    private static final Map<String, Integer> ids = new ConcurrentHashMap<>();
    private static final List<String> names = new ArrayList<>();

    private static final AtomicIntegerArray writer = new AtomicIntegerArray(MAX_FIELDS); // 0 none, >0 tenant
    private static final AtomicIntegerArray reader = new AtomicIntegerArray(MAX_FIELDS);
    private static final AtomicIntegerArray reported = new AtomicIntegerArray(MAX_FIELDS); // bit per report kind
    /** 0: unknown (tracked), 1: final and not a container (reads ignored), 2: tracked, 3: tracked container (also SHARED_READ). */
    private static final byte[] readMode = new byte[MAX_FIELDS];

    static volatile boolean strict;
    static volatile boolean trace;
    static volatile long sealAt = Long.MAX_VALUE; // System.nanoTime() after which writes are "runtime"
    static volatile boolean printViolations = true;

    private static final AtomicLongArray startupWrites = new AtomicLongArray(MAX_FIELDS);
    private static final AtomicLongArray runtimeWrites = new AtomicLongArray(MAX_FIELDS);
    private static final Map<Integer, Set<String>> writerThreads = new ConcurrentHashMap<>();

    public record Violation(String kind, String field, int tenantA, int tenantB, String thread, String stack) {}
    private static final List<Violation> violations = new ArrayList<>();

    /** Thrown in strict mode. */
    public static final class SharedStateViolation extends IllegalStateException {
        SharedStateViolation(String m) { super(m); }
    }

    /** Field id for {@code owner.name} (internal owner name). Called at transform time. */
    public static int id(String field) {
        return ids.computeIfAbsent(field, f -> {
            synchronized (names) {
                if (names.size() >= MAX_FIELDS) return -1;
                names.add(f);
                return names.size() - 1;
            }
        });
    }

    static String name(int id) { synchronized (names) { return names.get(id); } }

    /** Called when the owner class is transformed: reads of final non-container fields are not tracked. */
    static void declare(String field, boolean isFinal, boolean container) {
        int id = id(field);
        if (id >= 0 && readMode[id] == 0) readMode[id] = (byte) (container ? 3 : isFinal ? 1 : 2);
    }

    static void watch(String field) {
        int id = id(field);
        if (id >= 0) readMode[id] = 3;
    }

    public static void put(int id) {
        if (id < 0) return;
        if (trace) countWrite(id);
        int t = Tenants.current();
        if (t == 0) return;
        int w = writer.get(id);
        if (w == 0 && writer.compareAndSet(id, 0, t)) return;
        w = writer.get(id);
        if (w != t) {
            violation(id, 1, "CROSS_WRITE", w, t, strict); // throws in strict mode, before the write
            writer.set(id, t); // the last writer owns the value now: its readers are checked against it
        }
    }

    public static void get(int id) {
        if (id < 0 || readMode[id] == 1) return;
        int t = Tenants.current();
        if (t == 0) return;
        int w = writer.get(id);
        if (w != 0 && w != t) violation(id, 2, "CROSS_READ", w, t, false);
        int r = reader.get(id);
        if (r == 0) reader.compareAndSet(id, 0, t);
        else if (r != t && readMode[id] == 3) violation(id, 4, "SHARED_READ", r, t, false);
    }

    private static void countWrite(int id) {
        boolean runtime = System.nanoTime() >= sealAt;
        (runtime ? runtimeWrites : startupWrites).incrementAndGet(id);
        Set<String> threads = writerThreads.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
        if (threads.size() < 4) threads.add((runtime ? "" : "startup:") + Thread.currentThread().getName());
    }

    private static void violation(int id, int bit, String kind, int a, int b, boolean fail) {
        String msg = kind + " " + name(id) + ": tenant " + a + " then tenant " + b + " (" + Thread.currentThread().getName() + ")";
        int prev = reported.getAndUpdate(id, x -> x | bit);
        if ((prev & bit) == 0) {
            StringWriter sw = new StringWriter();
            new Throwable(msg).printStackTrace(new PrintWriter(sw));
            synchronized (violations) {
                violations.add(new Violation(kind, name(id), a, b, Thread.currentThread().getName(), sw.toString()));
            }
            if (printViolations) System.err.println("[static-guard] " + msg);
        }
        if (fail) throw new SharedStateViolation(msg);
    }

    public static List<Violation> violations() { synchronized (violations) { return List.copyOf(violations); } }

    /** Forgets all ownership and reports (tests, or a host between runs). */
    public static void reset() {
        for (int i = 0; i < names.size(); i++) { writer.set(i, 0); reader.set(i, 0); reported.set(i, 0); startupWrites.set(i, 0); runtimeWrites.set(i, 0); }
        writerThreads.clear();
        synchronized (violations) { violations.clear(); }
    }

    /** Strict mode: a write by a second tenant throws {@link SharedStateViolation}. */
    public static void setStrict(boolean on) { strict = on; }

    /** Marks the end of startup: writes from now on count as runtime writes in the trace. */
    public static void seal() { sealAt = System.nanoTime(); }

    public static String report() {
        StringBuilder sb = new StringBuilder();
        List<Violation> vs = violations();
        sb.append("static-guard: ").append(names.size()).append(" fields instrumented, ").append(vs.size()).append(" violations\n");
        for (Violation v : vs) {
            sb.append(v.kind()).append(' ').append(v.field()).append(" tenants ").append(v.tenantA()).append("->").append(v.tenantB())
              .append(" on ").append(v.thread()).append('\n');
            v.stack().lines().skip(2).limit(8).forEach(l -> sb.append("    ").append(l.trim()).append('\n'));
        }
        if (trace) {
            int n;
            synchronized (names) { n = names.size(); }
            List<Integer> written = new ArrayList<>();
            for (int i = 0; i < n; i++) if (startupWrites.get(i) + runtimeWrites.get(i) > 0) written.add(i);
            written.sort(Comparator.comparingLong((Integer i) -> -runtimeWrites.get(i)).thenComparing(Guard::name));
            long rt = written.stream().filter(i -> runtimeWrites.get(i) > 0).count();
            sb.append("\nstatic writes (outside the owner's <clinit>): ").append(written.size()).append(" fields, ")
              .append(rt).append(" written after the seal\n");
            sb.append(String.format("%12s %12s  %s  %s%n", "runtime", "startup", "field", "threads"));
            for (int i : written) {
                sb.append(String.format("%12d %12d  %s  %s%n", runtimeWrites.get(i), startupWrites.get(i), name(i),
                    new LinkedHashSet<>(writerThreads.getOrDefault(i, Set.of()))));
            }
        }
        return sb.toString();
    }
}
