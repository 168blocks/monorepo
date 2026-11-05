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
  and loads our plugins (`velocity/`). WireGuard and the cross-edge player registry are #22 and #23.
* **Lobby servers**: one process each; Fabric + VMP, Async, FerriteCore, our mods.
* **Main game server**: one process; Fabric + C2ME, VMP, ScalableLux, Async, FerriteCore, our mods.
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
