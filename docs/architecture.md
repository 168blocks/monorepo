# Architecture

```
 players ──► edge Velocity 1 ──┐                     ┌──► lobby server(s)
 players ──► edge Velocity 2 ──┼─(WireGuard, core)───┼──► main game server (survival)
 players ─► [L4 filter] ─► 3 ──┘          │          └──► creative host (many servers, one JVM)
                                          ▼
 browser ──► backend (Ktor, serves frontend) ──► PostgreSQL (pgecs)   S3 store (backups, replays)
```

All JVM components share Kotlin code from `libs/` (and so the same data model in PostgreSQL through
pgecs). Java 25 is the minimum: Minecraft 26.3 and Velocity 4.3 require it.

## Components

* **Edge Velocity proxies** (3, spread across the world; [design](https://claude.ai/artifact/WDswfCSv1YSxbGT7HWgtzd)):
  players connect to the nearest edge, which authenticates them and modern-forwards straight to the
  Fabric servers (FabricProxy-Lite) through a WireGuard tunnel to the core host. There is no main
  Velocity. An edge may sit behind an L4 DDoS filter that sends PROXY protocol v2; only such an edge
  runs with `haproxy-protocol = true`. Locally (`deploy/compose.yaml`) edge 3 is behind an HAProxy
  stand-in for the filter. Every edge renders its config from `deploy/edge-velocity/velocity.toml`
  and loads our plugins (`velocity/`). The cross-edge player registry is #23.
* **WireGuard** (hub and spoke): the core host is the hub (`10.168.0.1`, UDP 51820), edge N is
  `10.168.1.N`, ops machines `10.168.2.N`; peers and roles in `deploy/wireguard/peers.toml`. PostgreSQL,
  the Fabric servers (lobby 25601, survival 25602) and later the backend's internal port listen only on
  the core's own addresses and `10.168.0.1`; PostgreSQL picks the role by tunnel subnet (no NAT, no
  passwords). Removing a peer on the hub revokes that machine.
* **Lobby servers**: one process each; Fabric + VMP, Async, FerriteCore, our mods.
  The lobby world holds no state: a flat world of one grass layer at y=-64 with the spawn on top of it
  at 0/-63/0, its chunks kept loaded, no time, weather or mobs (`deploy/fabric/lobby`: server.properties
  and the datapack `world/datapacks/blocks168-lobby`). Staging deletes a lobby world generated from
  other files there, so every checkout gets the same lobby.
* **Main game server**: one process; Fabric + C2ME, VMP, ScalableLux, Async, FerriteCore, our mods.
  Its world keeps the 3x3 chunks around the world spawn force-loaded (data pack
  `deploy/fabric/survival/world/datapacks/blocks168-spawn`, #53): with C2ME's chunk system, finding a
  new player's spawn in chunks that still have to load could fail the join.
* **Creative testing servers**: one per player (they can invite others), several `MinecraftServer`
  instances in one JVM, started and stopped quickly. Not created yet (GitHub issue).
* **Backend**: Ktor (`backend/`); HTTP API (model in `libs/api`) and the web frontend.
* **Frontend**: Compose Multiplatform (`frontend/`), web via Kotlin/Wasm, also a desktop build.
* **PostgreSQL**: everything persistent, through **pgecs** (`libs/pgecs`), a fully ACID persistent
  entity component system (prototype: connection only). Design: [`pgecs.md`](pgecs.md). Its query
  planner will optimise with equality saturation: egglog compiled to Wasm and run on the JVM by Endive
  (`libs/pgecs-eqsat`).
* **S3-compatible object store**: backups and replays (SeaweedFS locally).

## Local network

`deploy/compose.yaml` runs all of it on podman; `tools/network.sh` builds, stages and starts it. JVM
services use one Nix-built runtime image (`nix build .#runtime-image`) and mount their staged files
from `deploy/build/stage/<service>`.

It has the production shape, with real tunnels: a `core` container owns the core host's network
namespace and the hub's `wg0`; PostgreSQL, backend, S3 and the Fabric servers join it
(`network_mode: service:core`) and talk over localhost. Each edge is a container with its own namespace
and `wg0` on a `wan` network that only reaches core's WireGuard port (and the published dev ports).
`gradlew :deploy:wireguard` (part of `:deploy:stage`) generates per-checkout keys and renders every
`wg0.conf` into `deploy/build/wireguard/`; `nix/wg-up.sh` (in the runtime image) brings `wg0` up with
the kernel module, or with userspace `wireguard-go` on `/dev/net/tun` where the module is missing. The
containers with a `wg0` need `NET_ADMIN`.
