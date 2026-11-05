package dev.vxcc.alexs168.pgecs.query

import dev.vxcc.alexs168.pgecs.ir.Canonical
import dev.vxcc.alexs168.pgecs.ir.QueryIr
import dev.vxcc.alexs168.pgecs.ir.parameterize
import dev.vxcc.alexs168.pgecs.query.sql.Mode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Differential and metamorphic testing of the query engine on PostgreSQL 18.
 *
 * Random queries ([FuzzGen]) over random datasets ([FuzzData]) run on the engine with and without
 * equality saturation, and must return what the reference [Oracle] returns. On top of that, for
 * each query:
 *
 * - the canonical text parses back to the same query;
 * - the same shape with new arguments (a plan-cache hit, possibly with other parameters null)
 *   agrees with the oracle too;
 * - [Mode.COUNT] counts the rows;
 * - ternary logic partitioning: the rows of `q` are the rows of `q ∧ p` plus those of `q ∧ ¬p`, for a
 *   random predicate `p`. This needs no oracle, so it also catches what the oracle and the engine
 *   get wrong the same way (SQL's three-valued logic leaking through, mostly).
 *
 * A failing query is shrunk ([FuzzShrink]) before it is reported, and the report says how to rerun
 * exactly that case.
 *
 * Seeded and deterministic. System properties (Gradle properties of the same names are passed on,
 * `-Ppgecs.fuzz.queries=100000`):
 *
 * | Property | Default | |
 * | --- | --- | --- |
 * | `pgecs.fuzz.seed` | 20261105 | a number, or `random` |
 * | `pgecs.fuzz.datasets` | 4 | random datasets, each in its own schema |
 * | `pgecs.fuzz.queries` | 1500 | valid queries in total, spread over the datasets |
 * | `pgecs.fuzz.case` | | `<dataset>:<query>`, run only that case (from a failure report) |
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RandomQueryTest {
    private val seed: Long = System.getProperty("pgecs.fuzz.seed").let {
        when (it) {
            null, "" -> 20261105L
            "random" -> System.nanoTime()
            else -> it.toLong()
        }
    }
    private val datasets = System.getProperty("pgecs.fuzz.datasets")?.toInt() ?: 4
    private val queries = System.getProperty("pgecs.fuzz.queries")?.toInt() ?: 1500
    private val only: Pair<Int, Int>? = System.getProperty("pgecs.fuzz.case")?.takeIf { it.isNotBlank() }?.split(':')?.let { it[0].toInt() to it[1].toInt() }

    private lateinit var withEqsat: QueryEngine
    private lateinit var withoutEqsat: QueryEngine
    private val engines get() = listOf("eqsat" to withEqsat, "simplifier" to withoutEqsat)

    @BeforeAll
    fun setUp() {
        withEqsat = QueryEngine(QueryEngineConfig(eqsatTimeout = Duration.ofSeconds(30)))
        withoutEqsat = QueryEngine(QueryEngineConfig(eqsatInstances = 0))
    }

    @AfterAll
    fun tearDown() {
        withEqsat.close()
        withoutEqsat.close()
    }

    private fun mix(vararg xs: Long): Long = xs.fold(0x9E3779B97F4A7C15uL.toLong()) { h, x -> (h xor x) * -0x40a7b892e31b1a47L }.let { it xor (it ushr 31) }

    private class Stats {
        var generated = 0
        var invalid = 0
        var checked = 0
        var partitioned = 0
        var aggregate = 0
        var ordered = 0
        var paged = 0
        var optional = 0
        var rows = 0L
    }

    @Test
    fun `random queries agree with the reference evaluator and with themselves`() {
        val stats = Stats()
        val perDataset = (queries + datasets - 1) / datasets
        for (d in 0 until datasets) {
            if (only != null && only.first != d) continue
            FuzzData(mix(seed, d.toLong())).use { data ->
                withEqsat.refreshStats(data.db.connection, force = true)
                // use counts per shape are kept until HotShapes flushes them; a real process has few shapes
                engines.forEach { (_, e) -> e.uses.clear() }
                var attempts = 0
                var valid = 0
                while (valid < perDataset && attempts < perDataset * 20) {
                    val i = attempts++
                    if (only != null && only.second != i) continue
                    stats.generated++
                    val case = try {
                        FuzzGen(Random(mix(seed, d.toLong(), i.toLong())), data.entities).query()
                    } catch (_: IllegalArgumentException) {
                        // IrValidationException included: the generator does not track every rule
                        stats.invalid++
                        continue
                    }
                    valid++
                    stats.checked++
                    if (case.query.isAggregate) stats.aggregate++
                    if (case.query.orderBy.isNotEmpty()) stats.ordered++
                    if (case.query.limit != null || case.query.offset != null) stats.paged++
                    if (case.query.terms.any { it is dev.vxcc.alexs168.pgecs.ir.Term.Optional }) stats.optional++
                    if (case.partition != null) stats.partitioned++
                    check(data, d, i, case, stats)
                }
                assertTrue(only != null || valid == perDataset, "dataset $d: only $valid valid queries in $attempts attempts")
            }
        }
        println(
            "seed $seed: checked ${stats.checked} random queries (${stats.generated} generated, ${stats.invalid} invalid), " +
                "${stats.aggregate} aggregate, ${stats.ordered} ordered, ${stats.paged} paged, ${stats.optional} with optional groups, " +
                "${stats.partitioned} partitioned; ${stats.rows} result rows; eqsat engine: ${withEqsat.metrics()}",
        )
    }

    private fun check(data: FuzzData, d: Int, i: Int, case: FuzzCase, stats: Stats) {
        val repro = "reproduce with -Ppgecs.fuzz.seed=$seed -Ppgecs.fuzz.datasets=$datasets -Ppgecs.fuzz.queries=$queries -Ppgecs.fuzz.case=$d:$i"
        fun failShrunk(what: String, q: QueryIr, args: List<Any?>, message: String): Nothing {
            val (small, msg) = FuzzShrink.shrink(FuzzShrink.Candidate(q, args)) { c -> agree(data, c.query, c.args) }
            fail(
                "$what\n$message\n\nshrunk to:\n${msg ?: "(no longer fails)"}\n${describe(small.query, small.args)}\n\n" +
                    "original:\n${describe(q, args)}\n\n$repro",
            )
        }

        agree(data, case.query, case.args, stats)?.let { failShrunk("wrong result", case.query, case.args, it) }

        val again = case.rebind(Random(mix(seed, d.toLong(), i.toLong(), 1)))
        agree(data, case.query, again)?.let { failShrunk("wrong result with other arguments for a cached shape", case.query, again, it) }

        val p = case.partition ?: return
        for (q in listOf(p.whenTrue, p.whenFalse)) {
            agree(data, q, p.args)?.let { failShrunk("wrong result for a partition", q, p.args, it) }
        }
        for ((name, engine) in engines) {
            val all = normalize(run(engine, data, case.query, case.args)).sorted()
            val parts = normalize(run(engine, data, p.whenTrue, p.args) + run(engine, data, p.whenFalse, p.args)).sorted()
            val same = if (case.query.distinct) all.toSet() == parts.toSet() else all == parts
            if (!same) {
                fail(
                    "[$name] partitioning: q has ${all.size} rows, q ∧ p and q ∧ ¬p ${parts.size}\n" +
                        "q:\n${describe(case.query, case.args)}\nq ∧ p:\n${describe(p.whenTrue, p.args)}\nq ∧ ¬p:\n${describe(p.whenFalse, p.args)}\n$repro",
                )
            }
        }
    }

    private fun run(engine: QueryEngine, data: FuzzData, q: QueryIr, args: List<Any?>, mode: Mode = Mode.FETCH): List<List<Any?>> =
        engine.execute(data.db.connection, q, args, mode).map { it.toList() }

    /** Null if the engines agree with the oracle on [q], else what went wrong. */
    private fun agree(data: FuzzData, q: QueryIr, args: List<Any?>, stats: Stats? = null): String? {
        val parsed = runCatching { Canonical.parse(q.canonicalText) }
        if (parsed.getOrNull() != q) return "canonical text does not parse back: ${parsed.exceptionOrNull() ?: parsed.getOrNull()?.canonicalText}"
        val expected = try {
            data.oracle.run(q, args)
        } catch (e: Exception) {
            return "the oracle failed: $e"
        }
        stats?.let { it.rows += expected.size }
        for ((name, engine) in engines) {
            val actual = try {
                run(engine, data, q, args)
            } catch (e: Exception) {
                return "[$name] failed: $e"
            }
            mismatch(q, expected, actual)?.let { return "[$name] $it" }
        }
        val count = try {
            run(withEqsat, data, q, args, Mode.COUNT).single()[0]
        } catch (e: Exception) {
            return "[eqsat] count failed: $e"
        }
        if (count != expected.size.toLong()) return "[eqsat] count is $count, expected ${expected.size}"
        return null
    }

    private fun mismatch(q: QueryIr, expected: List<List<Any?>>, actual: List<List<Any?>>): String? {
        val wrong = "wrong rows\nexpected ${show(expected)}\nactual   ${show(actual)}"
        val keyCols = q.orderBy.mapNotNull { k -> q.select.indexOfFirst { it.expr == k.expr }.takeIf { it >= 0 } }.distinct()
        val e = normalize(expected)
        val a = normalize(actual)
        if (q.limit != null || q.offset != null) {
            // the generator orders by every column when paging, so the rows are determined; a shrunk
            // query may not be, and then only the number of rows is
            return when {
                keyCols.size == q.select.size -> if (e != a) wrong else null
                else -> if (e.size != a.size) wrong else null
            }
        }
        if (e.sorted() != a.sorted()) return wrong
        if (keyCols.isNotEmpty()) {
            val ek = expected.map { r -> keyCols.joinToString("|") { show(r[it]) } }
            val ak = actual.map { r -> keyCols.joinToString("|") { show(r[it]) } }
            if (ek != ak) return "wrong order\nexpected ${show(expected)}\nactual   ${show(actual)}"
        }
        return null
    }

    private fun normalize(rows: List<List<Any?>>): List<String> = rows.map { r -> r.joinToString("|") { show(it) } }

    private fun show(v: Any?): String = when (v) {
        is Double -> "%.6f".format(v)
        is String -> Canonical.quote(v)
        is List<*> -> v.joinToString(", ", "[", "]") { show(it) }
        else -> v.toString()
    }

    private fun describe(q: QueryIr, args: List<Any?>): String {
        val bound = q.parameterize(args)
        val nulls = bound.arguments.indices.filter { bound.arguments[it] == null }.toSet()
        val sql = runCatching { withEqsat.compile(bound.shape, nulls).toString() }.getOrElse { "compile failed: $it" }
        return "${q.canonicalText}\nargs ${show(args)}\n$sql"
    }
}
