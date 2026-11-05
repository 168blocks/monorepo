package net.blocks168.staticguard

import net.blocks168.staticguard.fixtures.JavaMod
import net.blocks168.staticguard.fixtures.KotlinMod
import net.blocks168.staticguard.scan.Finding
import net.blocks168.staticguard.scan.Kind
import net.blocks168.staticguard.scan.StaticScanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticScannerTest {
    private val pkg = "net/blocks168/staticguard/fixtures"

    private fun scan(vararg classes: String): List<Finding> {
        val s = StaticScanner(instanceTypes = setOf("$pkg/Server"))
        for (c in classes) {
            val bytes = javaClass.classLoader.getResourceAsStream("$c.class")!!.readAllBytes()
            s.scanClass(bytes, "test")
        }
        return s.result()
    }

    private fun List<Finding>.kinds(fieldPrefix: String) = filter { it.field.startsWith(fieldPrefix) }.map { it.kind }.toSet()

    @Test
    fun `javac patterns`() {
        val f = scan(JavaMod::class.java.name.replace('.', '/'))
        val m = "$pkg/JavaMod."
        assertEquals(setOf(Kind.INSTANCE_STATIC, Kind.STATIC_WRITE), f.kinds("${m}server:"))
        assertEquals(setOf(Kind.STATIC_WRITE), f.kinds("${m}ticks:"))
        assertEquals(setOf(Kind.STATIC_CONTAINER_MUTATION), f.kinds("${m}CACHE:"))
        assertEquals(setOf(Kind.STATIC_CACHE), f.kinds("${m}LENGTHS:"))
        assertEquals(setOf(Kind.STATIC_ARRAY_STORE), f.kinds("${m}COUNTS:"))
        assertEquals(setOf(Kind.THREAD_LOCAL), f.kinds("${m}CONTEXT:"))
        assertEquals(setOf(Kind.MUTABLE_TYPE), f.kinds("${m}NEVER_MUTATED:"))
        assertEquals(setOf(Kind.LAZY_INIT), f.kinds("${m}helper:"))
        assertTrue(f.any { it.kind == Kind.JVM_GLOBAL && it.field == "java/lang/System.setProperty" && it.site == "${m}configure" })
    }

    @Test
    fun `kotlin patterns`() {
        val f = scan(KotlinMod::class.java.name.replace('.', '/'), "$pkg/KotlinModKt")
        assertEquals(setOf(Kind.INSTANCE_STATIC, Kind.STATIC_WRITE), f.kinds("$pkg/KotlinMod.server:"))
        assertEquals(setOf(Kind.STATIC_CONTAINER_MUTATION), f.kinds("$pkg/KotlinMod.players:"))
        assertEquals(setOf(Kind.INSTANCE_STATIC, Kind.STATIC_WRITE), f.kinds("$pkg/KotlinModKt.topLevelServer:"))
        // object INSTANCE is written in <clinit> only
        assertTrue(f.kinds("$pkg/KotlinMod.INSTANCE:").isEmpty())
    }
}
