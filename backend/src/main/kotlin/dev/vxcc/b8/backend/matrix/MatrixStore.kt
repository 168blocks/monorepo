package dev.vxcc.b8.backend.matrix

import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.Option
import dev.vxcc.alexs168.pgecs.db.Database
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig

/**
 * Where the bot keeps Trixnity's store (account, access token, Olm/Megolm sessions, device keys, room
 * state): the backend's PostgreSQL, in its own schema next to pgecs. Trixnity's Exposed repositories
 * create and migrate their tables there themselves.
 */
class MatrixStore(private val db: Database, val schema: String = DEFAULT_SCHEMA) {
    init {
        require(schema.matches(Regex("[a-z_][a-z0-9_]*"))) { "invalid schema name: $schema" }
    }

    /** Creates the schema; Trixnity creates its tables when the client starts. */
    fun migrate() = db.tx("matrix:migrate") {
        connection.createStatement().use { it.execute("create schema if not exists $schema") }
    }

    /** Whether the bot has logged in before (Trixnity's account row exists), so no password login is needed. */
    fun hasAccount(): Boolean = db.read("matrix:has-account") {
        val c = connection
        c.prepareStatement(
            "select exists(select 1 from information_schema.tables where table_schema = ? and table_name = 'account')",
        ).use { st ->
            st.setString(1, schema)
            st.executeQuery().use { rs -> rs.next(); rs.getBoolean(1) }
        } && c.createStatement().use { st ->
            st.executeQuery("select exists(select 1 from $schema.account where user_id is not null)")
                .use { rs -> rs.next(); rs.getBoolean(1) }
        }
    }

    /** An R2DBC connection to the same database, with [schema] as its search path. */
    fun database(): R2dbcDatabase {
        val config = db.config
        val url = config.jdbcUrl.removePrefix("jdbc:")
        require(url.startsWith("postgresql://")) { "expected a jdbc:postgresql:// URL, got ${config.jdbcUrl}" }
        val options = ConnectionFactoryOptions.parse("r2dbc:$url").mutate()
            .option(ConnectionFactoryOptions.USER, config.user)
            .option(Option.valueOf("schema"), schema)
            .build()
        return R2dbcDatabase.connect(databaseConfig = R2dbcDatabaseConfig.Builder().apply { connectionFactoryOptions = options })
    }

    companion object {
        const val DEFAULT_SCHEMA = "matrix_bot"
    }
}
