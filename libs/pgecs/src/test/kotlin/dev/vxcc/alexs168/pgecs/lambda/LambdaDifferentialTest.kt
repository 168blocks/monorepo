package dev.vxcc.alexs168.pgecs.lambda

import dev.vxcc.alexs168.pgecs.Component
import dev.vxcc.alexs168.pgecs.Pgecs
import dev.vxcc.alexs168.pgecs.db.Database
import dev.vxcc.alexs168.pgecs.dsl.QueryBuildException
import dev.vxcc.alexs168.pgecs.query.QueryEngineConfig
import dev.vxcc.alexs168.pgecs.serial.InstantAsEpochMillis
import dev.vxcc.alexs168.pgecs.testing.TestPostgres
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

@Serializable
enum class FuzzTier { @SerialName("gold") GOLD, SILVER, BRONZE }

/** The record the generated predicates (GeneratedLambdas.kt, from src/lambdaFuzzGen) test. */
@Serializable
data class FuzzRec(
    val key: Int,
    val group: String,
    val name: String,
    val alt: String? = null,
    val n: Int,
    /** Near Int.MAX_VALUE or Int.MIN_VALUE: Kotlin's Int arithmetic overflows, SQL's bigint does not. */
    val huge: Int,
    val m: Int? = null,
    val big: Long,
    val flag: Boolean,
    val opt: Boolean? = null,
    val tier: FuzzTier,
    val tags: List<String> = emptyList(),
    val nums: List<Long> = emptyList(),
    @Serializable(InstantAsEpochMillis::class) val at: Instant? = null,
    val ratio: Double,
)

object FuzzRecComponent : Component<FuzzRec>("test:lambda-fuzz:rec:v1")

/** One generated predicate: its number, its source and the compiled lambda. */
class GeneratedLambda(val index: Int, val source: String, val predicate: QueryPredicate<FuzzRec>)

/**
 * Differential test of the lambda decompiler and lowering: each generated predicate runs directly on
 * the Kotlin records and, decompiled and translated, as a query against the same records stored in
 * PostgreSQL; both must select the same records.
 *
 * The predicates are generated at build time (`:libs:pgecs:generateLambdaFuzz`) from
 * `-Ppgecs.lambda.seed` (default 74) and `-Ppgecs.lambda.count`; the records from the same seed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LambdaDifferentialTest {
    private val database = Database.connect(TestPostgres.config)
    private val ecs = Pgecs.on(database) {
        loadModules = false
        engine = QueryEngineConfig(eqsatInstances = 0)
        register(FuzzRecComponent)
    }.also { it.freeze() }

    @AfterAll
    fun close() {
        ecs.close()
        database.close()
    }

    private val names = listOf(
        "ann", "Ann", "ANN", "bob", "b", "", " ann ", "a\tb", "Ärger", "STRASSE", "straße", "éx",
        "😀", "😀x", "ΣΑΣ", "σας", "x1", "nn", "B0b", " n",
    )

    private fun records(group: String, r: Random): List<FuzzRec> = List(80) { k ->
        fun <T> maybe(v: () -> T): T? = if (r.nextInt(3) == 0) null else v()
        FuzzRec(
            key = k,
            group = group,
            name = names.random(r),
            alt = maybe { names.random(r) },
            n = r.nextInt(-5, 13),
            huge = if (r.nextBoolean()) Int.MAX_VALUE - r.nextInt(3) else Int.MIN_VALUE + r.nextInt(3),
            m = maybe { r.nextInt(-3, 8) },
            big = listOf(0L, 1L, -2L, 3L, 5_000_000_000L, Long.MAX_VALUE / 16, -7L).random(r),
            flag = r.nextBoolean(),
            opt = maybe { r.nextBoolean() },
            tier = FuzzTier.entries.random(r),
            tags = List(r.nextInt(4)) { listOf("red", "green", "blue", "").random(r) },
            nums = List(r.nextInt(3)) { listOf(0L, 1L, 3L, -2L, 10L).random(r) },
            at = maybe { listOf(Instant.parse("2001-06-01T00:00:00Z"), Instant.parse("2050-01-01T00:00:00Z"), Instant.parse("2101-01-01T12:00:00.001Z"), Instant.EPOCH).random(r) },
            ratio = listOf(0.0, 0.5, -1.25, 0.1, 1e10, 3.0).random(r),
        )
    }

    private val group = "g${UUID.randomUUID()}"
    private val recs = records(group, Random(GeneratedLambdas.SEED)).also { rs ->
        ecs.tx { rs.forEach { rec -> create { this[FuzzRecComponent] = rec } } }
    }

    private class Outcome(val problems: List<String>, val checked: Int, val tooComplex: Int)

    /** Runs each of [lambdas] on the records in Kotlin and as a query, and lists where they disagree. */
    private fun compare(lambdas: List<GeneratedLambda>): Outcome {
        val problems = ArrayList<String>()
        var checked = 0
        var tooComplex = 0
        val g = group
        for (l in lambdas) {
            val expected = recs.filter { l.predicate.test(it) }.map { it.key }.toSet()
            val actual = try {
                ecs.query {
                    val e by entity
                    e has FuzzRecComponent.where { it.group == g }
                    e has FuzzRecComponent.where(l.predicate)
                    select { e[FuzzRecComponent].key }
                }.fetch().toSet()
            } catch (x: QueryBuildException) {
                // the decompiler's documented limit on branches; the generator stays under it mostly
                if ("too many paths" in x.message.orEmpty()) { tooComplex++; continue }
                problems += "#${l.index} ${l.source}\n    failed: ${x.message?.lines()?.take(12)?.joinToString(" / ")}"
                continue
            } catch (x: Exception) {
                problems += "#${l.index} ${l.source}\n    failed: ${x.javaClass.simpleName}: ${x.message?.lines()?.take(12)?.joinToString(" / ")}"
                continue
            }
            checked++
            if (expected != actual) {
                val diff = (expected - actual).map { "Kotlin only: ${recs[it]}" } + (actual - expected).map { "SQL only: ${recs[it]}" }
                problems += "#${l.index} ${l.source}\n    " + diff.take(3).joinToString("\n    ") + (if (diff.size > 3) "\n    ... ${diff.size} records" else "")
            }
        }
        return Outcome(problems, checked, tooComplex)
    }

    @Test
    fun `decompiled predicates select what the lambdas select`() {
        val all = GeneratedLambdas.all
        val (problems, checked, tooComplex) = compare(all).let { Triple(it.problems, it.checked, it.tooComplex) }
        System.getProperty("pgecs.lambda.report")?.let { java.io.File(it).writeText(problems.joinToString("\n\n")) }
        val kinds = problems.groupingBy { p -> p.substringAfter("\n    ").substringBefore(" in (").take(120) }.eachCount()
        kinds.entries.sortedByDescending { it.value }.take(30).forEach { (k, n) -> println("  $n x $k") }
        println("lambda differential (seed ${GeneratedLambdas.SEED}): ${all.size} predicates, $checked translated, $tooComplex with too many paths, ${problems.size} problems")
        if (problems.isNotEmpty()) {
            fail("${problems.size} of ${all.size} predicates disagree (seed ${GeneratedLambdas.SEED}):\n" + problems.take(40).joinToString("\n"))
        }
    }

    @Test
    fun `a null check guards what follows it, as a smart cast does`() {
        var n = 0
        fun p(source: String, predicate: QueryPredicate<FuzzRec>) = GeneratedLambda(n++, source, predicate)
        val b = "b"
        val y2050 = Instant.parse("2050-01-01T00:00:00Z")
        val lambdas = listOf(
            p("alt != null && alt.length > 2") { it.alt != null && it.alt.length > 2 },
            p("alt == null || alt < b") { it.alt == null || it.alt < b },
            p("m != null && m * 2 >= n") { it.m != null && it.m * 2 >= it.n },
            p("if (alt != null) alt.startsWith(a) else flag") { if (it.alt != null) it.alt.startsWith("a") else it.flag },
            p("(alt ?: x).length > 1") { (it.alt ?: "x").length > 1 },
            p("(alt ?: name) > name") { (it.alt ?: it.name) > it.name },
            p("template of a guarded alt") { it.alt != null && "${it.alt}!" == "bob!" },
            p("at != null && at < 2050") { it.at != null && it.at < y2050 },
            p("tags.firstOrNull() != null && tags.first().length == 0") { it.tags.firstOrNull() != null && it.tags.first().isEmpty() },
            p("!(alt != null && alt.contains(n))") { !(it.alt != null && it.alt.contains("n")) },
        )
        val outcome = compare(lambdas)
        if (outcome.problems.isNotEmpty()) fail(outcome.problems.joinToString("\n"))
    }
}
