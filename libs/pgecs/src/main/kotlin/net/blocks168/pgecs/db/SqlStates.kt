package net.blocks168.pgecs.db

import java.sql.SQLException

/**
 * SQLSTATEs pgecs reacts to, and the ones its own triggers raise (class `EC`, unused by PostgreSQL).
 */
object SqlStates {
    const val SERIALIZATION_FAILURE = "40001"
    const val DEADLOCK_DETECTED = "40P01"
    const val QUERY_CANCELED = "57014"
    const val LOCK_NOT_AVAILABLE = "55P03"
    const val UNIQUE_VIOLATION = "23505"
    const val IN_FAILED_TRANSACTION = "25P02"

    /** A write outside a `SERIALIZABLE` transaction. */
    const val NOT_SERIALIZABLE = "EC001"
    /** Deleting or changing a reserved entity (the nil entity, component types). */
    const val RESERVED_ENTITY = "EC002"
    /** A row that does not fit its component's kind (tag with a value, relation without target, ...). */
    const val KIND_MISMATCH = "EC003"
    /** A write to a component that is `pending` or `retired`. */
    const val COMPONENT_NOT_ACTIVE = "EC004"
    /** A value that does not match its component's JSON Schema. */
    const val SCHEMA_VIOLATION = "EC005"
    /** A pair that would close a cycle in an acyclic relation. */
    const val CYCLE = "EC006"
    /** A pair whose target is not a child of the relation's `oneOf` parent. */
    const val ONE_OF_VIOLATION = "EC007"
    /** Deleting an entity that is the target of a pair whose relation has `onDeleteTarget = panic`. */
    const val DELETE_PANIC = "EC008"
    /** A catalog write outside freeze. */
    const val CATALOG_WRITE = "EC009"
    /** A misrouted or malformed outbox entry: unknown capability, or a target endpoint without it. */
    const val OUTBOX_ROUTING = "EC010"

    private val RETRYABLE = setOf(SERIALIZATION_FAILURE, DEADLOCK_DETECTED)

    /** SQLSTATE of the first [SQLException] in [t]'s cause chain, including suppressed `next` exceptions. */
    fun of(t: Throwable): String? = sqlExceptions(t).firstNotNullOfOrNull { it.sqlState }

    /**
     * Whether [t] (or a cause) is a serialization failure or deadlock, after which the transaction
     * must be retried from the start.
     */
    fun isRetryable(t: Throwable): Boolean = sqlExceptions(t).any { it.sqlState in RETRYABLE }

    /** Connection-level failures (SQLSTATE class 08). */
    fun isConnectionFailure(t: Throwable): Boolean = sqlExceptions(t).any { it.sqlState?.startsWith("08") == true }

    private fun sqlExceptions(t: Throwable): Sequence<SQLException> = sequence {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var cur: Throwable? = t
        while (cur != null && seen.add(cur)) {
            if (cur is SQLException) {
                yield(cur)
                var next = cur.nextException
                while (next != null && seen.add(next)) {
                    yield(next)
                    next = next.nextException
                }
            }
            cur = cur.cause
        }
    }
}
