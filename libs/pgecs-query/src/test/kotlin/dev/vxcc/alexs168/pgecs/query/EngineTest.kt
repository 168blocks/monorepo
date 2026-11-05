package dev.vxcc.alexs168.pgecs.query

import dev.vxcc.alexs168.pgecs.ir.AnyEntity
import dev.vxcc.alexs168.pgecs.ir.AnyRelation
import dev.vxcc.alexs168.pgecs.ir.Canonical
import dev.vxcc.alexs168.pgecs.ir.CompareOp
import dev.vxcc.alexs168.pgecs.ir.Expr
import dev.vxcc.alexs168.pgecs.ir.Functions
import dev.vxcc.alexs168.pgecs.ir.IrBuilder
import dev.vxcc.alexs168.pgecs.ir.QueryIr
import dev.vxcc.alexs168.pgecs.ir.RowLock
import dev.vxcc.alexs168.pgecs.ir.TypeEntity
import dev.vxcc.alexs168.pgecs.ir.ValueType
import dev.vxcc.alexs168.pgecs.ir.parameterize
import dev.vxcc.alexs168.pgecs.query.Fixtures.ACCOUNT
import dev.vxcc.alexs168.pgecs.query.Fixtures.BANNED
import dev.vxcc.alexs168.pgecs.query.Fixtures.CAN
import dev.vxcc.alexs168.pgecs.query.Fixtures.CHILD_OF
import dev.vxcc.alexs168.pgecs.query.Fixtures.EMAIL
import dev.vxcc.alexs168.pgecs.query.Fixtures.EXPIRES_SLOW
import dev.vxcc.alexs168.pgecs.query.Fixtures.LOBBY_DENIED
import dev.vxcc.alexs168.pgecs.query.Fixtures.MC_UUID
import dev.vxcc.alexs168.pgecs.query.Fixtures.OWNS
import dev.vxcc.alexs168.pgecs.query.Fixtures.SCORE
import dev.vxcc.alexs168.pgecs.query.Fixtures.SMP_MAIN_ACCESS
import dev.vxcc.alexs168.pgecs.query.Fixtures.eq
import dev.vxcc.alexs168.pgecs.query.Fixtures.field
import dev.vxcc.alexs168.pgecs.query.Fixtures.lower
import dev.vxcc.alexs168.pgecs.query.exec.ResultSizeException
import dev.vxcc.alexs168.pgecs.query.sql.Mode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The whole engine against PostgreSQL 18: the design doc's queries and the semantics around them. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EngineTest {
    private lateinit var db: TestDb
    private lateinit var engine: QueryEngine

    // accounts: alice (smp, two emails), bob (smp, lobby denied), carol (no smp), dave (smp, banned child)
    private lateinit var alice: UUID
    private lateinit var bob: UUID
    private lateinit var carol: UUID
    private lateinit var dave: UUID
    private lateinit var smtp: UUID
    private lateinit var endpoint: UUID
    private lateinit var bobEmail: UUID

    @BeforeAll
    fun setUp() {
        db = TestDb("engine")
        // generous: the first egglog run in a JVM is slow, and these tests check the optimised plans
        engine = QueryEngine(QueryEngineConfig(eqsatTimeout = java.time.Duration.ofSeconds(30)))
        for (t in listOf(ACCOUNT, SMP_MAIN_ACCESS, LOBBY_DENIED, BANNED)) db.type(t.name)
        for (t in listOf(EMAIL, MC_UUID, EXPIRES_SLOW, SCORE)) db.type(t.name, "value")
        for (t in listOf(CHILD_OF, OWNS, CAN)) db.type(t.name, "relation")
        smtp = db.type("b8:iam:smtp", "capability")

        alice = db.entity(); bob = db.entity(); carol = db.entity(); dave = db.entity()
        for (a in listOf(alice, bob, carol, dave)) db.add(a, ACCOUNT.name)
        for (a in listOf(alice, bob, dave)) db.add(a, SMP_MAIN_ACCESS.name)
        db.add(bob, LOBBY_DENIED.name)
        fun email(owner: UUID, address: String, verified: Long? = null) = db.entity().also {
            db.add(it, EMAIL.name, json = """{"address":"$address","verifiedAt":${verified ?: "null"}}""")
            db.add(it, CHILD_OF.name, target = owner)
        }
        email(alice, "Alice@Example.org", 1_700_000_000_000)
        email(alice, "alice2@example.org")
        bobEmail = email(bob, "bob@example.org")
        email(carol, "carol@example.org")
        db.entity().also { ban ->
            db.add(ban, BANNED.name)
            db.add(ban, CHILD_OF.name, target = dave)
            db.add(ban, EXPIRES_SLOW.name, json = "1700000000000")
        }
        db.add(alice, SCORE.name, json = "10")
        db.add(bob, SCORE.name, json = "30")
        db.add(carol, SCORE.name, json = "20")
        db.add(alice, OWNS.name, target = bob)
        db.add(alice, OWNS.name, target = carol)
        endpoint = db.entity()
        db.add(endpoint, CAN.name, target = smtp)
        db.exec("analyze")
        engine.refreshStats(db.connection, force = true)
    }

    @AfterAll
    fun tearDown() {
        engine.close()
        db.close()
    }

    private fun run(q: QueryIr, vararg args: Any?, mode: Mode = Mode.FETCH) = engine.execute(db.connection, q, args.toList(), mode)

    private fun ids(rows: List<Array<Any?>>) = rows.map { it[0] as UUID }.toSet()

    @Test
    fun `design doc example`() {
        val rows = run(Fixtures.accountsByEmail(Expr.Literal.of("ALICE@example.org")))
        assertEquals(1, rows.size)
        assertEquals(alice, rows[0][0])
        assertEquals("Alice@Example.org", rows[0][1])
        // bob is lobby-denied, carol has no smp access
        assertEquals(emptyList(), run(Fixtures.accountsByEmail(Expr.Literal.of("bob@example.org"))))
        assertEquals(emptyList(), run(Fixtures.accountsByEmail(Expr.Literal.of("carol@example.org"))))
        val sql = engine.compile(Fixtures.accountsByEmail().parameterize().shape).sql!!.sql
        assertContains(sql, "lower(c2.value->>'address') = lower(")
        assertContains(sql, "rel = 'b8:iam:email:v1'")
        assertContains(sql, "not exists")
    }

    @Test
    fun `literals do not split the cache`() {
        run(Fixtures.accountsByEmail(Expr.Literal.of("a@example.org")))
        val before = engine.metrics().compiles
        run(Fixtures.accountsByEmail(Expr.Literal.of("b@example.org")))
        run(Fixtures.accountsByEmail(Expr.Literal.of("c@example.org")))
        assertEquals(before, engine.metrics().compiles)
    }

    private fun accountsWith(block: IrBuilder.(dev.vxcc.alexs168.pgecs.ir.EntityVar) -> Unit): QueryIr = IrBuilder().run {
        val acc = entity("acc")
        match(acc, ACCOUNT)
        block(acc)
        select(acc)
        build()
    }

    @Test
    fun `negation, or, and probes`() {
        assertEquals(setOf(alice, dave), ids(run(accountsWith { acc ->
            match(acc, SMP_MAIN_ACCESS)
            filter(Expr.Not(exists { match(acc, LOBBY_DENIED) }))
        })))
        // acc has (LobbyDenied or not SmpMainAccess)
        assertEquals(setOf(bob, carol), ids(run(accountsWith { acc ->
            filter(Expr.Or(listOf(exists { match(acc, LOBBY_DENIED) }, Expr.Not(exists { match(acc, SMP_MAIN_ACCESS) }))))
        })))
        // has (LobbyDenied or Banned) merges into one probe over two components
        val q = accountsWith { acc ->
            filter(Expr.Or(listOf(exists { match(acc, LOBBY_DENIED) }, exists { match(acc, BANNED) })))
        }
        assertEquals(setOf(bob), ids(run(q)))
        val sql = engine.compile(q).sql!!.sql
        assertTrue(engine.compile(q).optimized)
        assertContains(sql, "in ('b8:iam:lobby-access-denied', 'b8:mod:banned')".let { if (it in sql) it else "in ('b8:mod:banned', 'b8:iam:lobby-access-denied')" })
    }

    @Test
    fun `has a child that`() {
        // accounts with a banned child entity
        assertEquals(setOf(dave), ids(run(accountsWith { acc ->
            filter(exists {
                val ban = entity("ban")
                match(ban, CHILD_OF, target = acc)
                match(ban, BANNED)
            })
        })))
        // and those without
        assertEquals(setOf(alice, bob, carol), ids(run(accountsWith { acc ->
            filter(Expr.Not(exists {
                val ban = entity("ban")
                match(ban, CHILD_OF, target = acc)
                match(ban, BANNED)
            }))
        })))
    }

    @Test
    fun `contradictions return nothing without asking the database`() {
        val q = accountsWith { acc ->
            match(acc, LOBBY_DENIED)
            filter(Expr.Not(exists { match(acc, LOBBY_DENIED) }))
        }
        assertNull(engine.compile(q).sql)
        assertEquals(emptyList(), run(q))
        assertEquals(0L, run(q, mode = Mode.COUNT).single()[0])
    }

    @Test
    fun `wildcards do not multiply rows`() {
        // alice owns two entities: ChildOf-like (Owns, *) still yields alice once
        val q = IrBuilder().run {
            val a = entity("a")
            match(a, OWNS, target = AnyEntity)
            select(a)
            build()
        }
        assertEquals(listOf(alice), run(q).map { it[0] })
        assertContains(engine.compile(q).sql!!.sql, "select distinct entity")
        // (*, bob): anything with some relation to bob (alice owns bob, bob's email is a child of bob)
        val anyRel = IrBuilder().run {
            val a = entity("a")
            match(a, AnyRelation(), target = Expr.Literal.of(bob))
            select(a)
            build()
        }
        assertEquals(setOf(alice, bobEmail), ids(run(anyRel)))
        // the targets alice owns, one row each
        val targets = IrBuilder().run {
            val t = entity("t")
            match(Expr.Literal.of(alice), OWNS, target = t)
            select(t)
            build()
        }
        assertEquals(setOf(bob, carol), ids(run(targets)))
    }

    @Test
    fun `capabilities as type entities`() {
        val q = IrBuilder().run {
            val e = entity("e")
            match(e, CAN, target = TypeEntity("b8:iam:smtp"))
            select(e)
            build()
        }
        assertEquals(listOf(endpoint), run(q).map { it[0] })
    }

    @Test
    fun `optional values are null when missing`() {
        val q = IrBuilder().run {
            val acc = entity("acc")
            val s = value("s", ValueType.INT)
            match(acc, ACCOUNT)
            optional { match(acc, SCORE, value = s) }
            select(acc)
            select("score", Expr.Var(s))
            build()
        }
        val byAcc = run(q).associate { it[0] to it[1] }
        assertEquals(mapOf<Any?, Any?>(alice to 10L, bob to 30L, carol to 20L, dave to null), byAcc)
        // Kotlin semantics: score == null holds for dave only
        val missing = IrBuilder().run {
            val acc = entity("acc")
            val s = value("s", ValueType.INT)
            match(acc, ACCOUNT)
            optional { match(acc, SCORE, value = s) }
            filter(Expr.IsNull(Expr.Var(s)))
            select(acc)
            build()
        }
        assertEquals(setOf(dave), ids(run(missing)))
        // score != 30 holds for null too (Kotlin), unlike SQL's <>
        val notThirty = IrBuilder().run {
            val acc = entity("acc")
            val s = value("s", ValueType.INT)
            match(acc, ACCOUNT)
            optional { match(acc, SCORE, value = s) }
            filter(Expr.Compare(CompareOp.NE, Expr.Var(s), Expr.Literal.of(30L)))
            select(acc)
            build()
        }
        assertEquals(setOf(alice, carol, dave), ids(run(notThirty)))
    }

    @Test
    fun `nullable parameters`() {
        // verifiedAt == p: with p null, emails that are not verified
        val q = IrBuilder().run {
            val mail = entity("mail")
            val m = value("m", ValueType.JSON)
            match(mail, EMAIL, value = m)
            filter(eq(field(m, "verifiedAt", type = ValueType.INSTANT.asNullable()), param(ValueType.INSTANT.asNullable())))
            select("address", field(m, "address"))
            build()
        }
        assertEquals(setOf("alice2@example.org", "bob@example.org", "carol@example.org"), run(q, null).map { it[0] }.toSet())
        assertEquals(listOf<Any?>("Alice@Example.org"), run(q, Instant.ofEpochMilli(1_700_000_000_000)).map { it[0] })
        assertContains(engine.compile(q, setOf(0)).sql!!.sql, "is null")
    }

    @Test
    fun `instants, now and ordering`() {
        val expired = IrBuilder().run {
            val e = entity("e")
            val t = value("t", ValueType.INSTANT)
            match(e, EXPIRES_SLOW, value = t)
            filter(Expr.Compare(CompareOp.LT, Expr.Var(t), Functions.call(Functions.NOW)))
            select(e)
            select("at", Expr.Var(t))
            build()
        }
        val row = run(expired).single()
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), row[1])
        assertContains(engine.compile(expired).sql!!.sql, "(c0.value#>>'{}')::bigint")

        val ranked = IrBuilder().run {
            val acc = entity("acc")
            val s = value("s", ValueType.INT)
            match(acc, SCORE, value = s)
            select(acc)
            orderBy(Expr.Var(s), descending = true)
            limit(Expr.Literal.of(2L))
            build()
        }
        assertEquals(listOf<Any?>(bob, carol), run(ranked).map { it[0] })
        assertEquals(listOf<Any?>(bob), run(ranked, mode = Mode.FIRST).map { it[0] })
        assertFailsWith<ResultSizeException> { run(ranked, mode = Mode.ONE) }
        assertEquals(2L, run(ranked, mode = Mode.COUNT).single()[0])
    }

    @Test
    fun `aggregates`() {
        val perAccount = IrBuilder().run {
            val acc = entity("acc")
            val mail = entity("mail")
            match(acc, ACCOUNT)
            match(mail, CHILD_OF, target = acc)
            match(mail, EMAIL)
            groupBy(Expr.Var(acc))
            select(acc)
            select("n", Expr.Aggregate(dev.vxcc.alexs168.pgecs.ir.AggregateFn.COUNT, null))
            orderBy(Expr.Aggregate(dev.vxcc.alexs168.pgecs.ir.AggregateFn.COUNT, null), descending = true)
            build()
        }
        val rows = run(perAccount)
        assertEquals(alice, rows.first()[0])
        assertEquals(2L, rows.first()[1])
        assertEquals(3, rows.size)
        val total = IrBuilder().run {
            val acc = entity("acc")
            val s = value("s", ValueType.INT)
            match(acc, SCORE, value = s)
            select("sum", Expr.Aggregate(dev.vxcc.alexs168.pgecs.ir.AggregateFn.SUM, Expr.Var(s)))
            select("max", Expr.Aggregate(dev.vxcc.alexs168.pgecs.ir.AggregateFn.MAX, Expr.Var(s)))
            build()
        }
        assertEquals(listOf<Any?>(60L, 30L), run(total).single().toList())
    }

    @Test
    fun `uuid fields compare as text with entities`() {
        val link = db.entity()
        db.add(link, MC_UUID.name, json = """{"uuid":"$alice"}""")
        val q = IrBuilder().run {
            val l = entity("l")
            val acc = entity("acc")
            val m = value("m", ValueType.JSON)
            match(l, MC_UUID, value = m)
            match(acc, ACCOUNT)
            filter(eq(field(m, "uuid", type = ValueType.UUID), Expr.Var(acc)))
            select(l)
            select("uuid", field(m, "uuid", type = ValueType.UUID))
            build()
        }
        val row = run(q).single()
        assertEquals(link, row[0])
        assertEquals(alice, row[1])
        assertContains(engine.compile(q).sql!!.sql, "c0.value->>'uuid' = (")
    }

    @Test
    fun `locks the matched row and skips locked ones`() {
        val q = IrBuilder().run {
            val e = entity("e")
            val t = value("t", ValueType.INSTANT)
            match(e, EXPIRES_SLOW, value = t)
            select(e)
            lock(RowLock(e, EXPIRES_SLOW))
            build()
        }
        assertContains(engine.compile(q).sql!!.sql, "for update of c0 skip locked")
        db.connection.autoCommit = false
        try {
            assertEquals(1, run(q).size)
            // a second session sees the row as locked and skips it
            TestDb("engine-other").use { other ->
                other.exec("set search_path = ${db.schema}")
                other.connection.autoCommit = false
                assertEquals(0, engine.execute(other.connection, q, emptyList()).size)
                other.connection.rollback()
            }
        } finally {
            db.connection.rollback()
            db.connection.autoCommit = true
        }
    }

    @Test
    fun `hot shapes are recorded and pre-compiled`() {
        run(Fixtures.accountsByEmail(Expr.Literal.of("x@example.org")))
        val shapes = HotShapes(engine, "test", Canonical::parse)
        assertTrue(shapes.flush(db.connection) >= 1)
        QueryEngine().use { fresh ->
            val n = HotShapes(fresh, "test", Canonical::parse).precompile(db.connection).join()
            assertTrue(n >= 1)
            assertTrue(fresh.metrics().compiles >= 1)
            val before = fresh.metrics().compiles
            fresh.execute(db.connection, Fixtures.accountsByEmail(Expr.Literal.of("y@example.org")), emptyList())
            assertEquals(before, fresh.metrics().compiles)
        }
    }

    @Test
    fun `plans read the statistics`() {
        val s = engine.stats
        assertTrue(s.rows(ACCOUNT.name) in 1.0..100.0, "$s")
        assertFalse(s.entities < 1)
    }
}
