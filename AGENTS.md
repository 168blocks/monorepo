# Notes for agents (and humans)

## Workflow: always GitHub issues and pull requests

All work goes through issues and pull requests in `168blocks/monorepo`, using the GitHub CLI (`gh`).
Never push to `main` directly.

1. **Issue first.** Every task gets an issue before work starts, one issue per independent item:
   `gh issue create --title "..." --body "..."`. Look for an existing one first: `gh issue list`,
   `gh issue list --search "..."`. Items the user wants recorded but not done yet get the `later` label.
2. **Branch per issue**, from an up-to-date `main`: `git switch -c <issue-number>-<short-slug> origin/main`.
3. **Commits** reference the issue (`Refs #12`). Keep them focused; one logical change each.
4. **Test before the PR**: `./dev ./gradlew build`, and `tools/network.sh test` for anything touching the
   network (proxies, servers, mods, backend, deploy). Say in the PR what you ran and what you could not.
5. **Pull request** with `Fixes #N` in the body: `gh pr create --fill --body "Fixes #N ..."`.
   Check CI and review with `gh pr checks`, `gh pr view --comments`; address review comments with new
   commits on the same branch.
6. Merge only when asked to (or when the issue says so): `gh pr merge --squash --delete-branch`.

Use `gh issue comment N` to record findings, decisions and blockers on the issue, so the next person
(or agent) can pick it up.

## Environment: everything runs through Nix

Nothing may depend on the developer's system beyond Nix. The dev shell (`flake.nix`) provides the
JDK, Node.js, Binaryen, PostgreSQL (with pg_jsonschema, `nix/pg_jsonschema.nix`), podman and
podman-compose, `gh`, ... Run every command through it:

```sh
./dev ./gradlew build            # compile + all unit/integration tests (alias for nix develop -c ...)
./dev ./gradlew :backend:run     # needs PGECS_URL etc., see backend/
tools/network.sh test            # whole network on podman + end-to-end tests
```

or enter it once with `nix develop` (or direnv: `.envrc`). Gradle never downloads a JDK
(`gradle.properties`); Kotlin/Wasm uses Node.js and Binaryen from Nix, not downloaded ones, because
downloaded binaries are glibc builds that do not run on every host (the main dev machine is musl-based).

### Podman

`podman` in the dev shell is Nix's, wrapped (`nix/podman.nix`) with its own configuration and storage
(`~/.local/share/168blocks/podman`) and file locks, so it never touches a podman installed on the host
(a host podman built against another libc deadlocks on the shared lock segment otherwise). Rootless
podman still needs the host's setuid `newuidmap`/`newgidmap` and `/etc/subuid`, `/etc/subgid` entries.
Use `./dev podman ps` etc. to look at the network's containers; the host's `podman` does not see them.

## Several worktrees on one machine

Several people (or agents) can work in separate git worktrees of this repository on the same machine at
the same time, each with its own Nix dev shell, network and tests:

```sh
git fetch origin
git worktree add ../monorepo-42-foo -b 42-foo origin/main   # one worktree per issue branch
cd ../monorepo-42-foo
./dev ./gradlew build
tools/network.sh test
tools/network.sh env          # this worktree's slot, ports and compose project
tools/network.sh instances    # which checkout has which slot
tools/network.sh down -v      # before deleting the worktree: containers and volumes
git worktree remove ../monorepo-42-foo
```

What keeps them apart:

* **Instance slot** (`tools/instance.sh`, sourced by the dev shell and `tools/network.sh`): each
  checkout claims a slot 0–19 in `~/.local/share/168blocks/instances/`, recorded with its path. The
  slot sets the compose project (`blocks168-<slot>`, so containers, networks and volumes are
  separate) and every host port: edges `25501-25503 + 10·slot`, backend `8080 + 10·slot`,
  PostgreSQL `55432 + 10·slot`, S3 `8333 + 10·slot`, `gradlew runServer` `25565 + 10·slot`, and
  the e2e agents (velocity, lobby, survival) `28500-28502 + 10·slot`.
  The end-to-end tests read the same values (`BLOCKS_E2E_*`). Slots of deleted checkouts are
  reclaimed; force one with `BLOCKS_INSTANCE_SLOT=<n>`.
* **Images** built by Nix are tagged with their store hash, never `latest`, so worktrees on different
  `flake.nix` versions keep their own images (`./dev podman image prune` cleans unused ones).
* **Gradle**: all build state is inside the checkout (`build/`, `.gradle/`, Loom's caches, the staged
  network in `deploy/build/`, the generated forwarding secret). Only the dependency cache
  `~/.gradle/caches` is shared, which Gradle supports concurrently.
* **Tests**: `TestPostgres` starts each test JVM's own PostgreSQL in a temporary directory on a free
  port. Nothing listens on fixed ports except what the instance slot assigns.
* **Nix**: each worktree's `./dev` / `nix develop` uses that worktree's `flake.nix` and `flake.lock`.

Never run `gradlew --stop`: it stops every Gradle daemon of that version on the machine, including
other worktrees' running builds.

Do not hard-code ports, container or project names in new scripts or tests: take them from the
`BLOCKS_*` variables (`tools/network.sh env`).

## Java and Kotlin

* **Java 25** everywhere: Minecraft 26.3 requires it, and Velocity 4.3 (`velocity-api` 4.3.1) is
  compiled for it (class file version 69). Ktor, Compose and the rest support it. The version is
  `java` in `gradle/libs.versions.toml`; the convention plugins apply it.
* Kotlin everywhere, except **mixins, which are Java** (`src/main/java/.../mixin`).
* Versions live only in `gradle/libs.versions.toml`.

## Layout

| Path | What |
| --- | --- |
| `build-logic/` | Convention plugins: `blocks.kotlin-jvm`, `blocks.kotlin-multiplatform`, `blocks.fabric-mod`, `blocks.velocity-plugin`, `blocks.repositories` |
| `libs/pgecs` | ACID persistent ECS on PostgreSQL (backend, mods, Velocity plugins). Test fixture `TestPostgres` starts a throwaway server from Nix |
| `libs/pgecs-eqsat` | Equality saturation for the pgecs planner: egglog (Rust, `egglog-wasi/`) built to wasm32-wasip1 by Nix (`nix/egglog-wasm.nix`), compiled to JVM classes by Endive at build time |
| `libs/common` | JVM code shared by server-side components; CBOR via tinyjcbor (`ServerHello`) |
| `libs/api` | Kotlin Multiplatform (JVM + Wasm) backend API model, shared with the frontend |
| `backend/` | Ktor backend; serves the API and the web frontend |
| `frontend/` | Compose Multiplatform frontend: web (Kotlin/Wasm) and desktop |
| `mods/<name>` | Fabric mods (Minecraft 26.3, Loom without remapping, fabric-language-kotlin). `mods/example-common`: server + client |
| `velocity/<name>` | Velocity plugins (Kotlin, fat jar via `pluginJar`) |
| `deploy/` | podman compose network (`compose.yaml`) and the Gradle task that stages it (`:deploy:stage`) |
| `e2e/` | End-to-end tests against the running network |
| `docs/` | `architecture.md`, `testing.md`, `pgecs.md` (pgecs design), `matrix-bot.md` (backend Matrix bot) |

New module: add it to `settings.gradle.kts`, apply the matching convention plugin, and (for mods and
plugins that run in the network) add it to `deploy/build.gradle.kts`.

## Testing

* Unit and integration tests run in `./dev ./gradlew build`. Database tests use
  `testFixtures(project(":libs:pgecs"))` → `TestPostgres.config` (real PostgreSQL, no containers).
* End-to-end tests (`e2e/`) need the network: `tools/network.sh test`. The Minecraft servers only start
  with the Minecraft EULA accepted (`BLOCKS_ACCEPT_EULA=true`); never accept it on the user's behalf
  without asking. Design and plans: `docs/testing.md`.
