package dev.vxcc.b8.dns

import dev.vxcc.alexs168.pgecs.db.Database
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * The edges in PostgreSQL (`dns.edges`), through pgecs' [Database] (transactions with retries). The
 * service connects as `blocks` from the core namespace and owns the `dns` schema; `blocks_ops` may read
 * it. An edge is answered with while it is `enabled` and its `heartbeat` is younger than the staleness
 * limit. The heartbeat comes from the service's own probe ([Prober], for edges with a `probe`
 * address) or from anything else that updates it (later the edges themselves, #54).
 */
class EdgeStore(private val db: Database) {
    fun migrate() = db.tx("dns:migrate") {
        connection.createStatement().use { st ->
            st.execute("select pg_advisory_xact_lock(hashtext('dns-schema'))")
            st.execute("create schema if not exists dns")
            st.execute(
                """
                create table if not exists dns.edges (
                    name        text primary key,
                    region      text        not null,
                    -- public address players reach the edge at (A/AAAA), and its port (SRV)
                    address     inet        not null,
                    port        integer     not null default 25565 check (port between 1 and 65535),
                    -- host:port the dns service status-pings for the heartbeat; null: heartbeats come from elsewhere
                    probe       text,
                    -- false takes the edge out of DNS by hand (maintenance), whatever its health
                    enabled     boolean     not null default true,
                    heartbeat   timestamptz,
                    probed_at   timestamptz,
                    probe_error text
                )
                """.trimIndent(),
            )
            // read-only for the ops peers, where the role exists (not in every test database)
            st.execute(
                """
                do $$ begin
                    if exists (select from pg_roles where rolname = 'blocks_ops') then
                        grant usage on schema dns to blocks_ops;
                        grant select on dns.edges to blocks_ops;
                    end if;
                end $$
                """.trimIndent(),
            )
        }
    }

    /**
     * Upserts the configured edges: name, region, address, port and probe come from the config; `enabled`
     * and the heartbeat stay as they are. Edges only in the database are left alone.
     */
    fun seed(edges: List<SeedEdge>) {
        if (edges.isEmpty()) return
        db.tx("dns:seed") {
            connection.prepareStatement(
                """
                insert into dns.edges (name, region, address, port, probe) values (?, ?, ?::inet, ?, ?)
                on conflict (name) do update set region = excluded.region, address = excluded.address,
                    port = excluded.port, probe = excluded.probe
                """.trimIndent(),
            ).use { ps ->
                for (e in edges) {
                    ps.setString(1, e.name)
                    ps.setString(2, e.region)
                    ps.setString(3, e.address.hostAddress)
                    ps.setInt(4, e.port)
                    ps.setString(5, e.probe?.toString())
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    /** The enabled edges; healthy when their heartbeat is younger than [stale] (by the database's clock). */
    fun load(stale: Duration): List<Edge> = db.read("dns:load") {
        connection.prepareStatement(
            """
            select name, region, host(address), port, coalesce(heartbeat > now() - make_interval(secs => ?), false)
            from dns.edges where enabled order by name
            """.trimIndent(),
        ).use { ps ->
            ps.setDouble(1, stale.toMillis() / 1000.0)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(Edge(rs.getString(1), rs.getString(2), parseAddress(rs.getString(3)), rs.getInt(4), rs.getBoolean(5)))
                    }
                }
            }
        }
    }

    /** Enabled edges with a probe address */
    fun probes(): Map<String, HostPort> = db.read("dns:probes") {
        connection.createStatement().use { st ->
            st.executeQuery("select name, probe from dns.edges where enabled and probe is not null").use { rs ->
                buildMap {
                    while (rs.next()) {
                        runCatching { HostPort.parse(rs.getString(2)) }.onSuccess { put(rs.getString(1), it) }
                    }
                }
            }
        }
    }

    /** Records probe results: a success refreshes the heartbeat, a failure only notes the error. */
    fun recordProbes(results: Map<String, String?>, at: Instant = Instant.now()) {
        if (results.isEmpty()) return
        db.tx("dns:probe") {
            connection.prepareStatement(
                """
                update dns.edges set probed_at = ?, probe_error = ?,
                    heartbeat = case when ? then now() else heartbeat end
                where name = ?
                """.trimIndent(),
            ).use { ps ->
                for ((name, error) in results) {
                    ps.setTimestamp(1, Timestamp.from(at))
                    ps.setString(2, error)
                    ps.setBoolean(3, error == null)
                    ps.setString(4, name)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }
}
