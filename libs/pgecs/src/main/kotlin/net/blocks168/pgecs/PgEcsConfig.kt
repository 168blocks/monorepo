package net.blocks168.pgecs

/**
 * Where pgecs finds its database.
 *
 * [jdbcUrl] is a PostgreSQL JDBC URL, e.g. `jdbc:postgresql://localhost:5432/blocks`.
 */
data class PgEcsConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
) {
    override fun toString(): String = "PgEcsConfig(jdbcUrl=$jdbcUrl, user=$user, password=***)"

    companion object {
        /**
         * Reads `PGECS_URL`, `PGECS_USER` and `PGECS_PASSWORD` (or the given [env] map), as set by
         * deploy/compose.yaml.
         */
        fun fromEnv(env: Map<String, String> = System.getenv()): PgEcsConfig = PgEcsConfig(
            jdbcUrl = env["PGECS_URL"] ?: error("PGECS_URL is not set"),
            user = env["PGECS_USER"] ?: error("PGECS_USER is not set"),
            password = env["PGECS_PASSWORD"] ?: "",
        )
    }
}
