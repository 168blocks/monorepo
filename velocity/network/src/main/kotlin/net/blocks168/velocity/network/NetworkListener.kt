package net.blocks168.velocity.network

import net.blocks168.pgecs.PgEcsConfig
import org.postgresql.PGConnection
import java.sql.Connection
import java.util.Properties

/**
 * LISTENs on [NetworkStore.CHANNEL] on its own connection (a session waiting for notifications cannot
 * run the store's queries) and hands every message to [handle]. Reconnects after failures; while not
 * listening, [listening] is false.
 */
class NetworkListener(
    private val config: PgEcsConfig,
    private val handle: (NetworkMessage) -> Unit,
    private val log: (String, Throwable?) -> Unit,
) : AutoCloseable {
    @Volatile var listening = false; private set
    @Volatile private var running = true
    @Volatile private var connection: Connection? = null
    private val thread = Thread(::run, "blocks168-network-listen").apply { isDaemon = true }

    fun start() = apply { thread.start() }

    private fun run() {
        while (running) {
            try {
                val c = org.postgresql.Driver().connect(
                    config.jdbcUrl,
                    Properties().apply {
                        setProperty("user", config.user)
                        setProperty("ApplicationName", "${config.applicationName}-listen")
                        setProperty("connectTimeout", "${config.timeouts.connect.seconds}")
                        setProperty("tcpKeepAlive", "true")
                    },
                ) ?: error("not a PostgreSQL JDBC URL: ${config.jdbcUrl}")
                connection = c
                c.createStatement().use { it.execute("listen ${NetworkStore.CHANNEL}") }
                listening = true
                val pg = c.unwrap(PGConnection::class.java)
                while (running) {
                    for (n in pg.getNotifications(1000) ?: emptyArray()) {
                        val message = runCatching { NetworkMessage.decode(n.parameter) }
                            .getOrElse { log("bad network message: ${n.parameter}", it); continue }
                        runCatching { handle(message) }.onFailure { log("handling $message", it) }
                    }
                    // getNotifications only notices a dead connection when it sends something
                    if (!c.isValid(5)) error("listen connection lost")
                }
            } catch (e: Throwable) {
                if (running) log("listen connection failed, retrying: $e", null)
            } finally {
                listening = false
                runCatching { connection?.close() }
                connection = null
            }
            if (running) Thread.sleep(2000)
        }
    }

    override fun close() {
        running = false
        runCatching { connection?.close() }
        thread.join(3000)
    }
}
