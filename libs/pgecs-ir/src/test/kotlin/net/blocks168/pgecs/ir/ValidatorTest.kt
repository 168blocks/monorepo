package net.blocks168.pgecs.ir

import net.blocks168.pgecs.ir.Fixtures.ACCOUNT
import net.blocks168.pgecs.ir.Fixtures.CHILD_OF
import net.blocks168.pgecs.ir.Fixtures.EMAIL
import net.blocks168.pgecs.ir.Fixtures.EXPIRES_SLOW
import net.blocks168.pgecs.ir.Fixtures.SMTP
import net.blocks168.pgecs.ir.Fixtures.field
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ValidatorTest {

    private fun invalid(expected: String, block: IrBuilder.() -> Unit) {
        val e = assertFailsWith<IrValidationException> { IrBuilder().apply(block).build() }
        assertTrue(e.problems.any { expected in it }, "expected a problem containing '$expected', got ${e.problems}")
    }

    @Test
    fun `unbound variables`() = invalid("never bound") {
        val acc = entity("acc")
        entity("ghost")
        match(acc, ACCOUNT)
        select(acc)
    }

    @Test
    fun `variable local to an exists is not visible outside`() = invalid("mail is not bound here") {
        val acc = entity("acc")
        val mail = entity("mail")
        match(acc, ACCOUNT)
        filter(exists { match(mail, CHILD_OF, target = acc) })
        select(mail)
    }

    @Test
    fun `one local variable in two exists`() = invalid("two separate scopes") {
        val acc = entity("acc")
        val mail = entity("mail")
        match(acc, ACCOUNT)
        filter(exists { match(mail, CHILD_OF, target = acc) })
        filter(Expr.Not(exists { match(mail, EMAIL) }))
        select(acc)
    }

    @Test
    fun `value of a tag`() = invalid("has no value to bind") {
        val acc = entity("acc")
        val v = value("v", ValueType.JSON)
        match(acc, ACCOUNT, value = v)
        select(acc)
    }

    @Test
    fun `value type mismatch`() = invalid("values are instant") {
        val e = entity("e")
        val v = value("v", ValueType.INT)
        match(e, EXPIRES_SLOW, value = v)
        select(e)
    }

    @Test
    fun `target on a plain component`() = invalid("cannot have a target") {
        val a = entity("a")
        val b = entity("b")
        match(b, ACCOUNT)
        match(a, ACCOUNT, target = b)
        select(a)
    }

    @Test
    fun `relation without target`() = invalid("needs a target") {
        val a = entity("a")
        match(a, CHILD_OF)
        select(a)
    }

    @Test
    fun `capability is not a component`() = invalid("is not a component") {
        val a = entity("a")
        match(a, SMTP)
        select(a)
    }

    @Test
    fun `wildcard relation cannot bind a value`() = invalid("cannot bind a value") {
        val a = entity("a")
        val v = value("v", ValueType.JSON)
        match(a, AnyRelation(), target = AnyEntity, value = v)
        select(a)
    }

    @Test
    fun `value variable bound twice`() = invalid("more than one match") {
        val a = entity("a")
        val b = entity("b")
        val v = value("v", ValueType.JSON)
        match(a, EMAIL, value = v)
        match(b, EMAIL, value = v)
        select(a)
    }

    @Test
    fun `filter must be boolean`() = invalid("expected a boolean") {
        val a = entity("a")
        val v = value("v", ValueType.JSON)
        match(a, EMAIL, value = v)
        filter(field(v, "address"))
        select(a)
    }

    @Test
    fun `ordering comparison of an optional value outside its group`() = invalid("may be null") {
        val a = entity("a")
        val v = value("v", ValueType.INSTANT)
        match(a, ACCOUNT)
        optional { match(a, EXPIRES_SLOW, value = v) }
        filter(Expr.Compare(CompareOp.LT, Expr.Var(v), Functions.call(Functions.NOW)))
        select(a)
    }

    @Test
    fun `incomparable types`() = invalid("cannot compare") {
        val a = entity("a")
        val v = value("v", ValueType.JSON)
        match(a, EMAIL, value = v)
        filter(Fixtures.eq(field(v, "address"), Expr.Literal.of(3)))
        select(a)
    }

    @Test
    fun `field of a scalar value`() = invalid("has no field") {
        val e = entity("e")
        val v = value("v", ValueType.INSTANT)
        match(e, EXPIRES_SLOW, value = v)
        filter(Expr.IsNull(Expr.Field(v, listOf("x"), ValueType.TEXT)))
        select(e)
    }

    @Test
    fun `unsubstituted lambda argument`() = invalid("never substituted") {
        val a = entity("a")
        match(a, EMAIL)
        filter(Fixtures.eq(Expr.Arg(0, ValueType.TEXT), Expr.Literal.of("x")))
        select(a)
    }

    @Test
    fun `unused parameter`() = invalid("parameter 0 is never used") {
        val a = entity("a")
        param(ValueType.TEXT)
        match(a, ACCOUNT)
        select(a)
    }

    @Test
    fun `parameter type mismatch`() = invalid("is used as int but declared text") {
        val a = entity("a")
        val v = value("v", ValueType.JSON)
        param(ValueType.TEXT)
        match(a, EMAIL, value = v)
        filter(Fixtures.eq(field(v, "n", type = ValueType.INT), Expr.Param(0, ValueType.INT)))
        select(a)
    }

    @Test
    fun `entity parameter must be a uuid`() = invalid("expected uuid") {
        val a = entity("a")
        match(a, CHILD_OF, target = param(ValueType.TEXT))
        select(a)
    }

    @Test
    fun `built-in function signature`() = invalid("takes [TEXT, TEXT], got [int]") {
        val a = entity("a")
        val v = value("v", ValueType.JSON)
        match(a, EMAIL, value = v)
        filter(Expr.Call(Functions.STARTS_WITH, listOf(field(v, "n", type = ValueType.INT)), ValueType.BOOL))
        select(a)
    }

    @Test
    fun `projection neither aggregate nor grouped`() = invalid("neither an aggregate nor in group-by") {
        val a = entity("a")
        val m = entity("m")
        match(a, ACCOUNT)
        match(m, CHILD_OF, target = a)
        select(m)
        select("n", Expr.Aggregate(AggregateFn.COUNT, null))
    }

    @Test
    fun `aggregate in a filter`() = invalid("aggregate not allowed") {
        val a = entity("a")
        match(a, ACCOUNT)
        filter(Expr.Compare(CompareOp.GT, Expr.Aggregate(AggregateFn.COUNT, null), Expr.Literal.of(1)))
        select(a)
    }

    @Test
    fun `negative limit`() = invalid("non-negative") {
        val a = entity("a")
        match(a, ACCOUNT)
        select(a)
        limit(Expr.Literal.of(-1))
    }

    @Test
    fun `lock needs a top-level match`() = invalid("no top-level match") {
        val a = entity("a")
        match(a, ACCOUNT)
        select(a)
        lock(RowLock(a, EXPIRES_SLOW))
    }

    @Test
    fun `distinct order by must be selected`() = invalid("must be one of the selected") {
        val a = entity("a")
        val v = value("v", ValueType.INSTANT)
        match(a, EXPIRES_SLOW, value = v)
        select(a)
        distinct()
        orderBy(Expr.Var(v))
    }

    @Test
    fun `nothing selected`() = invalid("selects nothing") {
        val a = entity("a")
        match(a, ACCOUNT)
    }

    @Test
    fun `undeclared variable`() = invalid("is not declared") {
        val a = entity("a")
        match(a, ACCOUNT)
        match(EntityVar(7, "b"), CHILD_OF, target = a)
        select(a)
    }
}
