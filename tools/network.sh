#!/usr/bin/env bash
# The local 168blocks network (deploy/compose.yaml) on podman.
#
#   tools/network.sh up [services...]     build + stage, load the runtime image, start
#   tools/network.sh test [gradle args]   up, wait until reachable, run the end-to-end tests (e2e/)
#   tools/network.sh down [-v]            stop (-v also deletes the database and S3 volumes)
#   tools/network.sh logs [service]
#   tools/network.sh ps
#
# The Minecraft servers (lobby, survival) only start once you accept the Minecraft EULA
# (https://aka.ms/MinecraftEULA): set BLOCKS_ACCEPT_EULA=true. Without it, they exit at startup and the
# tests that need them are skipped.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -n "${IN_NIX_SHELL:-}" ] || exec ./dev "$0" "$@"

# podman and podman-compose come from the dev shell (flake.nix, nix/podman.nix). Started containers
# would inherit our stdout/stderr; detach them so callers piping our output are not kept waiting.
compose() { podman-compose -f deploy/compose.yaml "$@" </dev/null; }

stage() {
  local eula=false
  [ "${BLOCKS_ACCEPT_EULA:-}" = true ] && eula=true
  ./gradlew --console=plain -q :deploy:stage "-PacceptMinecraftEula=$eula"
  # Nix-built images (flake.nix); only reloaded when their store path changes
  local name image
  for name in runtime postgres; do
    image=$(nix build --no-link --print-out-paths ".#$name-image")
    if [ "$(cat "deploy/build/$name-image" 2>/dev/null)" != "$image" ] || ! podman image exists "localhost/168blocks/$name:latest"; then
      podman load -q -i "$image"
      echo "$image" > "deploy/build/$name-image"
    fi
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
    ;;
  test)
    stage
    compose up -d
    for port in 25501 25502 25503; do wait_port 127.0.0.1 $port 60; done
    wait_http http://127.0.0.1:8080/api/health 120
    ./gradlew --console=plain :e2e:test -Pe2e "$@"
    ;;
  down) compose down "$@" ;;
  logs) compose logs "$@" ;;
  ps) compose ps ;;
  *) sed -n '2,13p' "$0"; exit 2 ;;
esac
