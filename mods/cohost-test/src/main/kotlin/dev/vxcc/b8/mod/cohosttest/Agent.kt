package dev.vxcc.b8.mod.cohosttest

/**
 * The static-guard agent (libs/static-guard), if this JVM runs with it: reached by reflection, because
 * it lives in the system class loader (exposed to the game through fabric.systemLibraries).
 */
object Agent {
    private val tenants: Class<*>? = runCatching { Class.forName("dev.vxcc.b8.staticguard.agent.Tenants", false, ClassLoader.getSystemClassLoader()) }.getOrNull()
    private val guard: Class<*>? = runCatching { Class.forName("dev.vxcc.b8.staticguard.agent.Guard", false, ClassLoader.getSystemClassLoader()) }.getOrNull()

    val active get() = tenants != null

    fun bindThread(tenant: Int) {
        tenants?.getMethod("bindThread", Int::class.javaPrimitiveType)?.invoke(null, tenant)
    }

    /** `KIND owner/Name.field tenants a->b` of every violation so far. */
    fun violations(): List<String> {
        val g = guard ?: return emptyList()
        val list = g.getMethod("violations").invoke(null) as List<*>
        return list.map { v ->
            val c = v!!.javaClass
            fun f(n: String) = c.getMethod(n).invoke(v)
            "${f("kind")} ${f("field")} tenants ${f("tenantA")}->${f("tenantB")} on ${f("thread")}"
        }
    }
}
