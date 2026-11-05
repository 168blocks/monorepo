package dev.vxcc.b8.velocity.network

import dev.vxcc.alexs168.pgecs.db.Database
import dev.vxcc.alexs168.pgecs.PgEcsConfig
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * Every query of the network plugin, against the `network` schema ([NetworkSchema]), through a pgecs
 * [Database] (a one-connection pool), reopened after failures; each call is one transaction. Calls throw [SQLException]
 * when the database is unreachable: the plugin then goes into maintenance.
 */
class NetworkStore(private val config: PgEcsConfig, val edge: String) : AutoCloseable {
    data class OnlinePlayer(val uuid: UUID, val name: String, val edge: String, val server: String?)
    data class Edge(val name: String, val address: String, val players: Int, val draining: Boolean)
    data class Ban(val name: String, val reason: String)

    private var db: Database? = null

    /** One transaction (pgecs retries serialization failures); connects on first use and after failures */
    fun <T> tx(block: (Connection) -> T): T {
        val database = synchronized(this) { db ?: Database.connect(config).also { db = it } }
        try {
            return database.tx("network") { block(connection) }
        } catch (e: SQLException) {
            // an unreachable server: start over with a fresh pool next time
            if (e.sqlState == null || e.sqlState!!.startsWith("08")) synchronized(this) {
                if (db === database) { runCatching { database.close() }; db = null }
            }
            throw e
        }
    }

    /** Applied schema version (0 before the migrations ran) */
    fun schemaVersion(): Int = tx { c ->
        val exists = c.createStatement().use { st ->
            st.executeQuery("select to_regclass('network.migrations') is not null").use { it.next(); it.getBoolean(1) }
        }
        if (exists) NetworkSchema.version(c) else 0
    }

    /** Players online on all edges (rows with a fresh heartbeat) */
    fun onlineCount(): Int = tx { c ->
        c.createStatement().use { st ->
            st.executeQuery("select count(*) from network.online_players where heartbeat > now() - interval '$EXPIRY_SECONDS seconds'")
                .use { it.next(); it.getInt(1) }
        }
    }

    fun onlinePlayers(): List<OnlinePlayer> = tx { c ->
        c.createStatement().use { st ->
            st.executeQuery(
                "select uuid, name, edge, server from network.online_players " +
                    "where heartbeat > now() - interval '$EXPIRY_SECONDS seconds' order by lower(name)",
            ).use { rs ->
                buildList { while (rs.next()) add(OnlinePlayer(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4))) }
            }
        }
    }

    fun findOnline(name: String): OnlinePlayer? = onlinePlayers().firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** The ban that keeps [name] / [uuid] out, if any */
    fun ban(name: String, uuid: UUID): Ban? = tx { c ->
        c.prepareStatement("select name, reason from network.bans where name = lower(?) or uuid = ? limit 1").use { st ->
            st.setString(1, name); st.setObject(2, uuid)
            st.executeQuery().use { rs -> if (rs.next()) Ban(rs.getString(1), rs.getString(2)) else null }
        }
    }

    fun addBan(name: String, uuid: UUID?, reason: String, by: String) = tx { c ->
        c.prepareStatement(
            "insert into network.bans (name, uuid, reason, created_by) values (lower(?), ?, ?, ?) " +
                "on conflict (name) do update set uuid = coalesce(excluded.uuid, bans.uuid), reason = excluded.reason, " +
                "created_by = excluded.created_by, created_at = now()",
        ).use { st -> st.setString(1, name); st.setObject(2, uuid); st.setString(3, reason); st.setString(4, by); st.executeUpdate() }
    }

    fun removeBan(name: String): Boolean = tx { c ->
        c.prepareStatement("delete from network.bans where name = lower(?)").use { st -> st.setString(1, name); st.executeUpdate() > 0 }
    }

    /**
     * Registers a login on this edge as the player's only session (the newest login wins) and tells the
     * other edges (and this one) to kick older sessions of the same player.
     */
    fun register(uuid: UUID, name: String, session: UUID) = tx { c ->
        c.prepareStatement(
            "insert into network.online_players (uuid, name, edge, server, session) values (?, ?, ?, null, ?) " +
                "on conflict (uuid) do update set name = excluded.name, edge = excluded.edge, server = null, " +
                "session = excluded.session, since = now(), heartbeat = now()",
        ).use { st -> st.setObject(1, uuid); st.setString(2, name); st.setString(3, edge); st.setObject(4, session); st.executeUpdate() }
        notify(c, NetworkMessage.Kick(uuid, exceptSession = session, reason = "You logged in from another location"))
    }

    /** Removes the player's row, unless a newer session replaced it */
    fun unregister(uuid: UUID, session: UUID) = tx { c ->
        c.prepareStatement("delete from network.online_players where uuid = ? and session = ?").use { st ->
            st.setObject(1, uuid); st.setObject(2, session); st.executeUpdate()
        }
    }

    fun setServer(uuid: UUID, session: UUID, server: String) = tx { c ->
        c.prepareStatement("update network.online_players set server = ?, heartbeat = now() where uuid = ? and session = ?").use { st ->
            st.setString(1, server); st.setObject(2, uuid); st.setObject(3, session); st.executeUpdate()
        }
    }

    /** A player connected to this edge, for [heartbeat] */
    data class Session(val uuid: UUID, val name: String, val session: UUID, val server: String?)

    /**
     * Refreshes this edge's row and its players' rows (re-creating rows that expired, e.g. after a
     * database outage), and deletes expired rows of any edge. Returns the sessions that a newer login
     * elsewhere replaced: those players must be disconnected.
     */
    fun heartbeat(address: String, draining: Boolean, sessions: List<Session>): List<Session> = tx { c ->
        c.prepareStatement(
            "insert into network.edges (name, address, players, draining, heartbeat) values (?, ?, ?, ?, now()) " +
                "on conflict (name) do update set address = excluded.address, players = excluded.players, " +
                "draining = excluded.draining, heartbeat = now()",
        ).use { st -> st.setString(1, edge); st.setString(2, address); st.setInt(3, sessions.size); st.setBoolean(4, draining); st.executeUpdate() }
        c.createStatement().use {
            it.executeUpdate("delete from network.online_players where heartbeat < now() - interval '$EXPIRY_SECONDS seconds'")
        }
        val superseded = ArrayList<Session>()
        c.prepareStatement(
            "insert into network.online_players (uuid, name, edge, server, session) values (?, ?, ?, ?, ?) " +
                "on conflict (uuid) do update set heartbeat = now(), server = excluded.server " +
                "where online_players.session = excluded.session",
        ).use { st ->
            for (s in sessions) {
                st.setObject(1, s.uuid); st.setString(2, s.name); st.setString(3, edge); st.setString(4, s.server); st.setObject(5, s.session)
                if (st.executeUpdate() == 0) superseded += s
            }
        }
        superseded
    }

    fun setDraining(draining: Boolean) = tx { c ->
        c.prepareStatement("update network.edges set draining = ? where name = ?").use { st ->
            st.setBoolean(1, draining); st.setString(2, edge); st.executeUpdate()
        }
    }

    /** Live edges other than this one that accept players, emptiest first */
    fun drainTargets(): List<Edge> = tx { c ->
        c.prepareStatement(
            "select name, address, players, draining from network.edges where name <> ? and not draining " +
                "and heartbeat > now() - interval '$EXPIRY_SECONDS seconds' order by players, name",
        ).use { st ->
            st.setString(1, edge)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(Edge(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4))) } }
        }
    }

    /** Sends [message] to every edge (including this one) */
    fun publish(message: NetworkMessage) = tx { c -> notify(c, message) }

    private fun notify(c: Connection, message: NetworkMessage) {
        c.prepareStatement("select pg_notify(?, ?)").use { st ->
            st.setString(1, CHANNEL); st.setString(2, message.encode()); st.execute()
        }
    }

    @Synchronized
    override fun close() {
        runCatching { db?.close() }
        db = null
    }

    companion object {
        const val CHANNEL = "network"
        /** online rows without a heartbeat for this long are gone (their edge died) */
        const val EXPIRY_SECONDS = 30
    }
}
