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
* Server-side agents (see "Server-side assertions" below): `ServerAgent` / `ProxyAgent` in
  `e2e/.../agent/`; `AgentTest` checks that Velocity reports its servers and that both Fabric servers
  report their worlds and run commands (`list`). The Fabric server tests are skipped unless
  `BLOCKS_ACCEPT_EULA=true`.
* `bot.Bot`: the protocol-level bot client (see "Drivers" below), Minecraft 26.3 / protocol 777.
  Offline login through an edge proxy and Velocity (compression, login plugin queries, cookies),
  configuration (known packs, keep-alive, resource packs, code of conduct), play (keep-alive, ping,
  teleport confirm, `player_loaded`, chunk batch acks, movement with `client_tick_end`, chat and
  commands), server switches (`start_configuration`), and the positions of the other players it sees.
  Packet ids are in `bot/PacketIds.kt`; a unit test checks them against the game's own report
  (`src/test/resources/packets-26.3.json`, from `java -DbundlerMainClass=net.minecraft.data.Main -jar
  server.jar --reports`). Formats come from the game's stream codecs (decompiled merged jar in the Loom
  cache). `BotNetworkTest` joins through the edges, checks world and game mode, two bots meeting,
  movement seen by another bot, chat, and `/server survival` and back. It needs the Minecraft servers
  and is skipped unless `BLOCKS_ACCEPT_EULA=true`. The bot has no world model: it does not know the
  terrain, so it can only make moves the caller knows are free.
* Real game clients (`net.blocks168.e2e.client`, see "Game clients" below): `GameClientTest` starts a
  Minecraft 26.3 client, joins through edge 1, waits for the world and checks that its screenshots are
  not blank and stable.

### Game clients (`client("bob")`)

* `mods/e2e-client`: dev-only, client-only Fabric mod, never deployed. A JSON-lines RPC on a free
  localhost port (written to `e2e-rpc.port` in the run directory): `connect`, `awaitReady`,
  `awaitWorld`, `awaitEntity`, `awaitChat`, `chat`, `command`, `look`, `input` (hold keys), `hud`,
  `screenshot`, `quit` (`RpcHandler`). A mixin makes the window always count as focused.
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

Errors are `{"error": "..."}` with 400 (bad request), 404 (unknown player/server/path) or 500.
`ServerAgent` adds `assertOnline`, `assertOffline`, `assertPosition`; `ProxyAgent` adds `assertOn`
(polling with a timeout).

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
