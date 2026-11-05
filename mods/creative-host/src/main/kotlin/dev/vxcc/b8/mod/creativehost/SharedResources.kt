package dev.vxcc.b8.mod.creativehost

import java.util.concurrent.ConcurrentHashMap

/**
 * Reference counts of resources several servers use at once (the host's datapack resource manager,
 * see [SharedStem]). Anything never [acquire]d is not tracked: [release] allows closing it.
 */
object SharedResources {
    private val users = ConcurrentHashMap<Any, Int>()

    /** Registers one more user of [resource]; the first call counts the owner as well (2 users). */
    fun acquire(resource: Any) {
        users.merge(resource, 2) { n, _ -> n + 1 }
    }

    /** One user is done with [resource]; true if it was the last (or [resource] is not shared) and may close it. */
    fun release(resource: Any): Boolean {
        var last = true
        users.computeIfPresent(resource) { _, n ->
            if (n > 1) { last = false; n - 1 } else null
        }
        return last
    }

    fun users(resource: Any): Int = users[resource] ?: 1
}
