package dev.vxcc.b8.staticguard.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which instance ("tenant") the current thread works for. Tenant ids are positive; 0 means the thread
 * belongs to no tenant (bootstrap, shared pools), and its accesses are not attributed.
 *
 * <p>A host binds its threads explicitly ({@link #bind}); threads they start inherit the binding.
 * Without a host, the agent's {@code bind=<regex>=<id>} option binds threads by name.
 */
public final class Tenants {
    private Tenants() {}

    private static final class Binding {
        final int tenant;
        final String name; // thread name the binding was derived from (null: explicit)
        final boolean inherit;
        Binding(int tenant, String name, boolean inherit) { this.tenant = tenant; this.name = name; this.inherit = inherit; }
    }

    private static final InheritableThreadLocal<Binding> CURRENT = new InheritableThreadLocal<>() {
        @Override
        protected Binding childValue(Binding parent) { return parent != null && parent.inherit ? parent : null; }
    };
    private static final List<Pattern> namePatterns = new ArrayList<>();
    private static final List<Integer> nameTenants = new ArrayList<>();
    private static volatile boolean byName;

    /** Binds the current thread (and the threads it starts from now on) to {@code tenant}. */
    public static void bind(int tenant) {
        if (tenant <= 0) throw new IllegalArgumentException("tenant ids are positive: " + tenant);
        CURRENT.set(new Binding(tenant, null, true));
    }

    /**
     * Binds only the current thread: threads it starts are of no tenant. For hosts whose instances
     * start threads that end up shared (pools created lazily by whichever instance comes first).
     */
    public static void bindThread(int tenant) {
        if (tenant <= 0) throw new IllegalArgumentException("tenant ids are positive: " + tenant);
        CURRENT.set(new Binding(tenant, null, false));
    }

    public static void unbind() { CURRENT.remove(); }

    /** Runs {@code r} as {@code tenant} on the current thread. */
    public static void runAs(int tenant, Runnable r) {
        Binding before = CURRENT.get();
        bind(tenant);
        try { r.run(); } finally { if (before == null) CURRENT.remove(); else CURRENT.set(before); }
    }

    /** The current thread's tenant, or 0. */
    public static int current() {
        Binding b = CURRENT.get();
        if (b != null && b.name == null) return b.tenant;
        if (!byName) return b == null ? 0 : b.tenant;
        String name = Thread.currentThread().getName();
        if (b != null && b.name == name) return b.tenant; // identity: same name object, not renamed since
        int t = 0;
        synchronized (namePatterns) {
            for (int i = 0; i < namePatterns.size(); i++) {
                if (namePatterns.get(i).matcher(name).matches()) { t = nameTenants.get(i); break; }
            }
        }
        CURRENT.set(new Binding(t, name, true));
        return t;
    }

    /** Binds threads whose name matches {@code regex} to {@code tenant} (agent option {@code bind}). */
    public static void bindByName(String regex, int tenant) {
        synchronized (namePatterns) {
            namePatterns.add(Pattern.compile(regex));
            nameTenants.add(tenant);
        }
        byName = true;
    }
}
