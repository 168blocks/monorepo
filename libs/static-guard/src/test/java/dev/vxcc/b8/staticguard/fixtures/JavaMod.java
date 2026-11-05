package dev.vxcc.b8.staticguard.fixtures;

import java.util.HashMap;
import java.util.Map;

/** The usual global-state patterns of mods, compiled by javac. */
public final class JavaMod {
    private JavaMod() {}

    public static Server server; // the classic: "the" server, set on start
    public static int ticks;
    public static final Map<String, String> CACHE = new HashMap<>();
    public static final Map<String, Integer> LENGTHS = new HashMap<>();
    public static final int[] COUNTS = new int[4];
    public static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();
    public static final Map<String, String> NEVER_MUTATED = new HashMap<>();
    private static Object helper;

    public static void onStart(Server s) { server = s; }

    public static Server server() { return server; }

    public static void tick() { ticks++; }

    public static int ticks() { return ticks; }

    public static void remember(String k, String v) { CACHE.put(k, v); }

    public static String recall(String k) { return CACHE.get(k); }

    public static int length(String s) { return LENGTHS.computeIfAbsent(s, String::length); }

    public static void count(int i) { COUNTS[i] = COUNTS[i] + 1; }

    public static Object helper() {
        if (helper == null) helper = new Object();
        return helper;
    }

    public static void enter(String context) { CONTEXT.set(context); }

    public static void configure() { System.setProperty("javamod.configured", "true"); }

    public static String neverMutated(String k) { return NEVER_MUTATED.get(k); }
}
