# Testing

Three levels, cheapest first:

| Level | Where | Runs in | Needs |
| --- | --- | --- | --- |
| Unit | `src/test` of every module | `./dev ./gradlew build` | nothing |
| Integration | e.g. `libs/pgecs`, `backend` | `./dev ./gradlew build` | real PostgreSQL from Nix (`TestPostgres`) |
| End to end | `e2e/` | `tools/network.sh test` | the whole network on podman (`deploy/compose.yaml`) |

## End-to-end tests of the network

Goal: tests that read like what a player and an operator would check, against the real network
(edge proxies, Velocity, Fabric servers, backend, database), for example:

```kotlin
scenario("second player sees the first in the lobby") {
    val alice = bot("alice").connect(edge(1))
    alice.awaitWorld("minecraft:overworld")
    server("lobby").assertOnline("alice")
    alice.moveTo(10.5, 64.0, 3.5)
    server("lobby").assertPosition("alice", 10.5, 64.0, 3.5, tolerance = 0.1)

    val bob = client("bob").connect(edge(2))   // a real, rendering game client
    bob.awaitEntity("alice")
    bob.screenshot().assertMatches("lobby-alice-visible", maxDiff = 0.02)
}
```

### What exists now (prototype)

* `e2e/` module, tests tagged `network` only run with `-Pe2e` (`tools/network.sh test` passes it;
  `gradlew build` runs only the harness's own `unit` tests).
* `Network`: addresses of the network under test (compose defaults, overridable with
  `BLOCKS_E2E_EDGES` / `BLOCKS_E2E_BACKEND`, so the same tests can run against staging).
* `StatusPing`: the Minecraft server list ping, written against the real protocol. `NetworkTest` pings
  each of the three edge proxies and checks that the answer comes from Velocity *with the example
  plugin loaded* (the plugin sets the MOTD). This covers: edge proxy → PROXY protocol v2 → Velocity →
  plugin.
* `NetworkTest` also checks the backend through HTTP (health with database, counter persisted in
  PostgreSQL, web frontend served).

### Design: drivers

Two kinds of simulated players, because they have very different costs:

1. **Bots** (`bot("alice")`): protocol-level clients, no rendering, many per JVM. Implemented in Kotlin
   in `e2e/` on top of the same packet primitives `StatusPing` uses: handshake → login (offline mode;
   the local network runs Velocity with `online-mode = false`) → configuration (acknowledge known
   packs, registries) → play (keep-alive, teleport confirm, movement, chat). They follow the proxy's
   server switches. Only a small subset of packets is parsed; the rest is skipped by length.
   Cheap enough to log in dozens of players for load and concurrency tests.

   Alternative considered: Mineflayer (Node.js). It lags behind new Minecraft versions, and a second
   language in the test stack costs more than the small subset of the protocol we need.

2. **Game clients** (`client("bob")`): the real Minecraft 26.3 client with Fabric, for everything
   that needs rendering, the client mods, or exact vanilla behaviour ("check that the screen shows
   this"). Run headless under a headless Wayland compositor (sway with `WLR_BACKENDS=headless`, from
   Nix, Mesa software rendering), one per test client. A dev-only Fabric mod (`mods/e2e-client`)
   exposes a small local RPC (JSON over a localhost socket) for: connect to address, wait for world /
   entity / chat, send input, screenshot. Screenshots are compared against stored references with a
   tolerance; references are updated by a Gradle task, never silently.

   Fabric's client game test API (`fabric-client-gametest-api-v1`) is an option for this driver; it
   is built for testing a single client against its own server, so we would still need the connect /
   RPC part. Evaluate it before writing our own bridge.

### Design: server-side assertions

`server("lobby").assertOnline("alice")` needs to look inside the servers. A dev-only server mod
(`mods/e2e-agent`, staged only into the test network) exposes the server state over a small HTTP API
inside the compose network (players, positions, worlds, run command), and the Velocity side gets the
same in a dev-only plugin (which backend each player is on). Tests reach them through a published
port per service. In production deployments these are absent.

The database is checked directly through pgecs, the same way the services use it.

### Design: scenario runner

* JUnit 5 stays the runner (IDE support, Gradle reports). `scenario { }` is a small Kotlin DSL on top:
  structured concurrency (coroutines) for clients acting in parallel, `await…` helpers with timeouts
  instead of sleeps, and on failure it attaches bot logs, client screenshots and `podman logs` of the
  involved services to the report.
* Tests do not rely on state from other tests: each one uses fresh player names, and server state is
  reset through the agent where needed.
* `tools/network.sh test` brings the network up, waits until edges and backend answer, and runs the
  tests. CI does the same on a runner with podman and Nix.

### Next steps

Tracked as GitHub issues: protocol-level bot client, e2e server agent mod and Velocity plugin,
headless real-client driver with screenshots, scenario DSL, CI job for the network tests.
