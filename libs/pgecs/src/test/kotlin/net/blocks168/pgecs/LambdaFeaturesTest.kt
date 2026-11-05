package net.blocks168.pgecs

import java.util.UUID
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.blocks168.pgecs.db.Database
import net.blocks168.pgecs.dsl.QueryBuildException
import net.blocks168.pgecs.lambda.QueryPredicate
import net.blocks168.pgecs.query.QueryEngineConfig
import net.blocks168.pgecs.testing.TestPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance

@Serializable
enum class Tier { @SerialName("gold") GOLD, SILVER, BRONZE }

@Serializable
data class Player(
    val name: String,
    val level: Int,
    val tier: Tier,
    val tags: List<String> = emptyList(),
    val scores: List<Long> = emptyList(),
    val group: String,
)

object PlayerComponent : Component<Player>("test:lambda:player:v1")

/** `when`, collections and regular expressions in query lambdas, run against PostgreSQL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LambdaFeaturesTest {
    private val database = Database.connect(TestPostgres.config)
    private val ecs = Pgecs.on(database) {
        loadModules = false
        engine = QueryEngineConfig(eqsatInstances = 0)
        register(PlayerComponent)
    }.also { it.freeze() }

    private val group = "g${UUID.randomUUID()}"
    private val ids: Map<String, EntityId> = ecs.tx {
        listOf(
            Player("alice", 1, Tier.GOLD, listOf("admin", "builder"), listOf(10, 20, 30), group),
            Player("bob", 5, Tier.SILVER, listOf("builder"), listOf(7), group),
            Player("Carol", 12, Tier.BRONZE, emptyList(), emptyList(), group),
            Player("dave99", 30, Tier.GOLD, listOf("admin"), listOf(1, 2), group),
        ).associate { p -> p.name to create { this[PlayerComponent] = p } }
    }

    @AfterAll
    fun close() {
        ecs.close()
        database.close()
    }

    private fun names(where: QueryPredicate<Player>): Set<String> {
        val g = group
        return ecs.query {
            val e by entity
            e has PlayerComponent.where { it.group == g }
            e has PlayerComponent.where(where)
            select { e[PlayerComponent].name }
        }.fetch().toSet()
    }

    @Test
    fun `when on strings, ints and enums`() {
        val g = group
        val labels = ecs.query {
            val e by entity
            e has PlayerComponent.where { it.group == g }
            select {
                e[PlayerComponent].name to when (e[PlayerComponent].tier) {
                    Tier.GOLD -> "g"
                    Tier.SILVER -> "s"
                    Tier.BRONZE -> "b"
                }
            }
        }.fetch().toMap()
        assertEquals(mapOf("alice" to "g", "bob" to "s", "Carol" to "b", "dave99" to "g"), labels)
        assertEquals(setOf("alice", "bob"), names { when (it.name) { "alice", "bob" -> true; "x" -> false; else -> false } })
        assertEquals(setOf("bob", "dave99"), names { when (it.level) { 5 -> true; 30 -> true; 99 -> true; else -> false } })
        assertEquals(setOf("Carol"), names { when { it.level > 20 -> false; it.level > 10 -> true; else -> false } })
        assertEquals(setOf("alice", "dave99"), names { when (it.tier) { Tier.GOLD -> true; else -> false } })
    }

    @Test
    fun `in a collection literal or a captured collection`() {
        assertEquals(setOf("alice", "bob"), names { it.name in listOf("alice", "bob", "nobody") })
        assertEquals(setOf("Carol", "dave99"), names { it.name !in setOf("alice", "bob") })
        assertEquals(setOf("bob"), names { it.name in listOf("bob") })
        assertEquals(setOf("alice", "dave99"), names { it.tier in listOf(Tier.GOLD) })
        val wanted = setOf("alice", "Carol")
        assertEquals(wanted, names { it.name in wanted })
        val levels = listOf(5L, 30L)
        assertEquals(setOf("bob", "dave99"), names { it.level.toLong() in levels })
        val tiers = listOf(Tier.SILVER, Tier.BRONZE)
        assertEquals(setOf("bob", "Carol"), names { it.tier in tiers })
        assertEquals(setOf("alice", "dave99"), names { it.tier !in tiers })
        val none = emptyList<String>()
        assertEquals(emptySet(), names { it.name in none })
        // entities in a captured set
        val g = group
        val some = setOf(ids.getValue("bob"), ids.getValue("dave99"))
        val found = ecs.query {
            val e by entity
            e has PlayerComponent.where { it.group == g }
            filter { e in some }
            select { e }
        }.fetch().toSet()
        assertEquals(some, found)
    }

    @Test
    fun `list fields`() {
        assertEquals(setOf("alice", "dave99"), names { "admin" in it.tags })
        assertEquals(setOf("bob", "Carol"), names { "admin" !in it.tags })
        assertEquals(setOf("alice"), names { it.tags.size == 2 })
        assertEquals(setOf("Carol"), names { it.tags.isEmpty() })
        assertEquals(setOf("alice", "bob", "dave99"), names { it.tags.isNotEmpty() })
        assertEquals(setOf("bob"), names { it.tags.size == 1 && it.tags[0] == "builder" })
        assertEquals(setOf("alice", "bob"), names { it.tags.lastOrNull() == "builder" })
        assertEquals(setOf("alice"), names { it.scores.first() == 10L && it.scores.last() == 30L })
        assertEquals(setOf("alice", "dave99"), names { it.scores.getOrNull(1) != null })
        assertEquals(setOf("alice"), names { it.scores[2] > 25 })
        assertEquals(setOf("alice"), names { it.tags.containsAll(listOf("admin", "builder")) })
        val tag = "builder"
        assertEquals(setOf("alice", "bob"), names { it.tags.contains(tag) })
        assertEquals(setOf("alice", "dave99"), names { 2L in it.scores || 20L in it.scores })
    }

    @Test
    fun `regular expressions are PostgreSQL's`() {
        assertEquals(setOf("dave99"), names { Regex("[a-z]+[0-9]+").matches(it.name) })
        assertEquals(setOf("dave99"), names { Regex("[0-9]").containsMatchIn(it.name) })
        assertEquals(setOf("bob", "alice"), names { Regex("^[ab]").find(it.name) != null })
        assertEquals(setOf("Carol", "dave99"), names { Regex("^[ab]").find(it.name) == null })
        assertEquals(setOf("bob"), names { Regex("b.b").matchEntire(it.name) != null })
        assertEquals(setOf("alice", "Carol"), names { it.name.matches(Regex("[a-z]*l[a-z]*", RegexOption.IGNORE_CASE)) })
        assertEquals(setOf("Carol"), names { it.name matches Regex("[A-Z].*") })
        assertEquals(setOf("Carol"), names { "C.r".toRegex().containsMatchIn(it.name) })
        val captured = Regex("o", RegexOption.IGNORE_CASE)
        assertEquals(setOf("bob", "Carol"), names { captured.containsMatchIn(it.name) })
        val embedded = Regex("(?i)CAROL")
        assertEquals(setOf("Carol"), names { embedded.matches(it.name) })
        val pattern = Pattern.compile("^a")
        assertEquals(setOf("alice"), names { pattern.matcher(it.name).find() })
        // PostgreSQL's bracket classes, which java.util.regex does not have
        assertEquals(setOf("dave99"), names { Regex("[[:digit:]]{2}$").containsMatchIn(it.name) })
    }

    @Test
    fun `what stays untranslatable says so`() {
        val e = assertFailsWith<QueryBuildException> { names { Regex("a", RegexOption.MULTILINE).matches(it.name) } }
        assertTrue("IGNORE_CASE" in e.message!!, e.message)
        val f = assertFailsWith<QueryBuildException> { names { it.tags.any { t -> t.length > 3 } } }
        assertTrue("not translatable" in f.message!!, f.message)
    }
}
