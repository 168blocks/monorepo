package net.blocks168.pgecs.dsl

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import net.blocks168.pgecs.ChildOf
import net.blocks168.pgecs.Component
import net.blocks168.pgecs.EntityId
import net.blocks168.pgecs.EntityWriter
import net.blocks168.pgecs.Relation
import net.blocks168.pgecs.Tag
import net.blocks168.pgecs.childOf

/** Records writes, to check what the pgecs-ksp accessors (generated for this test source set) call. */
private class RecordingWriter : EntityWriter {
    override val entity = EntityId(UUID.randomUUID())
    val log = mutableListOf<String>()

    override fun <T> set(component: Component<T>, value: T?) { log += "set ${component.name} = $value" }
    override fun <T> get(component: Component<T>): T? = null
    override fun add(tag: Tag) { log += "add ${tag.name}" }
    override fun remove(tag: Tag) { log += "remove ${tag.name}" }
    override fun add(relation: Relation, target: EntityId) { log += "add ${relation.name} -> $target" }
    override fun remove(relation: Relation, target: EntityId) { log += "remove ${relation.name} -> $target" }
    override fun removeAll(relation: Relation) { log += "removeAll ${relation.name}" }
}

class GeneratedAccessorsTest {
    private val parent = EntityId(UUID.fromString("018f0000-0000-7000-8000-000000000001"))

    @Test
    fun `write accessors and operators`() {
        val w = RecordingWriter()
        with(w) {
            email = Email("aea")
            profile = null
            childOf = parent
            childOf = null
            +Account
            -LobbyDenied
            +OwnedBy(parent)
            -OwnedBy(parent)
        }
        assertEquals(
            listOf(
                "set b8:iam:email:v1 = Email(address=aea, verifiedAt=null)",
                "set b8:test:profile:v1 = null",
                "add pgecs:child-of -> $parent",
                "removeAll pgecs:child-of",
                "add b8:iam:account:v1",
                "remove b8:iam:lobby-access-denied",
                "add b8:test:owned-by -> $parent",
                "remove b8:test:owned-by -> $parent",
            ),
            w.log,
        )
    }

    @Test
    fun `generated infix relations in queries`() {
        val q = query(null) {
            val thing by entity
            val acc by entity
            thing ownedBy acc
            thing childOf parent
            select { thing }
        }
        assertEquals(
            listOf("b8:test:owned-by(\$thing, \$acc)", "pgecs:child-of(\$thing, ?0)"),
            q.ir.terms.map { it.toString() },
        )
        assertEquals(ChildOf.name, "pgecs:child-of")
    }
}
