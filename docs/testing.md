# Testing

Three levels, cheapest first:

| Level | Where | Runs in | Needs |
| --- | --- | --- | --- |
| Unit | `src/test` of every module | `./dev ./gradlew build` | nothing |
| Integration | e.g. `libs/pgecs`, `backend` | `./dev ./gradlew build` | real PostgreSQL from Nix (`TestPostgres`) |
| End to end | `e2e/` | `tools/network.sh test` | the whole network on podman (`deploy/compose.yaml`) |

## Randomized tests of the pgecs query engine

`libs/pgecs-query`'s `RandomQueryTest` generates random first-level IR queries (`FuzzGen`: every kind
of match, optional groups, predicates and expressions of every IR type, aggregates, distinct,
order-by, paging) over random datasets (`FuzzData`, one schema each) and checks the engine, with and
without equality saturation, against a naive reference evaluator (`Oracle`). It also checks
properties that need no oracle: ternary logic partitioning (the rows of `q` are those of `q ∧ p`
plus those of `q ∧ ¬p`), the same shape with other arguments (plan-cache hits), `Mode.COUNT`, and that
the canonical text parses back. A failure is shrunk (`FuzzShrink`) to a small query before it is
reported, with the SQL and the Gradle properties that rerun exactly that case.

The build runs 1500 queries with a fixed seed (about a minute). For a longer run:

```sh
./dev ./gradlew :libs:pgecs-query:test --tests '*RandomQueryTest*' \
  -Ppgecs.fuzz.seed=random -Ppgecs.fuzz.queries=100000 -Ppgecs.fuzz.datasets=50
```

The seed is printed at the end (and in a failure, with `-Ppgecs.fuzz.case=<dataset>:<query>`).

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
  plugin loaded* (the plugin sets the MOTD). This covers: edge Velocity (edge 3 through its PROXY protocol v2 filter) →
  plugin.
* `NetworkTest` also checks the backend through HTTP (health with database, counter persisted in
  PostgreSQL, web frontend served).
* `TunnelTest`: the WireGuard layout from inside, through `ComposeNetwork` (podman, local network
  only): the core namespace reaches PostgreSQL over localhost as `blocks`; a container on `wan` without a
  tunnel reaches neither PostgreSQL nor the Fabric servers; an edge is `blocks_edge` by its tunnel
  address (as `blocks` it is rejected) and reaches the Fabric servers only through the tunnel; with its
  peer removed from the hub an edge refuses logins (a bot), and lets players in again once restored.
  Nothing reaches PostgreSQL through a host port.
* `EdgeNetworkTest` (network plugin, `velocity/network`): a bot logs in through each edge; pings of
  every edge show the network-wide player count; the same name on edge 2 kicks the edge-1 session (and
  twice on one edge keeps the newest); `network drain` on edge 1 transfers a bot to another edge
  (`Bot.followTransfer`) and edge 1 refuses logins until `network undrain`. `TunnelTest` also checks
  the "maintenance" ping of an edge without its tunnel.
* `DnsTest`: the player DNS ([`dns.md`](dns.md)) through `Dns`, a small DNS client (UDP, TCP, EDNS
  client subnet) at `BLOCKS_E2E_DNS`: SOA/NS/NXDOMAIN of the zone, each region's healthy edges (SRV
  ports tell the edges apart, they share 127.0.0.1), `play` by client subnet with fallback and the ECS
  scope echoed, and test edges inserted into `dns.edges` (through `ComposeNetwork`) dropping out when
  their probe fails or they are disabled.
* Server-side agents (see "Server-side assertions" below): `ServerAgent` / `ProxyAgent` in
  `e2e/.../agent/`; `AgentTest` checks that Velocity reports its servers and that both Fabric servers
  report their worlds and run commands (`list`). The Fabric server tests are skipped unless
  `BLOCKS_ACCEPT_EULA=true`.
* `LobbyTest`: a bot joins the lobby at 0.5/-63/0.5, stays at full health and walks a 20x20 square;
  through the agent, the grass floor and free space around the spawn, forced spawn chunks, game
  rules (#26).
* `bot.Bot`: the protocol-level bot client (see "Drivers" below), Minecraft 26.3 / protocol 777.
  Offline login through an edge proxy and Velocity (compression, login plugin queries, cookies),
  configuration (known packs, keep-alive, resource packs, code of conduct), play (keep-alive, ping,
  teleport confirm, `player_loaded`, chunk batch acks, movement with `client_tick_end`, chat and
  commands), server switches (`start_configuration`), and the positions of the other players it sees.
  Packet ids are in `bot/PacketIds.kt`; a unit test checks them against the game's own report
  (`src/test/resources/packets-26.3.json`, from `java -DbundlerMainClass=net.minecraft.data.Main -jar
  server.jar --reports`). Formats come from the game's stream codecs (decompiled merged jar in the Loom
  cache). Its network tests are scenarios (`PlayerScenarioTest`, see "Scenario runner"). The bot has no world model: it does not know the
  terrain, so it can only make moves the caller knows are free.
* Real game clients (`dev.vxcc.b8.e2e.client`, see "Game clients" below): `GameClientTest` starts a
  Minecraft 26.3 client, joins through edge 1, waits for the world and checks that its screenshots are
  not blank and stable.

### Game clients (`client("bob")`)

* `mods/e2e-client`: dev-only, client-only Fabric mod, never deployed. A JSON-lines RPC on a free
  localhost port (written to `e2e-rpc.port` in the run directory): `connect`, `awaitReady`,
  `awaitWorld`, `awaitRendered`, `awaitEntity`, `awaitChat`, `chat`, `command`, `look`, `input` (hold
  keys), `hud`, `screenshot`, `quit` (`RpcHandler`). A mixin makes the window always count as focused,
  another counts rendered frames.
* Screenshots wait for the rendered world, never for a fixed time (#89): `awaitRendered` (`RenderWait`)
  waits until the chunks around the player are loaded, their visible sections compiled, and that has
  stayed unchanged for a few frames; `awaitWorld` and `ScenarioClient.screenshot` include it, and
  `GameClient.renderedScreenshot` also retries a blank frame, failing with the render statistics of
  every attempt. Vanilla's `hasRenderedAllSections` alone is not enough: it is true before the first
  chunk arrives. The clients run without chunk fade-in.
* `:mods:e2e-client:clientLaunch` writes how Loom's `runClient` would start the game to
  `build/e2e-client/launch.json`; `GameClients` (in `e2e/`) starts clients from it, each with its own
  run directory (`e2e/build/e2e-clients/<name>`: `client.log`, `screenshots/`), offline-mode username
  and its own headless sway (`WLR_BACKENDS=headless`, pixman, private `XDG_RUNTIME_DIR`). Nothing
  appears on the desktop; no fixed ports.
* Rendering is Mesa llvmpipe through OpenGL on SDL's Wayland driver, so no GPU is needed. The client
  libraries and Mesa environment come from `blocks-client-env` (flake.nix, `BLOCKS_CLIENT_ENV`), which
  only the client processes run under; the dev shell itself is unchanged.
* `Screenshot`: blank check, a coarse difference (box-filtered to 64 columns) and `assertMatches(name,
  maxDiff)` against `e2e/src/test/screenshots/<name>.png`. References are written only with
  `tools/network.sh test -PupdateScreenshots`. The local worlds use random seeds, so `GameClientTest`
  compares frames with each other rather than with a stored reference.
* Fabric's client game test API was evaluated and not used: it runs tests inside the client in lockstep
  and only connects to its own in-process server (details in issue #3).

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

Implemented as `mods/e2e-agent` (server-only Fabric mod) and `velocity/e2e-agent`: JSON over the JDK's
`com.sun.net.httpserver`, Gson for JSON (shipped by Minecraft and Velocity), so neither has dependencies
of its own. Each listens on `BLOCKS_E2E_AGENT_PORT` inside its container (deploy/compose.yaml; unset
means off) and is published on 127.0.0.1 at `28500` (velocity), `28501` (lobby), `28502` (survival)
`+ 10·slot`; the tests read `BLOCKS_E2E_AGENT_<VELOCITY|LOBBY|SURVIVAL>` (tools/instance.sh). Only
`:deploy:stage` (the local test network) stages them. They are unauthenticated and can run any
command: never deploy them to production.

| Agent | Endpoint | Answer |
| --- | --- | --- |
| Fabric | `GET /status` | `{server, minecraft, tick, players}` |
| Fabric | `GET /players` | `[{uuid, name, dimension, x, y, z, yaw, pitch, gameMode}]` |
| Fabric | `GET /worlds` | `[{id, players}]` |
| Fabric | `POST /command {"command": "list"}` | `{success, result, output: [lines]}` (as the server console) |
| Velocity | `GET /status` | `{version, players}` |
| Velocity | `GET /servers` | `[{name, address, players}]` |
| Velocity | `GET /players` | `[{uuid, name, server}]` (`server` null while not on a backend) |
| Velocity | `POST /send {"player": "alice", "server": "survival"}` | `{status, success, reason}` |
| Velocity | `POST /command {"command": "network drain"}` | `{success}` (as the proxy console) |

Errors are `{"error": "..."}` with 400 (bad request), 404 (unknown player/server/path) or 500.
`ServerAgent` adds `assertOnline`, `assertOffline`, `assertPosition`; `ProxyAgent` adds `assertOn`
(polling with a timeout).

The database is checked from a container in the core namespace (`ComposeNetwork`, `psql`); PostgreSQL
has no host port.

### Design: scenario runner

* JUnit 5 stays the runner (IDE support, Gradle reports). `scenario { }` is a small Kotlin DSL on top:
  structured concurrency (coroutines) for clients acting in parallel, `await…` helpers with timeouts
  instead of sleeps, and on failure it attaches bot logs, client screenshots and `podman logs` of the
  involved services to the report.
* Tests do not rely on state from other tests: each one uses fresh player names, and server state is
  reset through the agent where needed.
* `tools/network.sh test` brings the network up, waits until edges and backend answer, and runs the
  tests. CI does the same on a runner with podman and Nix.

Implemented in `e2e/.../scenario/`:

```kotlin
@NetworkScenario   // @Test + @Tag("network") + ScenarioExtension
fun `second player sees the first in the lobby`() = scenario {
    val alice = bot("alice").connect(edge(1))          // fresh name, e.g. alice_k3x9q
    alice.awaitWorld("minecraft:overworld")
    val lobby = server("lobby")                         // ServerAgent, once it answers
    lobby.assertOnline(alice)
    proxy().assertOn(alice, "lobby")                    // ProxyAgent
    val target = alice.position.copy(y = alice.position.y + 0.15)
    alice.moveTo(target, onGround = false)
    lobby.assertPosition(alice, target, tolerance = 0.01)
    val bob = bot("bob").connect(edge(2))
    bob.awaitSees(alice, target)
}
```

* `scenario(timeout = 5.minutes) { }` runs the block in a coroutine scope on `Dispatchers.IO`:
  `launch` / `async` let players act in parallel. The whole scenario has a timeout, and every await
  has its own. It is skipped (not failed) without `BLOCKS_ACCEPT_EULA=true` unless
  `needsServers = false`.
* `bot(role)` gives a `ScenarioBot`: the protocol-level bot with suspending, cancellable actions
  (`connect` retries while the servers start, `awaitWorld`, `awaitJoins`, `moveTo`/`moveBy`, `chat`,
  `command`, `awaitChat`, `awaitSees`, `switchTo`). `server(name)` / `proxy()` are the agents;
  `assertOnline(bot)`, `assertPosition(bot, …)`, `assertOn(bot, server)`, `send(bot, server)` take
  bots, so tests never spell player names.
* On failure the scenario writes a bundle to `e2e/build/scenario-diagnostics/<test>-<time>/`
  (`BLOCKS_E2E_DIAGNOSTICS` overrides): the failure, a timeline of all steps and bot events, each
  bot's log and state, snapshots of the agents it used, and `podman logs --tail 300` of every container
  of the checkout's compose project (`BLOCKS_COMPOSE_PROJECT`). With `@NetworkScenario` the bundle is
  also attached to the test in the JUnit / Gradle report.
* `client(role)` starts a real game client (`ScenarioClient` on `GameClients`) with a fresh name:
  `join(edge(n), dimension)`, `awaitSees(player)`, `awaitChat`, `chat`, `look`, `hud`, `screenshot`.
  It skips the scenario where clients cannot run, and on failure attaches a last screenshot, its
  screenshots and its log tail. `PlayerScenarioTest` has the README example with it.
* Other kinds of players plug in the same way: a `ScenarioDriver` (closed at the end, asked for
  diagnostics on failure) registered with `register(...)`.
* `switchTo(server)` is done only when the proxy agent reports the bot on that server: a new login
  alone can be Velocity's failover back to the lobby while the target still starts (#43).

### Next steps

Tracked as GitHub issues: protocol-level bot client, e2e server agent mod and Velocity plugin,
headless real-client driver with screenshots, scenario DSL, CI job for the network tests.
