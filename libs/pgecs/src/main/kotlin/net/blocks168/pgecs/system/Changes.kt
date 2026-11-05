package net.blocks168.pgecs.system

import java.util.UUID
import net.blocks168.pgecs.Component
import net.blocks168.pgecs.ComponentType
import net.blocks168.pgecs.EntityId
import net.blocks168.pgecs.Relation
import net.blocks168.pgecs.Tag
import net.blocks168.pgecs.serial.PgecsJson

/** What happened to one component row (`ecs_change.op`). */
enum class ChangeOp(val sql: String) {
    /** The row was added. */
    INSERT("insert"),

    /** The row's value changed. */
    UPDATE("update"),

    /** The row was removed, directly, by deleting its entity, or by a cascade or cleanup policy. */
    DELETE("delete"),
    ;

    companion object {
        fun ofSql(s: String): ChangeOp = entries.first { it.sql == s }
    }
}

/** One `ecs_change` row: a component row of [EntityChange.entity] that changed. */
class ChangeRow(
    val rel: String,
    /** The pair's target; null for plain components and tags. */
    val target: EntityId?,
    val op: ChangeOp,
    /** The value before the change, as JSON text (for [ChangeOp.UPDATE] and [ChangeOp.DELETE] of values). */
    val oldValue: String?,
) {
    override fun toString(): String = "$op $rel" + (target?.let { "($it)" } ?: "")
}

/**
 * The changes one transaction made to one entity, as an observer receives them (pgecs design,
 * *How a removal observer works*). [old] is the entity as it was before the transaction, as far as
 * the changed rows tell: rows the transaction removed or updated, with their old values. That stays
 * readable even when the entity itself is gone.
 */
class EntityChange internal constructor(
    val entity: EntityId,
    /** The id (`xid8`) of the transaction that made the changes. */
    val xid: Long,
    val rows: List<ChangeRow>,
) {
    private fun of(type: ComponentType) = rows.filter { it.rel == type.name }

    /** Whether the transaction added [type] (any pair, for a relation) that the entity did not have before. */
    fun added(type: ComponentType): Boolean = firstOps(type).any { it.op == ChangeOp.INSERT } && lastOps(type).any { it.op != ChangeOp.DELETE }

    /** Whether the transaction removed [type] (any pair, for a relation) that the entity had before. */
    fun removed(type: ComponentType): Boolean = firstOps(type).any { it.op != ChangeOp.INSERT } && lastOps(type).any { it.op == ChangeOp.DELETE }

    /** Whether the transaction changed the value of [type] that the entity had before and still has. */
    fun updated(type: ComponentType): Boolean = of(type).groupBy { it.target }.values.any { rs ->
        rs.first().op == ChangeOp.UPDATE && rs.last().op != ChangeOp.DELETE
    }

    /** Whether any row of [type] changed. */
    fun touched(type: ComponentType): Boolean = of(type).isNotEmpty()

    private fun firstOps(type: ComponentType) = of(type).groupBy { it.target }.values.map { it.first() }

    private fun lastOps(type: ComponentType) = of(type).groupBy { it.target }.values.map { it.last() }

    /** The entity before the transaction. */
    val old: Old = Old()

    inner class Old internal constructor() {
        /** Rows that existed before the transaction: the first change of each key, unless it was an insert. */
        private val before: Map<Pair<String, EntityId?>, ChangeRow> = rows.groupBy { it.rel to it.target }
            .mapValues { it.value.first() }
            .filterValues { it.op != ChangeOp.INSERT }

        /** The old value of [component], if the transaction changed or removed it. */
        operator fun <T> get(component: Component<T>): T? =
            before[component.name to null]?.oldValue?.let { PgecsJson.decodeFromString(component.serializer, it) }

        /** The old target of exclusive [relation], if the transaction changed or removed its pair. */
        operator fun get(relation: Relation): EntityId? {
            require(relation.exclusive) { "relation ${relation.name} is not exclusive; use targets" }
            return targets(relation).firstOrNull()
        }

        /** Old targets of [relation] whose pairs the transaction removed. */
        fun targets(relation: Relation): List<EntityId> =
            before.keys.filter { it.first == relation.name }.mapNotNull { it.second }

        /** Whether the entity had [tag] and the transaction removed it. */
        fun had(tag: Tag): Boolean = before.containsKey(tag.name to null)
    }

    override fun toString(): String = "EntityChange($entity, xid=$xid, $rows)"

    internal companion object {
        val NIL: UUID = UUID(0, 0)
    }
}
