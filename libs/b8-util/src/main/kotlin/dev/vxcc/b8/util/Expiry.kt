package dev.vxcc.b8.util

import java.time.Duration
import java.time.Instant
import dev.vxcc.alexs168.pgecs.Component
import dev.vxcc.alexs168.pgecs.Ecs
import dev.vxcc.alexs168.pgecs.EntityId
import dev.vxcc.alexs168.pgecs.dsl.QueryBuilder
import dev.vxcc.alexs168.pgecs.serial.InstantAsEpochMillis

/**
 * The entity expires at this instant and is deleted by the slow sweep (every 5 minutes). Anything
 * that should expire on its own is its own entity (an OTP, an invite, a temporary ban), so expiry
 * always deletes the entity, and its children through `pgecs:child-of` (docs/pgecs.md, *Expiry*).
 */
object ExpiresSlowComponent : Component<Instant>("b8:util:expires-slow:v1", InstantAsEpochMillis) {
    init {
        index("at") { it }
    }
}

/** Like [ExpiresSlowComponent], swept every few seconds, for short-lived entities. */
object ExpiresFastComponent : Component<Instant>("b8:util:expires-fast:v1", InstantAsEpochMillis) {
    init {
        index("at") { it }
    }
}

/**
 * The expiry sweeps, as pgecs systems. Expiry is cleanup, not a filter: queries that must ignore
 * expired but not yet swept entities say so ([notExpired]), and security checks (OTPs) check the
 * instant inside their own transaction.
 */
object Expiry {
    const val SLOW = "b8:util:expire-slow"
    const val FAST = "b8:util:expire-fast"

    /** Registers both sweeps on [ecs] (after freeze); start them with `ecs.systems.start()`. */
    @JvmStatic
    @JvmOverloads
    fun install(
        ecs: Ecs,
        slowEvery: Duration = Duration.ofMinutes(5),
        fastEvery: Duration = Duration.ofSeconds(5),
        batchSize: Int = 500,
    ) {
        ecs.systems.system(SLOW, slowEvery, batchSize, {
            val e by entity
            e has ExpiresSlowComponent.where { it < now() }
            select { e }
        }) { rows -> rows.forEach { delete(it) } }
        ecs.systems.system(FAST, fastEvery, batchSize, {
            val e by entity
            e has ExpiresFastComponent.where { it < now() }
            select { e }
        }) { rows -> rows.forEach { delete(it) } }
    }
}

/**
 * `e.notExpired()` inside `query { }`: [entity] has no expiry instant before now (it may have none).
 */
fun QueryBuilder.notExpired(entity: EntityId) {
    not { has(entity, ExpiresSlowComponent) { it < now() } }
    not { has(entity, ExpiresFastComponent) { it < now() } }
}
