package net.blocks168.pgecs.dsl

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.blocks168.pgecs.ChildOf
import net.blocks168.pgecs.childOf
import net.blocks168.pgecs.Component
import net.blocks168.pgecs.EntityId
import net.blocks168.pgecs.Relation
import net.blocks168.pgecs.Tag
import net.blocks168.pgecs.ir.Expr
import net.blocks168.pgecs.ir.ValueType
import net.blocks168.pgecs.serial.InstantAsEpochMillis
import net.blocks168.pgecs.serial.UuidAsString

@Serializable
data class Email(
    val address: String,
    @Serializable(InstantAsEpochMillis::class) val verifiedAt: Instant? = null,
)

@Serializable
data class McUuid(@Serializable(UuidAsString::class) val uuid: UUID)

@Serializable
data class Profile(@SerialName("display_name") val displayName: String, val level: Int, val role: Role = Role.MEMBER)

@Serializable
enum class Role { MEMBER, ADMIN }

object EmailComponent : Component<Email>("b8:iam:email:v1")
object McUuidComponent : Component<McUuid>("b8:iam:mc-uuid:v1")
object ProfileComponent : Component<Profile>("b8:test:profile:v1")
object ExpiresSlowComponent : Component<Instant>("b8:util:expires-slow:v1", InstantAsEpochMillis)
object Account : Tag("b8:iam:account:v1")
object SmpMainAccess : Tag("b8:iam:smpmain-access-allowed")
object LobbyDenied : Tag("b8:iam:lobby-access-denied")
object Banned : Tag("b8:mod:banned")
object OwnedBy : Relation("b8:test:owned-by")


/** Records the queries it is asked to run and answers with canned rows. */
class FakeExecutor(private val rows: List<Array<Any?>> = emptyList()) : QueryExecutor {
    val calls = mutableListOf<Triple<String, List<Any?>, ResultMode>>()

    override fun execute(query: net.blocks168.pgecs.ir.QueryIr, params: List<Any?>, mode: ResultMode): List<Array<Any?>> {
        calls += Triple(query.canonicalText, params, mode)
        return when (mode) {
            ResultMode.COUNT -> listOf(arrayOf(rows.size.toLong()))
            ResultMode.FIRST -> rows.take(1)
            else -> rows
        }
    }
}

class QueryDslTest {
    private fun terms(q: Query<*>): String = q.ir.terms.joinToString("\n")

    @Test
    fun `the design doc example`() {
        val addr = "AEA@example.com"
        val q = query(null) {
            val acc by entity
            val mail by entity
            acc has Account
            acc has SmpMainAccess
            acc has !LobbyDenied
            mail childOf acc
            mail has EmailComponent.where { it.address.lowercase() == addr.lowercase() }
            select { acc to mail[EmailComponent].address }
        }
        assertEquals(
            """
            b8:iam:account:v1(${'$'}acc)
            b8:iam:smpmain-access-allowed(${'$'}acc)
            filter !exists(b8:iam:lobby-access-denied(${'$'}acc))
            pgecs:child-of(${'$'}mail, ${'$'}acc)
            b8:iam:email:v1(${'$'}mail) -> ${'$'}mail_email
            filter (pgecs:lowercase(${'$'}mail_email.address) == pgecs:lowercase(?0))
            """.trimIndent(),
            terms(q),
        )
        assertEquals(listOf<Any?>(addr), q.params)
        assertEquals(listOf("acc", "mail_email_address"), q.ir.select.map { it.name })
    }

    @Test
    fun `captured values are parameters, constants are literals`() {
        val min = 3
        val q = query(null) {
            val p by entity
            p has ProfileComponent.where { it.level > min && it.level < 10 }
            select { p }
        }
        assertEquals("filter ((${'$'}p_profile.level > ?0) && (${'$'}p_profile.level < 10))", q.ir.terms[1].toString())
        assertEquals(listOf<Any?>(3L), q.params)
    }

    @Test
    fun `serial names and enums`() {
        val q = query(null) {
            val p by entity
            p has ProfileComponent.where { it.displayName.startsWith("a") && it.role == Role.ADMIN }
            select { p[ProfileComponent].displayName }
        }
        assertEquals(
            "filter (pgecs:startsWith(${'$'}p_profile.display_name, \"a\") && (${'$'}p_profile.role == \"ADMIN\"))",
            q.ir.terms[1].toString(),
        )
    }

    @Test
    fun `null checks, maybe and optional values`() {
        val q = query(null) {
            val mail by entity
            mail has EmailComponent.where { it.verifiedAt != null }
            select { mail }
        }
        assertEquals("filter !(${'$'}mail_email.verifiedAt == null)", q.ir.terms[1].toString())

        val q2 = query(null) {
            val acc by entity
            acc has Account
            acc maybe ProfileComponent
            select { acc to acc[ProfileComponent] }
        }
        assertEquals("optional(b8:test:profile:v1(${'$'}acc) -> ${'$'}acc_profile)", q2.ir.terms[1].toString())
    }

    @Test
    fun `or, wildcards, relations by operator`() {
        val q = query(null) {
            val acc by entity
            val thing by entity
            acc has (Account or SmpMainAccess)
            OwnedBy(thing, acc)
            ChildOf(thing, any)
            select { thing }
        }
        assertEquals(
            """
            filter (exists(b8:iam:account:v1(${'$'}acc)) || exists(b8:iam:smpmain-access-allowed(${'$'}acc)))
            b8:test:owned-by(${'$'}thing, ${'$'}acc)
            pgecs:child-of(${'$'}thing, *)
            """.trimIndent(),
            terms(q),
        )
    }

    @Test
    fun `joins through value variables and entity ids`() {
        val q = query(null) {
            val p by entity
            val link by entity
            val account by entity
            p has Account
            link has McUuidComponent.where { it.uuid == p.id }
            link childOf account
            select { account }
        }
        assertEquals("filter (${'$'}link_mc_uuid.uuid == ${'$'}p)", q.ir.terms[2].toString())
    }

    @Test
    fun `not groups, fixed entities, scalar values and now`() {
        val owner = EntityId(UUID.fromString("018f0000-0000-7000-8000-000000000001"))
        val q = query(null) {
            val e by entity
            e has ExpiresSlowComponent.where { it < now() }
            not { e childOf owner }
            orderBy { e[ExpiresSlowComponent] }
            limit(500)
            select { e }
        }
        assertEquals(
            """
            b8:util:expires-slow:v1(${'$'}e) -> ${'$'}e_expires_slow
            filter (${'$'}e_expires_slow < pgecs:now())
            filter !exists(pgecs:child-of(${'$'}e, ?0))
            """.trimIndent(),
            terms(q),
        )
        assertEquals(listOf<Any?>(owner.id, 500L), q.params)
        assertEquals("[${'$'}e_expires_slow asc nulls first]", q.ir.orderBy.toString())
    }

    @Test
    fun `result methods decode rows`() {
        val id = UUID.randomUUID()
        val ex = FakeExecutor(listOf(arrayOf(id, "aea@example.com")))
        val q = query(ex) {
            val acc by entity
            val mail by entity
            mail childOf acc
            select { acc to mail[EmailComponent].address }
        }
        assertEquals(listOf(EntityId(id) to "aea@example.com"), q.fetch())
        assertEquals(EntityId(id) to "aea@example.com", q.fetchOne())
        assertEquals(EntityId(id) to "aea@example.com", q.first())
        assertEquals(1L, q.count())
        assertEquals(listOf(ResultMode.FETCH, ResultMode.ONE, ResultMode.FIRST, ResultMode.COUNT), ex.calls.map { it.third })
    }

    @Test
    fun `whole component values are decoded with their serializer`() {
        val ex = FakeExecutor(listOf(arrayOf("""{"address":"a@b","verifiedAt":5}""")))
        val q = query(ex) {
            val mail by entity
            select { mail[EmailComponent] }
        }
        assertEquals(Email("a@b", Instant.ofEpochMilli(5)), q.fetchOne())
        assertEquals(ValueType.JSON, (q.ir.select.single().expr as Expr.Var).type)
    }

    @Test
    fun `first is null when there are no rows, fetchOne throws`() {
        val q = query(FakeExecutor()) {
            val acc by entity
            acc has Account
            select { acc }
        }
        assertNull(q.first())
        assertFailsWith<QueryResultException> { q.fetchOne() }
    }

    @Test
    fun `same shape for different captured values`() {
        fun build(addr: String) = query(null) {
            val mail by entity
            mail has EmailComponent.where { it.address == addr }
            select { mail }
        }
        val a = build("a")
        val b = build("b")
        assertEquals(a.ir, b.ir)
        assertEquals(listOf<Any?>("a"), a.params)
        assertEquals(listOf<Any?>("b"), b.params)
    }

    @Test
    fun `errors name the problem`() {
        val e1 = assertFailsWith<QueryBuildException> {
            query(null) {
                val mail by entity
                mail has EmailComponent.where { it.address.hashCode() == 3 }
                select { mail }
            }
        }
        assertTrue("hashCode" in e1.message!!, e1.message)

        val e2 = assertFailsWith<QueryBuildException> {
            query(null) {
                val acc by entity
                acc has Account
                acc has EmailComponent.where { it.address.lowercase().startsWith("x", ignoreCase = true) }
                select { acc }
            }
        }
        assertTrue("ignoreCase" in e2.message!!, e2.message)
    }
}
