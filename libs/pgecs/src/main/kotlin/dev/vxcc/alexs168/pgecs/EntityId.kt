package dev.vxcc.alexs168.pgecs

import java.util.UUID

/**
 * The id of an entity: a UUID (UUIDv7 for ordinary entities, UUIDv5 of the name for component,
 * relation and capability types).
 *
 * Inside `query { }` a variable (`val acc by entity`) is also an [EntityId], so it can be used where
 * an id is expected, selected (`select { acc }` gives a `Query<EntityId>`) and captured in lambdas.
 * Such a placeholder is never a real entity: [isVariable] is true and using it outside its query
 * throws.
 */
open class EntityId(id: UUID) {
    private val uuid: UUID = id

    /** The entity's UUID. */
    open val id: UUID get() = uuid

    /** Whether this is a query variable rather than a real entity id. */
    open val isVariable: Boolean get() = false

    override fun equals(other: Any?): Boolean =
        this === other || (other is EntityId && !other.isVariable && !isVariable && other.id == id)

    override fun hashCode(): Int = uuid.hashCode()

    override fun toString(): String = uuid.toString()

    companion object {
        @JvmStatic
        fun of(id: UUID): EntityId = EntityId(id)

        @JvmStatic
        fun parse(id: String): EntityId = EntityId(UUID.fromString(id))
    }
}
