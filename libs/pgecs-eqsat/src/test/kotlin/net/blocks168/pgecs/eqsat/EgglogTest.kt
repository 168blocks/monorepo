package net.blocks168.pgecs.eqsat

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EgglogTest {
    private val math = """
        (datatype Math (Num i64) (Var String) (Add Math Math) (Mul Math Math))
        (rewrite (Mul a (Num 1)) a)
        (rewrite (Add a (Num 0)) a)
    """.trimIndent()

    private val joinReorder = resource("/join-reorder.egg")
    private val joinPrelude = joinReorder.substringBefore("(let q")
    private val joinQuery = "(let q" + joinReorder.substringAfter("(let q")

    @Test
    fun `extracts the cheapest term`() {
        val egglog = Egglog.create()
        val out = egglog.run("$math\n(let e (Add (Mul (Var \"x\") (Num 1)) (Num 0)))\n(run 5)\n(extract e)")
        assertEquals("(Var \"x\")", out.trim())
    }

    @Test
    fun `errors come back as EgglogException with egglog's message`() {
        val egglog = Egglog.create()
        val e = assertFailsWith<EgglogException> { egglog.run("(extract undefined-thing)") }
        assertContains(e.message!!, "undefined-thing")
        assertFailsWith<EgglogException> { egglog.run("(datatype") }
        // the instance stays usable after errors
        assertEquals("(Num 3)", egglog.run("$math\n(let e (Add (Num 3) (Num 0)))\n(run 2)\n(extract e)").trim())
    }

    @Test
    fun `optimizes a 4-way join with filters into pushed-down scans`() {
        val plan = Egglog.create().run(joinReorder).trim()
        assertTrue(plan.startsWith("(Project \"orders.id\" (Join "), plan)
        assertContains(plan, "(FScan (EqConst \"customer\" \"region\" 3) \"customer\")")
        assertContains(plan, "(FScan (EqConst \"item\" \"kind\" 7) \"item\")")
        assertFalse("Filter" in plan, plan)
    }

    @Test
    fun `session keeps the rules and push-pop isolates queries`() {
        val egglog = Egglog.create()
        val fresh = egglog.run(joinReorder).trim()
        egglog.runInSession(joinPrelude)
        repeat(3) { assertEquals(fresh, egglog.runInSession("(push)\n$joinQuery\n(pop)").trim()) }
        // q was popped
        assertFailsWith<EgglogException> { egglog.runInSession("(extract q)") }
        egglog.resetSession()
        // the rules are gone too
        assertFailsWith<EgglogException> { egglog.runInSession("(let s (Scan \"t\"))") }
    }

    @Test
    fun `pool runs queries concurrently and recovers from failed ones`() {
        val expected = Egglog.create().run(joinReorder).trim()
        val pool = EgglogPool(2, joinPrelude, rebuildEvery = 3)
        assertFailsWith<EgglogException> { pool.query("(push)\n(let q (Scan 1))") } // ill-typed, leaves a push open
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (1..12).map { executor.submit<String> { pool.query(joinQuery).trim() } }.map { it.get(5, TimeUnit.MINUTES) }
            results.forEach { assertEquals(expected, it) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun resource(name: String) = EgglogTest::class.java.getResource(name)!!.readText()
}
