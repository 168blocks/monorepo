package net.blocks168.staticguard.scan

/** What kind of process-global state a [Finding] is about. */
enum class Kind(val severity: Severity, val description: String) {
    /** A static field of one of the configured per-instance types (e.g. the server object). */
    INSTANCE_STATIC(Severity.HIGH, "static field holds a per-instance object"),

    /** Calls that act on the whole JVM: exit/halt, default handlers, system properties, ... */
    JVM_GLOBAL(Severity.HIGH, "call with JVM-wide effect"),

    /** `putstatic` outside the owner's `<clinit>`: a static that changes after class init. */
    STATIC_WRITE(Severity.MEDIUM, "static field written after class init"),

    /** A mutating call (put/add/set/...) on an object read straight from a static field. */
    STATIC_CONTAINER_MUTATION(Severity.MEDIUM, "object held in a static field is mutated"),

    /** An element store into an array read straight from a static field. */
    STATIC_ARRAY_STORE(Severity.MEDIUM, "array held in a static field is written"),

    /** `if (f == null) f = ...`: lazy init, harmless when the value does not depend on the instance. */
    LAZY_INIT(Severity.LOW, "lazily initialised static (check the value is instance-independent)"),

    /** `computeIfAbsent` and friends on a static map: memoisation, harmless when the function is pure. */
    STATIC_CACHE(Severity.LOW, "static memoisation cache (check keys/values are instance-independent)"),

    /** Static `ThreadLocal`: per thread, so safe only if no thread serves two instances. */
    THREAD_LOCAL(Severity.LOW, "static ThreadLocal (safe only if threads are not shared between instances)"),

    /** Static field of a mutable type with no mutation found (it may still be mutated elsewhere). */
    MUTABLE_TYPE(Severity.INFO, "static final field of a mutable type"),
}

enum class Severity { HIGH, MEDIUM, LOW, INFO }

/**
 * One finding: [field] (`owner.name:descriptor`, or the called method for [Kind.JVM_GLOBAL]) touched
 * at [site] (`class.method`), found in [origin] (jar, nested jar or directory).
 */
data class Finding(
    val kind: Kind,
    val field: String,
    val site: String,
    val origin: String,
    val mixin: Boolean = false,
)
