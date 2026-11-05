package dev.vxcc.alexs168.pgecs.query

import dev.vxcc.alexs168.pgecs.eqsat.EgglogPool
import dev.vxcc.alexs168.pgecs.ir.Expr
import dev.vxcc.alexs168.pgecs.ir.IrBuilder
import dev.vxcc.alexs168.pgecs.ir.QueryIr
import dev.vxcc.alexs168.pgecs.ir.parameterize
import dev.vxcc.alexs168.pgecs.query.Fixtures.ACCOUNT
import dev.vxcc.alexs168.pgecs.query.Fixtures.BANNED
import dev.vxcc.alexs168.pgecs.query.Fixtures.LOBBY_DENIED
import dev.vxcc.alexs168.pgecs.query.Fixtures.SMP_MAIN_ACCESS
import dev.vxcc.alexs168.pgecs.query.lower.Lowering
import dev.vxcc.alexs168.pgecs.query.opt.EqsatOptimizer
import dev.vxcc.alexs168.pgecs.query.opt.Simplifier
import dev.vxcc.alexs168.pgecs.query.plan.Block
import dev.vxcc.alexs168.pgecs.query.plan.CmpOp
import dev.vxcc.alexs168.pgecs.query.plan.Column
import dev.vxcc.alexs168.pgecs.query.plan.Kind
import dev.vxcc.alexs168.pgecs.query.plan.LogicalQuery
import dev.vxcc.alexs168.pgecs.query.plan.Pred
import dev.vxcc.alexs168.pgecs.query.plan.Scan
import dev.vxcc.alexs168.pgecs.query.plan.SelectItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import dev.vxcc.alexs168.pgecs.query.plan.Expr as P

/** The egglog core rules, one at a time, on lowered plans (no database). */
class OptimizerTest {
    private val eqsat = EqsatOptimizer(EgglogPool(1, EqsatOptimizer.PRELUDE))

    private fun lowered(q: QueryIr): LogicalQuery = Lowering.lower(q.parameterize().shape).query

    private fun accounts(block: IrBuilder.(dev.vxcc.alexs168.pgecs.ir.EntityVar) -> Unit): QueryIr = IrBuilder().run {
        val acc = entity("acc")
        match(acc, ACCOUNT)
        block(acc)
        select(acc)
        build()
    }

    @Test
    fun `probes of one entity merge over components`() {
        val q = lowered(accounts { acc ->
            filter(Expr.Or(listOf(exists { match(acc, LOBBY_DENIED) }, exists { match(acc, BANNED) }, exists { match(acc, SMP_MAIN_ACCESS) })))
        })
        val where = eqsat.optimize(q).root.where
        assertTrue(where is Pred.Has, "$where")
        assertEquals(setOf(LOBBY_DENIED.name, BANNED.name, SMP_MAIN_ACCESS.name), where.rels.toSet())
    }

    @Test
    fun `negated probes merge too`() {
        val q = lowered(accounts { acc ->
            filter(Expr.Not(exists { match(acc, LOBBY_DENIED) }))
            filter(Expr.Not(exists { match(acc, BANNED) }))
        })
        val where = eqsat.optimize(q).root.where
        assertTrue(where is Pred.Not && where.pred is Pred.Has, "$where")
        assertEquals(setOf(LOBBY_DENIED.name, BANNED.name), ((where as Pred.Not).pred as Pred.Has).rels.toSet())
    }

    @Test
    fun `a probe that a scan guarantees is true, its negation a contradiction`() {
        // the simplifier does it too; here egglog alone, on the unsimplified plan
        val q = lowered(accounts { acc ->
            match(acc, SMP_MAIN_ACCESS)
            filter(exists { match(acc, ACCOUNT) })
        })
        assertTrue(Pred.conjuncts(eqsat.optimize(q).root.where).none { it is Pred.Has && it.rels == listOf(ACCOUNT.name) })
        val contradiction = lowered(accounts { acc ->
            match(acc, SMP_MAIN_ACCESS)
            filter(Expr.Not(exists { match(acc, ACCOUNT) }))
        })
        assertEquals(Pred.False, eqsat.optimize(contradiction).root.where)
        assertEquals(Pred.False, Simplifier.simplify(contradiction).root.where)
    }

    @Test
    fun `p and not p is only a contradiction when p is never null`() {
        val a = P.Field(0, listOf("n"), Kind.LONG)
        val b = P.Param(0, Kind.LONG)
        val cmp = Pred.Cmp(CmpOp.EQ, a, b)
        val q = query(Pred.And(listOf(cmp, Pred.Not(cmp))))
        // n = ? and not (n = ?) is null, not false, when n is null; it stays a comparison
        val where = eqsat.optimize(q).root.where
        assertNotEquals(Pred.False, where)
        assertTrue(Pred.conjuncts(where).all { it is Pred.Cmp }, "$where")

        val isNull = Pred.IsNull(a)
        assertEquals(Pred.False, eqsat.optimize(query(Pred.And(listOf(isNull, Pred.Not(isNull))))).root.where)
    }

    @Test
    fun `negation moves into comparisons`() {
        val a = P.Field(0, listOf("n"), Kind.LONG)
        val where = eqsat.optimize(query(Pred.Not(Pred.Or(listOf(Pred.Cmp(CmpOp.LT, a, P.Param(0, Kind.LONG)), Pred.Cmp(CmpOp.EQ, a, P.Param(1, Kind.LONG))))))).root.where
        assertEquals(setOf<Pred>(Pred.Cmp(CmpOp.GE, a, P.Param(0, Kind.LONG)), Pred.Cmp(CmpOp.NE, a, P.Param(1, Kind.LONG))), Pred.conjuncts(where).toSet())
    }

    @Test
    fun `bounded on wide predicates`() {
        // 12 conjuncts of or-ed probes: and/or are commutative and associative, so the e-graph grows
        // fast; the iteration bound keeps it in check
        val q = lowered(accounts { acc ->
            repeat(6) {
                filter(Expr.Or(listOf(exists { match(acc, LOBBY_DENIED) }, exists { match(acc, BANNED) })))
                filter(Expr.Not(exists { match(acc, SMP_MAIN_ACCESS) }))
            }
        })
        repeat(3) { eqsat.optimize(q) } // warm-up
        val start = System.nanoTime()
        val where = eqsat.optimize(Simplifier.simplify(q)).root.where
        val ms = (System.nanoTime() - start) / 1e6
        println("wide predicate: $ms ms -> $where")
        assertTrue(ms < 5_000, "$ms ms")
        assertEquals(2, Pred.conjuncts(where).size, "$where")
    }

    private fun query(where: Pred) = LogicalQuery(
        root = Block(0, listOf(Scan(0, "b8:x", P.Col(0, Column.ENTITY), P.Nil)), where),
        select = listOf(SelectItem("x", P.Col(0, Column.ENTITY))),
    )
}
