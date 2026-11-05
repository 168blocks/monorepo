package dev.vxcc.alexs168.pgecs.lambda

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

data class Email(val address: String, val verifiedAt: Instant? = null, val score: Int = 0, val big: Long = 0)

data class Link(val uuid: UUID)

object Helpers {
    const val LIMIT = 10
}

/**
 * Golden tests: what the Kotlin compiler in use emits for typical query lambdas, decompiled.
 * A change in code shape (a new Kotlin version, a different SAM conversion) shows up here first.
 */
class KotlinGoldenTest {
    private fun <T> pred(p: QueryPredicate<T>): LambdaInstance = LambdaDecompiler.decompile(p)
    private fun <T, R> fn(f: QueryFunction<T, R>): LambdaInstance = LambdaDecompiler.decompile(f)
    private fun <R> sup(f: QuerySupplier<R>): LambdaInstance = LambdaDecompiler.decompile(f)

    private fun check(expected: String, l: LambdaInstance) = assertEquals(expected, l.body.render())

    @Test
    fun `equality with a captured value`() {
        val addr = "aea@example.com"
        val l = pred<Email> { it.address == addr }
        check("(arg0.getAddress() == cap0)", l)
        assertEquals(listOf<Any?>(addr), l.captured)
        assertEquals(listOf("Ldev/vxcc/alexs168/pgecs/lambda/Email;"), l.decompiled.paramTypes)
    }

    @Test
    fun `lowercase on both sides`() {
        val addr = "AEA"
        check(
            "(arg0.getAddress().toLowerCase(const(Locale)) == cap0.toLowerCase(const(Locale)))",
            pred<Email> { it.address.lowercase() == addr.lowercase() },
        )
    }

    @Test
    fun `and, or, not`() {
        check(
            "((arg0.getScore() > 3) && (arg0.getScore() < 10))",
            pred<Email> { it.score > 3 && it.score < 10 },
        )
        check(
            "((arg0.getScore() == 1) || (arg0.getAddress() == \"x\"))",
            pred<Email> { it.score == 1 || it.address == "x" },
        )
        check("(arg0.getAddress() != \"x\")", pred<Email> { it.address != "x" })
        // Kotlin's String.startsWith has a default argument; the core whitelist matches the `$default` form.
        check(
            "!StringsKt.startsWith\$default(arg0.getAddress(), \"a\", false, 2, null)",
            pred<Email> { !it.address.startsWith("a") },
        )
        check(
            "(((arg0.getScore() > 3) && (arg0.getScore() < 10)) || (arg0.getScore() == 42))",
            pred<Email> { (it.score > 3 && it.score < 10) || it.score == 42 },
        )
    }

    @Test
    fun `null checks`() {
        check("(arg0.getVerifiedAt() != null)", pred<Email> { it.verifiedAt != null })
        check("(arg0.getVerifiedAt() == null)", pred<Email> { it.verifiedAt == null })
    }

    @Test
    fun `comparable compareTo becomes a comparison`() {
        val now = Instant.EPOCH
        check("(arg0 < cap0)", pred<Instant> { it < now })
        check("(arg0.getVerifiedAt() >= cap0)", pred<Email> { it.verifiedAt!! >= now })
    }

    @Test
    fun `long comparisons`() {
        val n = 5L
        check("(arg0.getBig() > cap0)", pred<Email> { it.big > n })
        check("(arg0.getBig() == 0L)", pred<Email> { it.big == 0L })
    }

    @Test
    fun `static final constants are inlined`() {
        check("(arg0.getScore() < 10)", pred<Email> { it.score < Helpers.LIMIT })
    }

    @Test
    fun `uuid equality`() {
        val id = UUID.randomUUID()
        check("(arg0.getUuid() == cap0)", pred<Link> { it.uuid == id })
    }

    @Test
    fun `projection and string templates`() {
        check("arg0.getAddress().toLowerCase(const(Locale))", fn<Email, String> { it.address.lowercase() })
        check("concat(\"<\", arg0.getAddress(), \">\")", fn<Email, String> { "<${it.address}>" })
        check("(arg0.getScore() + 1)", fn<Email, Int> { it.score + 1 })
    }

    @Test
    fun `conditional value`() {
        check(
            "(if (arg0.getScore() <= 0) then \"none\" else arg0.getAddress())",
            fn<Email, String> { if (it.score > 0) it.address else "none" },
        )
    }

    @Test
    fun `safe call and elvis`() {
        check(
            "(if (arg0.getVerifiedAt() == null) then 0L else arg0.getVerifiedAt().toEpochMilli())",
            fn<Email, Long> { it.verifiedAt?.toEpochMilli() ?: 0L },
        )
    }

    @Test
    fun `supplier with captures`() {
        val a = "a"
        val b = 2
        check("TuplesKt.to(cap0, cap1)", sup { a to b })
    }

    @Test
    fun `method reference`() {
        check("arg0.getAddress()", fn(Email::address))
    }

    @Test
    fun `decompilation is cached per implementation`() {
        val results = (1..3).map { i -> pred<Email> { it.score == i } }
        assertTrue(results.all { it.decompiled === results[0].decompiled })
        assertEquals(listOf(1, 2, 3), results.map { it.captured.single() })
    }

    @Test
    fun `loops are rejected`() {
        val e = assertFailsWith<UntranslatableLambdaException> {
            pred<Email> {
                var ok = false
                for (c in it.address) if (c == '@') ok = true
                ok
            }
        }
        assertTrue("loops" in e.message!!, e.message)
    }
}

