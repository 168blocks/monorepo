# Player DNS

How players find an edge proxy (issue #27, step 5 of the
[edge design](https://claude.ai/artifact/WDswfCSv1YSxbGT7HWgtzd)). Cloudflare is used only for the web
UI; the Minecraft names are served by our own authoritative DNS.

```
 resolver ──UDP/TCP 53──► PowerDNS (authoritative, remote backend)
                              │ HTTP JSON, localhost
                              ▼
                          dns service (dns/, Ktor) ──► PostgreSQL: dns.edges (via pgecs)
                              │ status ping every few seconds
                              ▼
                          edges (as players reach them)
```

## Pieces

* **PowerDNS** (`pdns_server` from Nix, image `nix build .#pdns-image`, config `deploy/dns/pdns.conf`):
  authoritative only, no data of its own. `launch=remote` with the HTTP connector in JSON POST mode
  (`http:url=http://127.0.0.1:8053/dns,post,post_json`). `edns-subnet-processing=yes` passes the EDNS
  client subnet to the backend as `real-remote`. Its packet, query and zone caches are off: answers
  depend on the client and on health that changes within seconds, and every lookup is a localhost
  round trip. DNSSEC is off.
* **dns service** (`dns/`, `blocks.kotlin-jvm`, Ktor): implements the remote backend calls an
  authoritative, non-DNSSEC zone needs (`lookup`, `getDomainInfo`, `getAllDomains`,
  `getDomainMetadata`; anything else answers `false`). It keeps a snapshot of the enabled edges in
  memory, re-read from PostgreSQL every second (`BLOCKS_DNS_REFRESH_MILLIS`); if the database is
  unreachable it keeps answering from the last snapshot. `GET /health` shows the snapshot.
  Settings: environment (`Main.kt`) and the config file (`DnsConfig.kt`, local one
  `deploy/dns/dns.conf`).
* **Health** (`Prober`): every `BLOCKS_DNS_PROBE_SECONDS` (default 10, locally 2) the service does a
  Minecraft status ping to each enabled edge with a `probe` address and refreshes its `heartbeat` when
  it answers. An edge whose heartbeat is older than `BLOCKS_DNS_STALE_SECONDS` (default 30, locally 7)
  is unhealthy and leaves every answer. `enabled = false` takes an edge out by hand.

## Database

The service connects as **`blocks`** (core namespace, like the backend) and owns the schema `dns`
(created on start, `EdgeStore.migrate`); `blocks_ops` may read it. Plain SQL through pgecs' `Database`
(serializable transactions with retries), as the edge registry is small and has no ECS shape yet.

```sql
dns.edges (
    name text primary key,          -- DNS label: <name>.edge.<zone>
    region text not null,           -- eu, us, ...
    address inet not null,          -- public address (A/AAAA)
    port integer not null,          -- public port (SRV)
    probe text,                     -- host:port to status-ping; null: heartbeats come from elsewhere
    enabled boolean not null,       -- false: out of DNS whatever its health
    heartbeat timestamptz,          -- healthy while younger than the staleness limit
    probed_at timestamptz, probe_error text
)
```

Edges in the config (`edge` lines) are upserted at start (name, region, address, port, probe);
`enabled` and the heartbeat are left alone, and rows only in the database stay. Anything that updates
`heartbeat` keeps an edge healthy, so once the edge network plugin (#23, PR #54, `network.edges` with
its own heartbeat) is merged, the edges can heartbeat here themselves or this table can become a view
on theirs; the probe stays useful because it checks the path players take.

## Names

For the zone `<zone>` (locally `168blocks.test`):

| Name | Records |
| --- | --- |
| `<zone>` | SOA (`ns1.<zone>. hostmaster.<zone>. <serial> 3600 600 604800 60`), NS |
| `ns1.<zone>` | A/AAAA of the name server (config `nameserver`) |
| `play.<zone>` | GeoDNS: A/AAAA + TXT of the nearest healthy edges |
| `<region>.<zone>` | A/AAAA of that region's healthy edges only, TXT `region eu: 2 of 2 edges healthy` |
| `_minecraft._tcp.play.<zone>`, `_minecraft._tcp.<region>.<zone>` | SRV `0 10 <port> <edge>.edge.<zone>.` for the same edges |
| `<edge>.edge.<zone>` | A/AAAA of that (enabled) edge, the SRV targets |

* TTL 60 s for edge records (config `ttl`, also the SOA minimum, so negative answers are cached as
  long); 3600 s for SOA, NS and the name servers' addresses. At most `max-answers` (4) edges per answer.
* A region name exists while it is configured or has an enabled edge; a region without healthy edges
  answers only its TXT (NOERROR, no addresses), so players see "no edge there" instead of a stale one.
* The serial moves (to the current Unix time, or one higher) whenever the set of edges or their health
  changes.
* Minecraft clients look up `_minecraft._tcp.<name>` first, so the SRV records also carry ports. The
  local network relies on that: all edges are published on 127.0.0.1 with their own port, so A records
  are deduplicated and only the SRV records tell the edges apart.

### GeoDNS

`play.<zone>` picks a region for the client and answers with its healthy edges; with none, the region's
fallbacks in order (`region asia us eu`), then any region with a healthy edge.

* The client is the EDNS client subnet the resolver sent, else the resolver's address.
* The region comes from a `GeoLocator` (`Geo.kt`). The one in use is `PrefixTable`: longest match over
  the config's `prefix <cidr> <region>` lines, `default-region` when nothing matches. A GeoIP database
  or a latency map can implement the same interface.
* The answer's ECS scope is the client's source prefix: exact for any prefix table, nested ones
  included, at the cost of fewer cache hits in resolvers.

## Deployment

Hosts run Nix but not necessarily NixOS, so production uses the same podman layout as
`deploy/compose.yaml`: `dns` and `pdns` join the core namespace (`network_mode: service:core`), reach
PostgreSQL and each other over localhost, and only PowerDNS's port 53 is public. DNS (UDP and TCP 53)
is the core host's public service besides WireGuard and HTTPS. A second name server can be another
PowerDNS with the same remote backend, for example on an edge reaching the dns service through its
tunnel. Real zone, name servers, prefixes and edges go into that deployment's `dns.conf`.

## Local network and tests

`tools/network.sh up` starts both; PowerDNS answers on `127.0.0.1:$BLOCKS_DNS_PORT` (`10053 + 10·slot`,
`tools/network.sh env`). Edge 1 and 3 are `eu`, edge 2 is `us`, `asia` has none and falls back to `us`;
the documentation prefixes stand in for clients (`192.0.2.0/24` eu, `198.51.100.0/24` us,
`203.0.113.0/24` asia). Without `dig` in the dev shell, `e2e/.../Dns.kt` is the client.

* Unit tests (`./dev ./gradlew :dns:test`): config and prefix table, every name of the zone, the remote
  backend protocol, `EdgeStore` and the prober against a real PostgreSQL (TestPostgres), the snapshot
  through a database outage.
* End to end (`tools/network.sh test`, `e2e/.../DnsTest.kt`): SOA/NS sane and NXDOMAIN with SOA, the
  records of each region, GeoDNS by client subnet with fallback and ECS scope, and an edge dropping out
  when its probe fails or it is disabled.
