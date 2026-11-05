package net.blocks168.pgecs.lambda

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

data class Email(val address: String, val verifiedAt: Instant? = null, val score: Int = 0, val big: Long = 0)

data class Link(val uuid: UUID)

enum class Rank { GOLD, SILVER, BRONZE }

data class Item(val name: String, val n: Int, val rank: Rank, val tags: List<String>, val r: Rank?)

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
        assertEquals(listOf("Lnet/blocks168/pgecs/lambda/Email;"), l.decompiled.paramTypes)
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

    @Test
    fun `when on a string compiles to a hashCode switch and reads as equalities`() {
        check(
            "(if (arg0.getName() == \"a\") then 1 else (if (arg0.getName() == \"b\") then 2 else (if (arg0.getName() == \"c\") then 2 else " +
                "(if (arg0.getName() == \"d\") then 3 else (if (arg0.getName() == \"e\") then 4 else 0)))))",
            fn<Item, Int> { when (it.name) { "a" -> 1; "b", "c" -> 2; "d" -> 3; "e" -> 4; else -> 0 } },
        )
    }

    @Test
    fun `when on an int`() {
        check(
            "(if (arg0.getN() == 1) then 10 else (if (arg0.getN() == 2) then 20 else (if (arg0.getN() == 3) then 30 else (if (arg0.getN() == 100) then 7 else 0))))",
            fn<Item, Int> { when (it.n) { 1 -> 10; 2 -> 20; 3 -> 30; 100 -> 7; else -> 0 } },
        )
    }

    @Test
    fun `when on an enum reads the switch map`() {
        val l = fn<Item, Int> { when (it.rank) { Rank.GOLD -> 1; Rank.SILVER -> 2; Rank.BRONZE -> 3 } }
        check("(if (arg0.getRank() == const(Rank)) then 1 else (if (arg0.getRank() == const(Rank)) then 2 else 3))", l)
        val outer = l.body as LExpr.Cond
        assertEquals(Rank.GOLD, ((outer.condition as LExpr.Compare).right as LExpr.Const).value)
        check(
            "(if (arg0.getR() != null) then (if (arg0.getR() == const(Rank)) then 1 else 2) else 5)",
            fn<Item, Int> { when (it.r) { Rank.GOLD -> 1; null -> 5; else -> 2 } },
        )
        check("(arg0.getRank() == const(Rank))", pred<Item> { when (it.rank) { Rank.SILVER -> true; else -> false } })
    }

    @Test
    fun `collection literals`() {
        check("listOf(\"a\", \"b\").contains(arg0.getName())", pred<Item> { it.name in listOf("a", "b") })
        check("listOf(\"a\").contains(arg0.getName())", pred<Item> { it.name in listOf("a") })
        check("setOf(\"a\", \"b\", \"c\").contains(arg0.getName())", pred<Item> { it.name in setOf("a", "b", "c") })
        check("!listOf(\"a\", \"b\").contains(arg0.getName())", pred<Item> { it.name !in listOf("a", "b") })
        check("listOf(arg0.getName(), \"x\").contains(\"y\")", pred<Item> { "y" in listOf(it.name, "x") })
        check("ArraysKt.contains(arrayOf(1, 2), arg0.getN())", pred<Item> { it.n in intArrayOf(1, 2) })
    }

    @Test
    fun `a value built in one branch stays per path`() {
        check(
            "(if (arg0.getN() <= 0) then listOf(\"b\").contains(arg0.getName()) else listOf(\"a\").contains(arg0.getName()))",
            fn<Item, Boolean> { it.name in listOf(if (it.n > 0) "a" else "b") },
        )
    }

    @Test
    fun `list fields and regex calls stay calls`() {
        check(
            "(arg0.getTags().contains(\"x\") && ((arg0.getTags().size() > 2) && (arg0.getTags().get(0) == \"y\")))",
            pred<Item> { "x" in it.tags && it.tags.size > 2 && it.tags[0] == "y" },
        )
        check(
            "(new Regex(\"^a.*\").matches(arg0.getName()) && (Regex.find\$default(new Regex(\"d\"), arg0.getName(), 0, 2, null) != null))",
            pred<Item> { Regex("^a.*").matches(it.name) && "d".toRegex().find(it.name) != null },
        )
    }
}
