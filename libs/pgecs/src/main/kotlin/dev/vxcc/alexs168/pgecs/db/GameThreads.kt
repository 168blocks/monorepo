package dev.vxcc.alexs168.pgecs.db

import java.util.concurrent.ConcurrentHashMap

/**
 * Threads that must never block on the database, such as every `MinecraftServer` thread. The blocking
 * [Database.tx] and [Database.read] throw [IllegalStateException] on them; use `txAsync` / `readAsync`.
 */
object GameThreads {
    private val ids: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    @JvmStatic
    fun register(thread: Thread = Thread.currentThread()) {
        ids.add(thread.threadId())
    }

    @JvmStatic
    fun unregister(thread: Thread = Thread.currentThread()) {
        ids.remove(thread.threadId())
    }

    @JvmStatic
    fun isGameThread(thread: Thread = Thread.currentThread()): Boolean =
        ids.isNotEmpty() && thread.threadId() in ids
}
