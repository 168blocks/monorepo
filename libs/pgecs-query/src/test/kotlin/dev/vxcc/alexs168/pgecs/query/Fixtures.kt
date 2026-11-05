package dev.vxcc.alexs168.pgecs.query

import dev.vxcc.alexs168.pgecs.ir.CompareOp
import dev.vxcc.alexs168.pgecs.ir.ComponentKind
import dev.vxcc.alexs168.pgecs.ir.ComponentRef
import dev.vxcc.alexs168.pgecs.ir.Expr
import dev.vxcc.alexs168.pgecs.ir.Functions
import dev.vxcc.alexs168.pgecs.ir.IrBuilder
import dev.vxcc.alexs168.pgecs.ir.QueryIr
import dev.vxcc.alexs168.pgecs.ir.ValueType
import dev.vxcc.alexs168.pgecs.ir.ValueVar

/** Components of the pgecs design doc's examples. */
object Fixtures {
    val ACCOUNT = ComponentRef("b8:iam:account:v1", ComponentKind.TAG)
    val SMP_MAIN_ACCESS = ComponentRef("b8:iam:smpmain-access-allowed", ComponentKind.TAG)
    val LOBBY_DENIED = ComponentRef("b8:iam:lobby-access-denied", ComponentKind.TAG)
    val BANNED = ComponentRef("b8:mod:banned", ComponentKind.TAG)
    val EMAIL = ComponentRef("b8:iam:email:v1", ComponentKind.VALUE, ValueType.JSON)
    val MC_UUID = ComponentRef("b8:iam:mc-uuid:v1", ComponentKind.VALUE, ValueType.JSON)
    val CHILD_OF = ComponentRef("pgecs:child-of", ComponentKind.RELATION)
    val OWNS = ComponentRef("b8:test:owns", ComponentKind.RELATION)
    val CAN = ComponentRef("pgecs:can", ComponentKind.RELATION)
    val SMTP = ComponentRef("b8:iam:smtp", ComponentKind.CAPABILITY)
    val EXPIRES_SLOW = ComponentRef("b8:util:expires-slow:v1", ComponentKind.VALUE, ValueType.INSTANT)
    val SCORE = ComponentRef("b8:test:score:v1", ComponentKind.VALUE, ValueType.INT)

    fun field(v: ValueVar, vararg path: String, type: ValueType = ValueType.TEXT) = Expr.Field(v, path.toList(), type)

    fun eq(l: Expr, r: Expr) = Expr.Compare(CompareOp.EQ, l, r)

    fun lower(e: Expr) = Functions.call(Functions.LOWERCASE, e)

    /** The design doc's main example. */
    fun accountsByEmail(addr: Expr = Expr.Literal.of("aea@example.org")): QueryIr = IrBuilder().run {
        val acc = entity("acc")
        val mail = entity("mail")
        val m = value("m", ValueType.JSON)
        match(acc, ACCOUNT)
        match(acc, SMP_MAIN_ACCESS)
        filter(Expr.Not(exists { match(acc, LOBBY_DENIED) }))
        match(mail, CHILD_OF, target = acc)
        match(mail, EMAIL, value = m)
        filter(eq(lower(field(m, "address")), lower(addr)))
        select(acc)
        select("address", field(m, "address"))
        build()
    }
}
