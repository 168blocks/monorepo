# 168blocks

Monorepo of the 168blocks Minecraft SMP: Fabric mods, Velocity plugins, the Ktor backend, the Compose
frontend and the shared Kotlin libraries, plus the podman compose network that runs all of it.

* Architecture: [docs/architecture.md](docs/architecture.md)
* Testing (including the end-to-end network tests): [docs/testing.md](docs/testing.md)
* Workflow, layout and conventions: [AGENTS.md](AGENTS.md)

## Quick start

Requires only [Nix](https://nixos.org) (with flakes). For the podman network, the host also needs
rootless containers set up (setuid `newuidmap`/`newgidmap`, `/etc/subuid` and `/etc/subgid`); podman
itself comes from Nix.

```sh
./dev ./gradlew build                          # everything, with tests
BLOCKS_ACCEPT_EULA=true tools/network.sh up    # whole network on podman (accepts the Minecraft EULA)
tools/network.sh test                          # ... and the end-to-end tests
```

Join with a 26.3 client at `localhost:25501` (edge 1; also 25502, 25503). The web frontend is at
http://localhost:8080.

Stack: Java 25, Kotlin 2.4, Gradle 9 (Kotlin DSL), Minecraft 26.3 / Fabric Loader 0.20, Velocity 4.3,
Ktor 3.6, Compose Multiplatform 1.12, PostgreSQL 18, [tinyjcbor](https://github.com/alex-s168/tinyjcbor).
