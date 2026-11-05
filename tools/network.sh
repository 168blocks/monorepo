#!/usr/bin/env bash
# The local 168blocks network (deploy/compose.yaml) on podman.
#
#   tools/network.sh up [services...]     build + stage, load the runtime image, start
#   tools/network.sh test [gradle args]   up, wait until reachable, run the end-to-end tests (e2e/)
#   tools/network.sh down [-v]            stop (-v also deletes the database and S3 volumes)
#   tools/network.sh logs [service]
#   tools/network.sh ps
#   tools/network.sh psql [args]          psql as postgres inside the database container
#   tools/network.sh env                  this checkout's instance slot, ports, compose project
#   tools/network.sh instances            all checkouts' slots on this machine
#
# Every checkout (worktree) gets its own compose project, volumes and host ports (tools/instance.sh),
# so several networks can run side by side.
#
# The Minecraft servers (lobby, survival) only start once you accept the Minecraft EULA
# (https://aka.ms/MinecraftEULA): set BLOCKS_ACCEPT_EULA=true. Without it, they exit at startup and the
# tests that need them are skipped.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -n "${IN_NIX_SHELL:-}" ] || exec ./dev "$0" "$@"
BLOCKS_ROOT=$PWD . tools/instance.sh

# podman and podman-compose come from the dev shell (flake.nix, nix/podman.nix). Started containers
# would inherit our stdout/stderr; detach them so callers piping our output are not kept waiting.
compose() { podman-compose -p "$BLOCKS_COMPOSE_PROJECT" -f deploy/compose.yaml "$@" </dev/null; }

stage() {
  local eula=false
  [ "${BLOCKS_ACCEPT_EULA:-}" = true ] && eula=true
  ./gradlew --console=plain -q :deploy:stage "-PacceptMinecraftEula=$eula"
  # Nix-built images (flake.nix), tagged by Nix with a content hash; loaded once per version
  local name image ref
  for name in runtime postgres; do
    image=$(nix build --no-link --print-out-paths ".#$name-image")
    ref=$(tar -xzOf "$image" manifest.json | jq -r '.[0].RepoTags[0]')
    podman image exists "$ref" || podman load -q -i "$image"
    export "BLOCKS_$(echo "$name" | tr a-z A-Z)_IMAGE=$ref"
  done
}

wait_port() { # host port timeout-seconds
  local end=$((SECONDS + $3))
  until (exec 3<>"/dev/tcp/$1/$2") 2>/dev/null; do
    [ $SECONDS -lt $end ] || { echo "timeout waiting for $1:$2" >&2; return 1; }
    sleep 1
  done
}

wait_http() { # url timeout-seconds
  local end=$((SECONDS + $2))
  until curl -fsS -o /dev/null "$1"; do
    [ $SECONDS -lt $end ] || { echo "timeout waiting for $1" >&2; return 1; }
    sleep 1
  done
}

cmd=${1:-up}; shift || true
case "$cmd" in
  up)
    stage
    compose up -d "$@"
    echo "network $BLOCKS_COMPOSE_PROJECT: edges 127.0.0.1:$BLOCKS_EDGE1_PORT-$BLOCKS_EDGE3_PORT, backend $BLOCKS_E2E_BACKEND"
    ;;
  test)
    stage
    compose up -d
    for port in "$BLOCKS_EDGE1_PORT" "$BLOCKS_EDGE2_PORT" "$BLOCKS_EDGE3_PORT"; do wait_port 127.0.0.1 "$port" 60; done
    wait_http "$BLOCKS_E2E_BACKEND/api/health" 120
    ./gradlew --console=plain :e2e:test -Pe2e "$@"
    ;;
  down) compose down "$@" ;;
  env) env | grep -E '^BLOCKS_(INSTANCE_SLOT|COMPOSE_PROJECT|.*_PORT|E2E_.*)=' | sort ;;
  instances)
    for d in "${XDG_DATA_HOME:-$HOME/.local/share}"/168blocks/instances/*/; do
      [ -f "$d/path" ] && printf '%s\t%s\n' "$(basename "$d")" "$(cat "$d/path")"
    done
    ;;
  logs) compose logs "$@" ;;
  ps) compose ps ;;
  psql) # psql as the postgres superuser inside the database container (peer auth, no password)
    exec podman exec -it --user postgres "${BLOCKS_COMPOSE_PROJECT}_postgres_1" psql -h /run/postgresql -U postgres -d blocks "$@" ;;
  *) sed -n '2,18p' "$0"; exit 2 ;;
esac
