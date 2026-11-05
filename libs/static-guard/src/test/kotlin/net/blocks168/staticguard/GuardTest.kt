package net.blocks168.staticguard

import net.blocks168.staticguard.agent.Guard
import net.blocks168.staticguard.agent.GuardTransformer
import net.blocks168.staticguard.agent.Tenants
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Loads the fixture classes through [GuardTransformer] in their own class loader, as the agent would. */
class TransformingLoader(private val transformer: GuardTransformer, parent: ClassLoader) : ClassLoader(parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        if (!name.startsWith("net.blocks168.staticguard.fixtures.")) return super.loadClass(name, resolve)
        findLoadedClass(name) ?: run {
            val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.readAllBytes()
            val out = transformer.transform(bytes, this) ?: bytes
            defineClass(name, out, 0, out.size)
        }
    }
}

class GuardTest {
    private val fixtures = "net/blocks168/staticguard/fixtures/"
    private lateinit var loader: TransformingLoader

    private fun call(cls: String, method: String, vararg args: Any?): Any? {
        val c = loader.loadClass("net.blocks168.staticguard.fixtures.$cls")
        val m = c.methods.single { it.name == method }
        return try {
            m.invoke(null, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }

    private fun server(name: String): Any =
        loader.loadClass("net.blocks168.staticguard.fixtures.Server").getConstructor(String::class.java).newInstance(name)

    private fun setUp(gets: GuardTransformer.Gets = GuardTransformer.Gets.ALL) {
        loader = TransformingLoader(GuardTransformer(listOf(fixtures), GuardTransformer.DEFAULT_EXCLUDE, gets), javaClass.classLoader)
    }

    @BeforeTest
    fun reset() = Guard.reset()

    @AfterTest
    fun unbind() = Tenants.unbind()

    @Test
    fun `second tenant writing the server static is a cross write`() {
        setUp()
        call("JavaMod", "onStart", server("bootstrap")) // no tenant: shared, not attributed
        Tenants.runAs(1) { call("JavaMod", "onStart", server("a")) }
        Tenants.runAs(2) { call("JavaMod", "onStart", server("b")) }
        Tenants.runAs(1) { call("JavaMod", "server") }
        val v = Guard.violations().map { it.kind to it.field }.toSet()
        assertTrue("CROSS_WRITE" to "${fixtures}JavaMod.server" in v, v.toString())
        assertTrue("CROSS_READ" to "${fixtures}JavaMod.server" in v, v.toString())
    }

    @Test
    fun `two tenants using one static container is a shared read`() {
        setUp()
        Tenants.runAs(1) { call("JavaMod", "remember", "k", "a") }
        Tenants.runAs(2) { call("JavaMod", "recall", "k") }
        val v = Guard.violations().map { it.kind to it.field }.toSet()
        assertEquals(setOf("SHARED_READ" to "${fixtures}JavaMod.CACHE"), v)
    }

    @Test
    fun `one tenant alone is fine`() {
        setUp()
        Tenants.runAs(1) {
            call("JavaMod", "onStart", server("a"))
            repeat(3) { call("JavaMod", "tick") }
            call("JavaMod", "remember", "k", "a")
        }
        assertEquals(emptyList(), Guard.violations())
    }

    @Test
    fun `threads inherit the tenant of the thread that starts them`() {
        setUp()
        Tenants.runAs(1) { call("JavaMod", "onStart", server("a")) }
        Tenants.runAs(2) {
            val t = Thread { call("JavaMod", "tick"); call("JavaMod", "server") }
            t.start(); t.join()
        }
        assertTrue(Guard.violations().any { it.kind == "CROSS_READ" && it.tenantB == 2 })
    }

    @Test
    fun `strict mode throws at the second tenant's write`() {
        setUp()
        Guard.setStrict(true)
        try {
            Tenants.runAs(1) { call("JavaMod", "tick") }
            assertFailsWith<Guard.SharedStateViolation> { Tenants.runAs(2) { call("JavaMod", "tick") } }
        } finally {
            Guard.setStrict(false)
        }
    }

    @Test
    fun `agent end to end`() {
        val java = ProcessHandle.current().info().command().get()
        val agent = System.getProperty("staticguard.agentJar")
        val report = File.createTempFile("static-guard", ".txt")
        val p = ProcessBuilder(
            java, "-javaagent:$agent=include=$fixtures;report=$report;trace",
            "-cp", System.getProperty("java.class.path"), "net.blocks168.staticguard.fixtures.TwoTenantsMain",
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue(), out)
        assertTrue("CROSS_WRITE ${fixtures}JavaMod.server" in out, out)
        assertTrue("SHARED_READ ${fixtures}JavaMod.CACHE" in out, out)
        val r = report.readText()
        assertTrue("${fixtures}JavaMod.server" in r, r)
        report.delete()
    }
}
