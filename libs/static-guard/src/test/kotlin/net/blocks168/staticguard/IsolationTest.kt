package net.blocks168.staticguard

import net.blocks168.staticguard.agent.Guard
import net.blocks168.staticguard.agent.GuardTransformer
import net.blocks168.staticguard.agent.TenantStatics
import net.blocks168.staticguard.agent.Tenants
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Per-tenant copies of statics ([TenantStatics]). */
class IsolationTest {
    private val fixtures = "net/blocks168/staticguard/fixtures/"

    @AfterTest
    fun cleanup() {
        Tenants.unbind()
        TenantStatics.forget(1)
        TenantStatics.forget(2)
        Guard.reset()
    }

    @Test
    fun `isolated statics are per tenant, bootstrap keeps the real field`() {
        val t = GuardTransformer(listOf(fixtures), GuardTransformer.DEFAULT_EXCLUDE, GuardTransformer.Gets.NONE)
        t.isolate("${fixtures}JavaMod.server", TenantStatics.Seed.SHARE)
        t.isolate("${fixtures}JavaMod.ticks", TenantStatics.Seed.RESET)
        t.isolate("${fixtures}JavaMod.CACHE", TenantStatics.Seed.COPY)
        val loader = TransformingLoader(t, javaClass.classLoader)
        val c = loader.loadClass("net.blocks168.staticguard.fixtures.JavaMod")
        val server = loader.loadClass("net.blocks168.staticguard.fixtures.Server").getConstructor(String::class.java)
        fun call(m: String, vararg a: Any?) = c.methods.single { it.name == m }.invoke(null, *a)

        call("onStart", server.newInstance("bootstrap"))
        call("tick")
        call("remember", "shared", "yes") // registered during bootstrap: every tenant's copy has it
        Tenants.runAs(1) {
            assertEquals("Server[name=bootstrap]", call("server").toString()) // SHARE: starts as the real value
            call("onStart", server.newInstance("a"))
            repeat(5) { call("tick") }
            call("remember", "k", "a")
        }
        Tenants.runAs(2) {
            call("onStart", server.newInstance("b"))
            call("tick")
            call("remember", "k", "b")
        }
        Tenants.runAs(1) {
            assertEquals("Server[name=a]", call("server").toString())
            assertEquals(5, call("ticks"))
            assertEquals("a", call("recall", "k"))
            assertEquals("yes", call("recall", "shared"))
        }
        Tenants.runAs(2) {
            assertEquals("Server[name=b]", call("server").toString())
            assertEquals("b", call("recall", "k"))
        }
        // threads of no tenant see the real statics, untouched by the tenants
        assertEquals("Server[name=bootstrap]", call("server").toString())
        assertEquals(1, c.getField("ticks").getInt(null))
        assertEquals(null, call("recall", "k"))
    }
}
