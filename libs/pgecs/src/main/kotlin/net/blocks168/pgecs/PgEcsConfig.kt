package net.blocks168.pgecs

/**
 * Where pgecs finds its database.
 *
 * [jdbcUrl] is a PostgreSQL JDBC URL, e.g. `jdbc:postgresql://localhost:5432/blocks`. There are no
 * passwords: the server decides by where a connection comes from which [user] (role) it may use
 * (pg_hba.conf, see nix/postgres-entrypoint.sh).
 */
data class PgEcsConfig(
    val jdbcUrl: String,
    val user: String,
) {
    companion object {
        /**
         * Reads `PGECS_URL` and `PGECS_USER` (or the given [env] map), as set by deploy/compose.yaml.
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): PgEcsConfig = PgEcsConfig(
            jdbcUrl = env["PGECS_URL"] ?: error("PGECS_URL is not set"),
            user = env["PGECS_USER"] ?: error("PGECS_USER is not set"),
        )
    }
}
