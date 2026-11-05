package net.blocks168.pgecs.query

import net.blocks168.pgecs.ir.AggregateFn
import net.blocks168.pgecs.ir.AnyEntity
import net.blocks168.pgecs.ir.AnyRelation
import net.blocks168.pgecs.ir.CompareOp
import net.blocks168.pgecs.ir.ComponentKind
import net.blocks168.pgecs.ir.ComponentRef
import net.blocks168.pgecs.ir.EntityRef
import net.blocks168.pgecs.ir.EntityVar
import net.blocks168.pgecs.ir.Expr
import net.blocks168.pgecs.ir.Functions
import net.blocks168.pgecs.ir.IrBuilder
import net.blocks168.pgecs.ir.IrValidationException
import net.blocks168.pgecs.ir.QueryIr
import net.blocks168.pgecs.ir.SortKey
import net.blocks168.pgecs.ir.TermsBuilder
import net.blocks168.pgecs.ir.ValueType
import net.blocks168.pgecs.ir.ValueVar
import net.blocks168.pgecs.ir.parameterize
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Random queries, run by the engine on PostgreSQL 18 (with and without equality saturation) and by
 * the reference [Oracle], must return the same rows. Seeded, so a failure reproduces; the message
 * names the query and its SQL.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RandomQueryTest {
    private val tags = listOf("t:a", "t:b", "t:c").map { ComponentRef("b8:$it", ComponentKind.TAG) }
    private val rels = listOf("r:r", "r:q").map { ComponentRef("b8:$it", ComponentKind.RELATION) }
    private val score = ComponentRef("b8:v:score", ComponentKind.VALUE, ValueType.INT)
    private val json = ComponentRef("b8:v:json", ComponentKind.VALUE, ValueType.JSON)
    private val names = listOf("ann", "Ann", "bob", "bobby", "c")

    private lateinit var db: TestDb
    private lateinit var oracle: Oracle
    private lateinit var entities: List<UUID>
    private lateinit var withEqsat: QueryEngine
    private lateinit var withoutEqsat: QueryEngine

    @BeforeAll
    fun setUp() {
        db = TestDb("random")
        tags.forEach { db.type(it.name) }
        rels.forEach { db.type(it.name, "relation") }
        db.type(score.name, "value")
        db.type(json.name, "value")
        val rnd = Random(168)
        entities = List(8) { db.entity() }
        val rows = mutableListOf<Oracle.Row>()
        val nil = UUID(0, 0)
        for (e in entities) {
            for (t in tags) if (rnd.nextInt(2) == 0) { db.add(e, t.name); rows += Oracle.Row(e, t.name, nil, null) }
            for (r in rels) for (t in entities) if (rnd.nextInt(5) == 0) { db.add(e, r.name, t); rows += Oracle.Row(e, r.name, t, null) }
            if (rnd.nextInt(3) != 0) {
                val s = rnd.nextLong(-3, 4)
                db.add(e, score.name, json = "$s")
                rows += Oracle.Row(e, score.name, nil, s)
            }
            if (rnd.nextInt(3) != 0) {
                val name = names.random(rnd)
                val n: Long? = if (rnd.nextInt(3) == 0) null else rnd.nextLong(0, 3)
                val missing = n == null && rnd.nextBoolean()
                val text = if (missing) """{"name":"$name"}""" else """{"name":"$name","n":${n ?: "null"}}"""
                db.add(e, json.name, json = text)
                rows += Oracle.Row(e, json.name, nil, if (missing) mapOf("name" to name) else mapOf("name" to name, "n" to n))
            }
        }
        db.exec("analyze")
        oracle = Oracle(rows)
        withEqsat = QueryEngine(QueryEngineConfig(eqsatTimeout = Duration.ofSeconds(30)))
        withoutEqsat = QueryEngine(QueryEngineConfig(eqsatInstances = 0))
    }

    @AfterAll
    fun tearDown() {
        withEqsat.close()
        withoutEqsat.close()
        db.close()
    }

    /** One generated query with its argument values. */
    private class Case(val query: QueryIr, val args: List<Any?>)

    private inner class Gen(val rnd: Random) {
        val args = mutableListOf<Any?>()
        lateinit var b: IrBuilder
        val bound = mutableListOf<EntityVar>()
        val scores = mutableListOf<ValueVar>()
        val jsons = mutableListOf<ValueVar>()
        var vars = 0

        fun param(type: ValueType, value: Any?): Expr.Param = b.param(type).also { args += value }

        fun entityRef(allowNew: Boolean, allowAny: Boolean = true): EntityRef = when (rnd.nextInt(10)) {
            in 0..4 -> if (allowNew || bound.isEmpty()) b.entity("e${vars++}").also { bound += it } else bound.random(rnd)
            in 5..6 -> if (bound.isNotEmpty()) bound.random(rnd) else b.entity("e${vars++}").also { bound += it }
            7 -> if (allowAny) AnyEntity else bound.randomOrNull(rnd) ?: b.entity("e${vars++}").also { bound += it }
            else -> param(ValueType.UUID, entities.random(rnd))
        }

        /** A match into [t]; binds variables into [bound]. */
        fun match(t: TermsBuilder, allowNew: Boolean) {
            when (rnd.nextInt(7)) {
                0, 1 -> t.match(entityRef(allowNew, allowAny = false), tags.random(rnd))
                2, 3 -> t.match(entityRef(allowNew), rels.random(rnd), target = entityRef(allowNew))
                4 -> t.match(entityRef(allowNew, allowAny = false), AnyRelation(), target = entityRef(allowNew, allowAny = false))
                5 -> {
                    val v = b.value("s${vars++}", ValueType.INT)
                    t.match(entityRef(allowNew, allowAny = false), score, value = v)
                    scores += v
                }
                else -> {
                    val v = b.value("j${vars++}", ValueType.JSON)
                    t.match(entityRef(allowNew, allowAny = false), json, value = v)
                    jsons += v
                }
            }
        }

        fun pred(depth: Int): Expr = if (depth > 4) Expr.Literal.of(rnd.nextBoolean()) else when (if (depth > 2) rnd.nextInt(5) else rnd.nextInt(9)) {
            0 -> if (scores.isNotEmpty()) {
                Expr.Compare(CompareOp.entries.random(rnd), Expr.Var(scores.random(rnd)), Expr.Literal.of(rnd.nextLong(-2, 3)))
            } else {
                pred(depth + 1)
            }
            1 -> if (jsons.isNotEmpty()) {
                val name = Expr.Field(jsons.random(rnd), listOf("name"), ValueType.TEXT.asNullable())
                when (rnd.nextInt(3)) {
                    0 -> Expr.Compare(CompareOp.EQ, name, Expr.Literal.of(names.random(rnd)))
                    1 -> Expr.Compare(CompareOp.EQ, Functions.call(Functions.LOWERCASE, name), param(ValueType.TEXT, names.random(rnd).lowercase()))
                    else -> Expr.Compare(CompareOp.NE, name, param(ValueType.TEXT.asNullable(), if (rnd.nextBoolean()) null else names.random(rnd)))
                }
            } else {
                pred(depth + 1)
            }
            2 -> if (jsons.isNotEmpty()) {
                val n = Expr.Field(jsons.random(rnd), listOf("n"), ValueType.INT.asNullable())
                when (rnd.nextInt(3)) {
                    0 -> Expr.IsNull(n)
                    1 -> Expr.Compare(if (rnd.nextBoolean()) CompareOp.EQ else CompareOp.NE, n, param(ValueType.INT.asNullable(), if (rnd.nextBoolean()) null else rnd.nextLong(0, 3)))
                    else -> Expr.Compare(CompareOp.EQ, Expr.Coalesce(listOf(n, Expr.Literal.of(-1L))), Expr.Literal.of(rnd.nextLong(-1, 3)))
                }
            } else {
                pred(depth + 1)
            }
            3 -> if (bound.size >= 2) {
                Expr.Compare(if (rnd.nextBoolean()) CompareOp.EQ else CompareOp.NE, Expr.Var(bound.random(rnd)), Expr.Var(bound.random(rnd)))
            } else {
                pred(depth + 1)
            }
            4 -> if (bound.isNotEmpty()) existsProbe() else pred(depth + 1)
            5 -> Expr.Not(pred(depth + 1))
            6 -> Expr.And(listOf(pred(depth + 1), pred(depth + 1)))
            7 -> Expr.Or(listOf(pred(depth + 1), pred(depth + 1)))
            else -> if (bound.isNotEmpty()) existsChild() else pred(depth + 1)
        }

        /** `x has T` or `x has (R, y)` with only bound variables. */
        fun existsProbe(): Expr = b.exists {
            val x = bound.random(rnd)
            if (rnd.nextBoolean()) match(x, tags.random(rnd)) else match(x, rels.random(rnd), target = if (rnd.nextBoolean()) AnyEntity else bound.random(rnd))
        }

        /** "has a child that ...": a local variable. */
        fun existsChild(): Expr = b.exists {
            val c = b.entity("x${vars++}")
            match(c, rels.random(rnd), target = bound.random(rnd))
            match(c, tags.random(rnd))
        }

        fun query(): Case {
            b = IrBuilder()
            repeat(1 + rnd.nextInt(3)) { match(b, allowNew = true) }
            if (rnd.nextInt(3) == 0) {
                // variables bound only in the group may be null outside it; queries that compare them
                // in ways the validator rejects (ordering a nullable value) are skipped
                b.optional { repeat(1 + rnd.nextInt(2)) { match(this, allowNew = true) } }
            }
            repeat(rnd.nextInt(3)) { b.filter(pred(0)) }
            val selected = bound.distinct().take(2)
            if (selected.isEmpty()) b.select("one", Expr.Literal.of(1L)) else selected.forEach { b.select(it) }
            when (rnd.nextInt(6)) {
                0 -> b.distinct()
                1 -> if (selected.isNotEmpty()) {
                    b.groupBy(Expr.Var(selected[0]))
                    val q = b.buildUnchecked()
                    // keep only the grouped column, plus a count
                    val bb = q.copy(
                        select = listOf(q.select.first { it.expr == Expr.Var(selected[0]) }, net.blocks168.pgecs.ir.Projection("n", Expr.Aggregate(AggregateFn.COUNT, null))),
                    )
                    return Case(bb.validate(), args.toList())
                }
                2 -> selected.firstOrNull()?.let { b.orderBy(SortKey(Expr.Var(it), descending = rnd.nextBoolean())) }
                else -> {}
            }
            return Case(b.build(), args.toList())
        }
    }

    private fun normalize(rows: List<List<Any?>>, ordered: Boolean): List<String> {
        val s = rows.map { r -> r.joinToString("|") { (it as? Double)?.let { d -> "%.6f".format(d) } ?: it.toString() } }
        return if (ordered) s else s.sorted()
    }

    @Test
    fun `random queries agree with the reference evaluator`() {
        val rnd = Random(20261105)
        var checked = 0
        var attempts = 0
        while (checked < 1000 && attempts < 20000) {
            attempts++
            val case = try {
                Gen(rnd).query()
            } catch (_: IrValidationException) {
                continue
            } catch (_: IllegalArgumentException) {
                continue
            }
            checked++
            val expected = oracle.run(case.query, case.args)
            val ordered = case.query.orderBy.isNotEmpty()
            for ((name, engine) in listOf("eqsat" to withEqsat, "simplifier" to withoutEqsat)) {
                val actual = try {
                    engine.execute(db.connection, case.query, case.args).map { it.toList() }
                } catch (e: Exception) {
                    fail("[$name] failed: ${describe(engine, case)}", e)
                }
                // with order by, compare the order keys' sequence (ties may come in any order)
                val exp = if (ordered) normalize(expected.map { listOf(it[0]) }, true) else normalize(expected, false)
                val act = if (ordered) normalize(actual.map { listOf(it[0]) }, true) else normalize(actual, false)
                if (ordered) {
                    if (normalize(expected, false) != normalize(actual, false) || exp != act) {
                        fail("[$name] wrong rows\nexpected $expected\nactual   $actual\n${describe(engine, case)}")
                    }
                } else if (exp != act) {
                    fail("[$name] wrong rows\nexpected $expected\nactual   $actual\n${describe(engine, case)}")
                }
            }
        }
        assertTrue(checked >= 800, "only $checked valid random queries")
        println("checked $checked random queries ($attempts generated); eqsat engine: ${withEqsat.metrics()}")
    }

    private fun describe(engine: QueryEngine, c: Case): String {
        val bound = c.query.parameterize(c.args)
        val nulls = bound.arguments.indices.filter { bound.arguments[it] == null }.toSet()
        val sql = runCatching { engine.compile(bound.shape, nulls).toString() }.getOrElse { "compile failed: $it" }
        return "${c.query.canonicalText}\nargs ${c.args}\n$sql"
    }
}
