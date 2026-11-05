package dev.vxcc.alexs168.pgecs.ir

import dev.vxcc.alexs168.pgecs.ir.Fixtures.ACCOUNT
import dev.vxcc.alexs168.pgecs.ir.Fixtures.CAN
import dev.vxcc.alexs168.pgecs.ir.Fixtures.CHILD_OF
import dev.vxcc.alexs168.pgecs.ir.Fixtures.EMAIL
import dev.vxcc.alexs168.pgecs.ir.Fixtures.EXPIRES_SLOW
import dev.vxcc.alexs168.pgecs.ir.Fixtures.MC_POSITION
import dev.vxcc.alexs168.pgecs.ir.Fixtures.SMTP
import dev.vxcc.alexs168.pgecs.ir.Fixtures.eq
import dev.vxcc.alexs168.pgecs.ir.Fixtures.field
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CanonicalTest {

    private fun roundTrip(q: QueryIr) {
        val parsed = Canonical.parse(q.canonicalText)
        assertEquals(q, parsed)
        assertEquals(q.canonicalText, parsed.canonicalText)
        assertEquals(q.fingerprint, parsed.fingerprint)
    }

    /** Every node kind, every literal kind, awkward strings. */
    private val everything: QueryIr = IrBuilder().run {
        val acc = entity("acc")
        val mail = entity("mail")
        val ep = entity("ep")
        val m = value("m", ValueType.JSON)
        val exp = value("exp", ValueType.INSTANT)
        val pos = value("pos", ValueType.JSON)
        match(acc, ACCOUNT)
        match(acc, CHILD_OF, target = Expr.Literal.of(UUID.fromString("018f0000-0000-7000-8000-000000000001")))
        match(ep, CAN, target = TypeEntity(SMTP.name))
        match(ep, AnyRelation(), target = AnyEntity)
        match(ep, CHILD_OF, target = param(ValueType.UUID))
        match(ep, MC_POSITION, value = pos)
        filter(Expr.Call(FunctionId("mc:near"), listOf(Expr.Var(pos), Expr.Literal.of(-0.0), Expr.Literal.of(1.5e300)), ValueType.BOOL))
        optional {
            match(mail, CHILD_OF, target = acc)
            match(mail, EMAIL, value = m)
            filter(
                or(
                    eq(field(m, "a b", "\"q\"\\\n\u0001ü", type = ValueType.TEXT.asNullable()), Expr.Literal.of("x \"y\" \\ \t ü")),
                    Expr.IsNull(field(m, "verifiedAt", type = ValueType.INSTANT.asNullable())),
                    Functions.call(Functions.STARTS_WITH, field(m, "address"), param(ValueType.TEXT)),
                    eq(field(m, "flag", type = ValueType.BOOL), Expr.Literal.of(false)),
                    eq(field(m, "j", type = ValueType.JSON), Expr.Literal("{\"k\":[1,2]}", ValueType.JSON)),
                ),
            )
        }
        match(acc, EXPIRES_SLOW, value = exp)
        filter(
            and(
                Expr.Compare(CompareOp.GE, Expr.Var(exp), Expr.Arith(ArithOp.MINUS, Functions.call(Functions.NOW), Expr.Literal.of(Duration.ofMinutes(5)))),
                Expr.Compare(CompareOp.LT, Expr.Var(exp), Expr.Literal.of(Instant.parse("2026-11-05T03:00:00.123Z"))),
                Expr.Not(exists { match(acc, Fixtures.LOBBY_DENIED) }),
                Expr.Compare(CompareOp.NE, Expr.Negate(Expr.Literal.of(3)), Expr.Arith(ArithOp.REM, Expr.Literal.of(7), Expr.Literal.of(2))),
                Expr.Literal.of(true),
            ),
        )
        groupBy(Expr.Var(acc))
        groupBy(Expr.If(Expr.IsNull(Expr.Var(acc)), Expr.Literal.of("none"), Functions.call(Functions.CONCAT, Expr.Literal.of("acc-"), Expr.Literal.of("x"))))
        select(acc)
        select("n", Expr.Aggregate(AggregateFn.COUNT, Expr.Var(mail), distinct = true))
        select("all", Expr.Aggregate(AggregateFn.COUNT, null))
        select("label", Expr.If(Expr.IsNull(Expr.Var(acc)), Expr.Literal.of("none"), Functions.call(Functions.CONCAT, Expr.Literal.of("acc-"), Expr.Literal.of("x"))))
        select("latest", Expr.Coalesce(listOf(Expr.Aggregate(AggregateFn.MAX, Expr.Var(exp)), Expr.Literal.of(Instant.EPOCH))))
        orderBy(SortKey(Expr.Aggregate(AggregateFn.COUNT, null), descending = true, nulls = NullOrder.FIRST))
        orderBy(Expr.Var(acc))
        limit(param(ValueType.INT))
        offset(Expr.Literal.of(10))
        buildUnchecked()
    }

    @Test
    fun `round trip of every node`() {
        roundTrip(everything)
        roundTrip(everything.parameterize(listOf(UUID.randomUUID(), "p", 5L)).shape)
    }

    @Test
    fun `round trip of the design doc example and a lock`() {
        roundTrip(Fixtures.accountsByEmail())
        roundTrip(Fixtures.accountsByEmail().parameterize().shape)
        roundTrip(
            IrBuilder().run {
                val e = entity("e")
                val exp = value("exp", ValueType.INSTANT)
                match(e, EXPIRES_SLOW, value = exp)
                filter(Expr.Compare(CompareOp.LT, Expr.Var(exp), Functions.call(Functions.NOW)))
                select(e)
                limit(param(ValueType.INT))
                lock(RowLock(e, EXPIRES_SLOW, LockMode.NO_KEY_UPDATE, LockWait.NOWAIT))
                build()
            },
        )
    }

    @Test
    fun `lambda arguments survive too`() {
        val q = IrBuilder().run {
            val a = entity("a")
            match(a, ACCOUNT)
            filter(eq(Expr.Arg(0, ValueType.TEXT), Expr.Literal.of("x")))
            select(a)
            buildUnchecked()
        }
        roundTrip(q)
    }

    @Test
    fun `wrong version and malformed text are rejected`() {
        val text = Fixtures.accountsByEmail().canonicalText
        assertFailsWith<CanonicalParseException> { Canonical.parse(text.replaceFirst("(query v1", "(query v0")) }
        assertFailsWith<CanonicalParseException> { Canonical.parse(text.dropLast(1)) }
        assertFailsWith<CanonicalParseException> { Canonical.parse("$text x") }
        assertFailsWith<CanonicalParseException> { Canonical.parse(text.replace("tag postgres", "flag postgres")) }
        assertFailsWith<CanonicalParseException> { Canonical.parse(text.replace("(var 2)", "(var 9)")) }
        assertFailsWith<CanonicalParseException> { Canonical.parse(text.replace("\"b8:iam:account:v1\"", "\"account\"")) }
    }
}
