package dev.vxcc.b8.backend

import dev.vxcc.alexs168.pgecs.db.Database

/** The example counter the frontend shows. */
interface CounterStore {
    fun get(): Long
    fun increment(): Long
}

/** Keeps the counter in PostgreSQL, so it survives restarts and is shared by all backend instances. */
class PgCounterStore(private val db: Database) : CounterStore {
    fun migrate() = db.tx("example:counter-migrate") {
        connection.createStatement().use { st ->
            st.execute("create table if not exists example_counter (id int primary key, value bigint not null)")
            st.execute("insert into example_counter values (1, 0) on conflict do nothing")
        }
    }

    override fun get(): Long = db.read("example:counter-get") {
        connection.createStatement().use { st ->
            st.executeQuery("select value from example_counter where id = 1").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    override fun increment(): Long = db.tx("example:counter-increment") {
        connection.createStatement().use { st ->
            st.executeQuery("update example_counter set value = value + 1 where id = 1 returning value")
                .use { rs -> rs.next(); rs.getLong(1) }
        }
    }
}
