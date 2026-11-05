# Architecture

```
 players ──► edge proxy 1 ─┐
 players ──► edge proxy 2 ─┼─(PROXY v2)─► Velocity (main) ──► lobby server(s)
 players ──► edge proxy 3 ─┘                  │          ├──► main game server (survival)
                                               │          └──► creative host (many servers, one JVM)
                                               ▼
 browser ──► backend (Ktor, serves frontend) ──► PostgreSQL (pgecs)   S3 store (backups, replays)
```

All JVM components share Kotlin code from `libs/` (and so the same data model in PostgreSQL through
pgecs). Java 25 is the minimum: Minecraft 26.3 and Velocity 4.3 require it.

## Components

* **Edge proxies** (3, spread across the world): accept players close to them, terminate TLS, and
  forward to the main Velocity with a PROXY protocol v2 header (Velocity: `haproxy-protocol = true`)
  so it sees real addresses. Locally they are HAProxy in TCP mode (`deploy/edge/haproxy.cfg`). Vanilla
  clients speak plain TCP, so TLS needs a client mod (or a tunnel); whether edges stay HAProxy or
  become our own Kotlin ingest service is open (GitHub issue).
* **Velocity (main)**: one proxy that knows all Minecraft servers; modern forwarding to the Fabric
  servers (FabricProxy-Lite). Our plugins: `velocity/`.
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
