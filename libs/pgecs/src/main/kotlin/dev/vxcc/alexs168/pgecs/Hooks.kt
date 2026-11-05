package dev.vxcc.alexs168.pgecs

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** What a hook is told: [type] was added to or removed from [entity] (with [target], for a pair). */
data class HookEvent(val entity: EntityId, val type: ComponentType, val target: EntityId?)

/**
 * In-transaction hooks, flecs' `on_add`, `on_set` and `on_remove` (pgecs design, *Hooks run
 * in-transaction*). They run inside the writing `tx`, right when pgecs buffers the write, and may
 * write more (defaults, derived components); if one throws, the transaction rolls back.
 *
 * They only fire for writes pgecs performs through `create { }` / `update(e) { }`: not for rows a
 * database cascade or cleanup policy removes (deleting an entity, a deleted target), nor for rows
 * other code writes in SQL. Side effects of removal belong in observers, which see every change.
 *
 * Telling an add from a set needs to know whether the row existed, so a component with `onAdd` or
 * `onRemove` hooks costs one read per write.
 */
class Hooks internal constructor() {
    private val onAdd = ConcurrentHashMap<String, MutableList<EcsTx.(HookEvent) -> Unit>>()
    private val onRemove = ConcurrentHashMap<String, MutableList<EcsTx.(HookEvent) -> Unit>>()
    private val onSet = ConcurrentHashMap<String, MutableList<EcsTx.(EntityId, Any?) -> Unit>>()

    /** [type] (a tag, component or relation) was added: the entity did not have it (that pair) before. */
    fun onAdd(type: ComponentType, hook: EcsTx.(HookEvent) -> Unit) {
        onAdd.computeIfAbsent(type.name) { CopyOnWriteArrayList() } += hook
    }

    /** [type] was removed by a write (`-Tag`, `this[C] = null`, `remove`, replacing an exclusive pair). */
    fun onRemove(type: ComponentType, hook: EcsTx.(HookEvent) -> Unit) {
        onRemove.computeIfAbsent(type.name) { CopyOnWriteArrayList() } += hook
    }

    /** A value of [component] was written (added or changed). */
    fun <T> onSet(component: Component<T>, hook: EcsTx.(EntityId, T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        onSet.computeIfAbsent(component.name) { CopyOnWriteArrayList() } += hook as EcsTx.(EntityId, Any?) -> Unit
    }

    internal fun watchesPresence(type: ComponentType) = onAdd[type.name] != null || onRemove[type.name] != null

    internal fun added(tx: EcsTx, e: HookEvent) = run { onAdd[e.type.name]?.forEach { tx.it(e) } }

    internal fun removed(tx: EcsTx, e: HookEvent) = run { onRemove[e.type.name]?.forEach { tx.it(e) } }

    internal fun set(tx: EcsTx, entity: EntityId, component: Component<*>, value: Any?) =
        run { onSet[component.name]?.forEach { tx.it(entity, value) } }

    private inline fun run(block: () -> Unit) {
        val d = depth.get()
        check(d < MAX_DEPTH) { "pgecs hooks nested more than $MAX_DEPTH deep: do two hooks trigger each other?" }
        depth.set(d + 1)
        try {
            block()
        } finally {
            depth.set(d)
        }
    }

    private companion object {
        const val MAX_DEPTH = 16
        val depth: ThreadLocal<Int> = ThreadLocal.withInitial { 0 }
    }
}
