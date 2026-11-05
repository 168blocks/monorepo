package net.blocks168.pgecs

import java.sql.Connection
import java.sql.SQLException

/**
 * Entry point of pgecs, a fully ACID, persistent entity component system on PostgreSQL.
 *
 * Prototype: only connecting and checking the connection exist so far. Entities, components,
 * systems and transactions over them are still to be designed.
 *
 * Requires the PostgreSQL extensions in [REQUIRED_EXTENSIONS] (pg_jsonschema, for validating
 * component data against their schemas); [connect] creates them if missing and fails otherwise.
 */
class PgEcs private constructor(
    private val config: PgEcsConfig,
    private val connection: Connection,
) : AutoCloseable {

    /** Information about the connected server, from a round trip to it. */
    data class ServerInfo(val version: String, val database: String)

    /** Runs a query on the server and reports what it is connected to. */
    fun ping(): ServerInfo = synchronized(connection) {
        connection.createStatement().use { st ->
            st.executeQuery("select current_setting('server_version'), current_database()").use { rs ->
                check(rs.next()) { "empty result from ping" }
                ServerInfo(version = rs.getString(1), database = rs.getString(2))
            }
        }
    }

    /**
     * Runs [block] in one serializable transaction, committing when it returns and rolling back
     * when it throws.
     */
    fun <T> transaction(block: (Connection) -> T): T = synchronized(connection) {
        connection.autoCommit = false
        connection.transactionIsolation = Connection.TRANSACTION_SERIALIZABLE
        try {
            block(connection).also { connection.commit() }
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    val isClosed: Boolean get() = connection.isClosed

    override fun close() = connection.close()

    override fun toString(): String = "PgEcs(${config.jdbcUrl})"

    companion object {
        /** Extensions pgecs needs in its database. */
        val REQUIRED_EXTENSIONS = listOf("pg_jsonschema")

        private fun ensureExtensions(connection: Connection) {
            connection.createStatement().use { st ->
                for (ext in REQUIRED_EXTENSIONS) {
                    val available = st.executeQuery("select 1 from pg_available_extensions where name = '$ext'")
                        .use { it.next() }
                    if (!available) {
                        throw SQLException(
                            "PostgreSQL extension $ext is not installed on the server; pgecs requires it " +
                                "(the Nix dev shell and deploy/compose.yaml provide a PostgreSQL with it)",
                        )
                    }
                    val installed = st.executeQuery("select 1 from pg_extension where extname = '$ext'").use { it.next() }
                    // needs a privileged role; deployments create it up front (nix/postgres-entrypoint.sh)
                    if (!installed) st.execute("create extension $ext")
                }
            }
        }

        /**
         * [connect], retrying for up to [timeoutMillis] while the server is not reachable yet (e.g. while
         * the database container starts).
         */
        fun connectWithRetry(config: PgEcsConfig, timeoutMillis: Long = 60_000, intervalMillis: Long = 1_000): PgEcs {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (true) {
                try {
                    return connect(config)
                } catch (e: SQLException) {
                    // 08xxx: connection exceptions; anything else (auth, missing extension) is final
                    if (e.sqlState?.startsWith("08") != true || System.currentTimeMillis() >= deadline) throw e
                    Thread.sleep(intervalMillis)
                }
            }
        }

        /** Opens a connection to [config]'s database. Throws [SQLException] when that fails. */
        fun connect(config: PgEcsConfig): PgEcs {
            // Explicit load: in Fabric and Velocity the driver is not on the system class path, so
            // java.sql.DriverManager's service loader would not find it.
            val driver = org.postgresql.Driver()
            val props = java.util.Properties().apply {
                setProperty("user", config.user)
                setProperty("ApplicationName", "pgecs")
            }
            val connection = driver.connect(config.jdbcUrl, props)
                ?: throw SQLException("not a PostgreSQL JDBC URL: ${config.jdbcUrl}")
            try {
                ensureExtensions(connection)
            } catch (e: SQLException) {
                connection.close()
                throw e
            }
            return PgEcs(config, connection)
        }
    }
}
