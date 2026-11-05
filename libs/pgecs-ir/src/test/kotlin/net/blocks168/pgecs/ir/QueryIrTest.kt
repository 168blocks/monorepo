package net.blocks168.pgecs.ir

import net.blocks168.pgecs.ir.Fixtures.ACCOUNT
import net.blocks168.pgecs.ir.Fixtures.CAN
import net.blocks168.pgecs.ir.Fixtures.CHILD_OF
import net.blocks168.pgecs.ir.Fixtures.EMAIL
import net.blocks168.pgecs.ir.Fixtures.EXPIRES_SLOW
import net.blocks168.pgecs.ir.Fixtures.MC_POSITION
import net.blocks168.pgecs.ir.Fixtures.MC_UUID
import net.blocks168.pgecs.ir.Fixtures.SMTP
import net.blocks168.pgecs.ir.Fixtures.accountsByEmail
import net.blocks168.pgecs.ir.Fixtures.eq
import net.blocks168.pgecs.ir.Fixtures.field
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class QueryIrTest {

    @Test
    fun `design doc example`() {
        val q = accountsByEmail()
        assertEquals(
            """
            (query v1
              (vars (entity 0 acc) (entity 1 mail) (value 2 m json))
              (params)
              (terms
                (match (var 0) (component "b8:iam:account:v1" tag postgres) _ _)
                (match (var 0) (component "b8:iam:smpmain-access-allowed" tag postgres) _ _)
                (filter (not (exists (match (var 0) (component "b8:iam:lobby-access-denied" tag postgres) _ _))))
                (match (var 1) (component "pgecs:child-of" relation postgres) (var 0) _)
                (match (var 1) (component "b8:iam:email:v1" value json postgres) _ (var 2))
                (filter (eq (call pgecs:lowercase text (field 2 "address" text)) (call pgecs:lowercase text (literal text "aea@example.org")))))
              (select ("acc" (var 0)) ("address" (field 2 "address" text)))
              (distinct false) (group-by) (order-by) (limit _) (offset _) (lock _))
            """.trimIndent(),
            q.canonicalText,
        )
    }

    @Test
    fun `parameter values are not part of the shape`() {
        val a = accountsByEmail(Expr.Literal.of("a@example.org")).parameterize()
        val b = accountsByEmail(Expr.Literal.of("b@example.org")).parameterize()
        assertEquals(a.shape, b.shape)
        assertEquals(a.shape.hashCode(), b.shape.hashCode())
        assertEquals(a.shape.fingerprint, b.shape.fingerprint)
        assertEquals(listOf<Any?>("a@example.org"), a.arguments)
        assertEquals(listOf<Any?>("b@example.org"), b.arguments)
        assertEquals(listOf(ValueType.TEXT), a.shape.params)
        a.shape.validate()
        // A captured value (a Param from the start) gives the same shape as a lifted literal.
        val captured = IrBuilder().run {
            val acc = entity("acc")
            val mail = entity("mail")
            val m = value("m", ValueType.JSON)
            match(acc, ACCOUNT)
            match(acc, Fixtures.SMP_MAIN_ACCESS)
            filter(Expr.Not(exists { match(acc, Fixtures.LOBBY_DENIED) }))
            match(mail, CHILD_OF, target = acc)
            match(mail, EMAIL, value = m)
            filter(eq(Fixtures.lower(field(m, "address")), Fixtures.lower(param(ValueType.TEXT))))
            select(acc)
            select("address", field(m, "address"))
            build()
        }
        assertEquals(a.shape, captured)
        assertEquals(a.shape.fingerprint, captured.fingerprint)
    }

    @Test
    fun `component names, variable names and term order are part of the shape`() {
        fun q(component: ComponentRef, name: String, swap: Boolean) = IrBuilder().run {
            val e = entity(name)
            val terms = listOf(component, Fixtures.LOBBY_DENIED)
            (if (swap) terms.reversed() else terms).forEach { match(e, it) }
            select(e)
            build()
        }
        val base = q(ACCOUNT, "acc", false)
        assertEquals(base, q(ACCOUNT, "acc", false))
        assertNotEquals(base, q(Fixtures.SMP_MAIN_ACCESS, "acc", false))
        assertNotEquals(base, q(ACCOUNT, "a", false))
        assertNotEquals(base, q(ACCOUNT, "acc", true))
        assertNotEquals(base.fingerprint, q(ACCOUNT, "acc", true).fingerprint)
        assertEquals(64, base.fingerprint.length)
    }

    @Test
    fun `entity ids in entity positions are parameters`() {
        val id = UUID.fromString("018f0000-0000-7000-8000-000000000001")
        val q = IrBuilder().run {
            val mail = entity("mail")
            match(mail, CHILD_OF, target = Expr.Literal.of(id))
            select(mail)
            build()
        }
        val bound = q.parameterize()
        assertEquals(listOf<Any?>(id), bound.arguments)
        val match = bound.shape.terms.single() as Term.Match
        assertEquals(Expr.Param(0, ValueType.UUID), match.target)
    }

    @Test
    fun `capability as a target entity`() {
        IrBuilder().run {
            val ep = entity("endpoint")
            match(ep, CAN, target = TypeEntity(SMTP.name))
            select(ep)
            build()
        }
    }

    @Test
    fun `expiry sweep with now, order and lock`() {
        val q = IrBuilder().run {
            val e = entity("e")
            val exp = value("exp", ValueType.INSTANT)
            match(e, EXPIRES_SLOW, value = exp)
            filter(Expr.Compare(CompareOp.LT, Expr.Var(exp), Functions.call(Functions.NOW)))
            orderBy(Expr.Var(exp))
            select(e)
            limit(param(ValueType.INT))
            lock(RowLock(e, EXPIRES_SLOW))
            build()
        }
        assertTrue("(lock (var 0) (component \"b8:util:expires-slow:v1\" value instant postgres) update skip_locked)" in q.canonicalText)
        assertEquals(SortKey(Expr.Var(q.variables[1]), descending = false, nulls = NullOrder.FIRST), q.orderBy.single())
    }

    @Test
    fun `federated query, terms know their source`() {
        val q = IrBuilder().run {
            val p = entity("p")
            val link = entity("link")
            val account = entity("account")
            val pos = value("pos", ValueType.JSON)
            val mc = value("mc", ValueType.JSON)
            match(p, MC_POSITION, value = pos)
            filter(
                and(
                    eq(field(pos, "world"), Expr.Literal.of("smpmain")),
                    Expr.Call(FunctionId("mc:near"), listOf(Expr.Var(pos), param(ValueType.FLOAT)), ValueType.BOOL),
                ),
            )
            match(link, MC_UUID, value = mc)
            filter(eq(field(mc, "uuid", type = ValueType.UUID), Expr.Var(p)))
            match(link, CHILD_OF, target = account)
            match(account, ACCOUNT)
            select(account)
            select(p)
            build()
        }
        val sources = q.terms.filterIsInstance<Term.Match>().map { it.source }
        assertEquals(listOf(Fixtures.MINECRAFT, SourceId.POSTGRES, SourceId.POSTGRES, SourceId.POSTGRES), sources)
    }

    @Test
    fun `aggregates`() {
        IrBuilder().run {
            val acc = entity("acc")
            val mail = entity("mail")
            match(acc, ACCOUNT)
            match(mail, CHILD_OF, target = acc)
            groupBy(Expr.Var(acc))
            select(acc)
            select("emails", Expr.Aggregate(AggregateFn.COUNT, Expr.Var(mail), distinct = true))
            orderBy(Expr.Aggregate(AggregateFn.COUNT, null), descending = true)
            build()
        }
        // count() of any query
        val count = accountsByEmail().let { it.copy(select = listOf(Projection("count", Expr.Aggregate(AggregateFn.COUNT, null)))) }
        count.validate()
        assertEquals(ValueType.INT, count.select.single().expr.type)
    }

    @Test
    fun `optional groups make variables nullable outside of them`() {
        val q = IrBuilder().run {
            val acc = entity("acc")
            val mail = entity("mail")
            val m = value("m", ValueType.JSON)
            match(acc, ACCOUNT)
            optional {
                match(mail, CHILD_OF, target = acc)
                match(mail, EMAIL, value = m)
                // inside the group, m is bound and not null: an ordering comparison is fine
                filter(Expr.Compare(CompareOp.GT, field(m, "verifiedAt", type = ValueType.INSTANT), param(ValueType.INSTANT)))
            }
            select(acc)
            select("address", field(m, "address"))
            build()
        }
        val nullVars = Nullability.optionalVariables(q)
        assertEquals(setOf(q.variables[1], q.variables[2]), nullVars)
        assertTrue(Nullability.mayBeNull(q.select[1].expr, nullVars))
        assertTrue(!Nullability.mayBeNull(q.select[0].expr, nullVars))
    }

    @Test
    fun `lambda arguments and parameters are substituted`() {
        // `{ it.address == addr }` as the decompiler sees it: it = Arg(0), addr = Param(0) of the lambda
        val lambda = eq(Expr.Field(ValueVar(0, "it", ValueType.JSON), listOf("address"), ValueType.TEXT), Expr.Param(0, ValueType.TEXT))
        val withArg = eq(Expr.Arg(0, ValueType.TEXT), Expr.Param(0, ValueType.TEXT))
        val m = ValueVar(5, "m", ValueType.JSON)
        val f = Expr.Field(m, listOf("address"), ValueType.TEXT)
        assertEquals(eq(f, Expr.Param(3, ValueType.TEXT)), withArg.bindArgs(listOf(f)).mapParams { it + 3 })
        assertEquals(setOf<Variable>(ValueVar(0, "it", ValueType.JSON)), lambda.variables())
        assertFailsWith<IllegalArgumentException> { withArg.bindArgs(listOf(Expr.Literal.of(1))) }
    }

    @Test
    fun `typing rules`() {
        val i = Expr.Literal.of(Instant.EPOCH)
        val d = Expr.Literal.of(java.time.Duration.ofMinutes(5))
        assertEquals(ValueType.INSTANT, Expr.Arith(ArithOp.MINUS, i, d).type)
        assertEquals(ValueType.DURATION, Expr.Arith(ArithOp.MINUS, i, i).type)
        assertEquals(ValueType.FLOAT, Expr.Arith(ArithOp.TIMES, Expr.Literal.of(2), Expr.Literal.of(1.5)).type)
        assertFailsWith<IllegalArgumentException> { Expr.Arith(ArithOp.PLUS, i, i) }
        assertFailsWith<IllegalArgumentException> { Expr.Aggregate(AggregateFn.SUM, Expr.Literal.of("x")) }
        assertEquals(ValueType.INT, Expr.Literal.of(3).type)
        assertEquals(3L, Expr.Literal.of(3.toShort()).value)
        assertFailsWith<IllegalArgumentException> { Expr.Literal(3, ValueType.INT) }
        assertFailsWith<IllegalArgumentException> { ComponentRef("account", ComponentKind.TAG) }
        assertFailsWith<IllegalArgumentException> { ComponentRef("b8:x", ComponentKind.VALUE) }
        assertEquals(listOf(Expr.Literal.of(true), Expr.Literal.of(false)), (and(and(Expr.Literal.of(true), Expr.Literal.of(false))) as Expr.And).operands)
    }

    @Test
    fun `bound query checks arguments`() {
        val shape = accountsByEmail().parameterize().shape
        BoundQuery(shape, listOf("x"))
        assertFailsWith<IllegalArgumentException> { BoundQuery(shape, listOf(1)) }
        assertFailsWith<IllegalArgumentException> { BoundQuery(shape, listOf(null)) }
        assertFailsWith<IllegalArgumentException> { BoundQuery(shape, emptyList()) }
    }

    @Test
    fun `hash is cached and copies compare structurally`() {
        val q = accountsByEmail()
        val c = q.copy()
        assertEquals(q, c)
        assertEquals(q.hashCode(), c.hashCode())
        assertNotEquals(q, q.copy(distinct = true))
        assertEquals(q.fingerprint, Canonical.fingerprint(q.toString()))
    }
}
