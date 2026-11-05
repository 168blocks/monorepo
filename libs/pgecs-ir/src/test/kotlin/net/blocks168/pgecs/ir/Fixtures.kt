package net.blocks168.pgecs.ir

/** Components of the pgecs design doc's examples. */
object Fixtures {
    val ACCOUNT = ComponentRef("b8:iam:account:v1", ComponentKind.TAG)
    val SMP_MAIN_ACCESS = ComponentRef("b8:iam:smpmain-access-allowed", ComponentKind.TAG)
    val LOBBY_DENIED = ComponentRef("b8:iam:lobby-access-denied", ComponentKind.TAG)
    val EMAIL = ComponentRef("b8:iam:email:v1", ComponentKind.VALUE, ValueType.JSON)
    val MC_UUID = ComponentRef("b8:iam:mc-uuid:v1", ComponentKind.VALUE, ValueType.JSON)
    val CHILD_OF = ComponentRef("pgecs:child-of", ComponentKind.RELATION)
    val CAN = ComponentRef("pgecs:can", ComponentKind.RELATION)
    val SMTP = ComponentRef("b8:iam:smtp", ComponentKind.CAPABILITY, ValueType.JSON)
    val EXPIRES_SLOW = ComponentRef("b8:util:expires-slow:v1", ComponentKind.VALUE, ValueType.INSTANT)
    val MINECRAFT = SourceId("minecraft")
    val MC_POSITION = ComponentRef("b8:mc:position", ComponentKind.VALUE, ValueType.JSON, MINECRAFT)

    fun field(v: ValueVar, vararg path: String, type: ValueType = ValueType.TEXT) = Expr.Field(v, path.toList(), type)

    fun eq(l: Expr, r: Expr) = Expr.Compare(CompareOp.EQ, l, r)

    fun lower(e: Expr) = Functions.call(Functions.LOWERCASE, e)

    /**
     * The design doc's main example:
     * acc has Account, SmpMainAccess, !LobbyDenied; mail childOf acc;
     * mail has Email.where { it.address.lowercase() == addr.lowercase() }; select { acc to mail[Email].address }
     */
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
