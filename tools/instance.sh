# shellcheck shell=sh
# Per-checkout ("instance") settings, so several worktrees of this repository can run their dev
# shells, networks and tests side by side on one machine. Sourced by the Nix dev shell (flake.nix) and
# tools/network.sh; POSIX sh.
#
# Each checkout claims a slot (0-19) in $XDG_DATA_HOME/168blocks/instances/<slot>, which records the
# checkout's path. The slot picks the compose project name and the host ports:
#
#   edge proxies   25501-25503 + 10*slot
#   backend        8080        + 10*slot
#   PostgreSQL     55432       + 10*slot
#   S3             8333        + 10*slot
#   loom runServer 25565       + 10*slot (mods' `gradlew runServer`)
#
# The main checkout usually gets slot 0 (the documented default ports). Override with
# BLOCKS_INSTANCE_SLOT=<n>. Slots of deleted checkouts are reclaimed automatically;
# `tools/network.sh instances` lists them.

blocks_root=$(git -C "${BLOCKS_ROOT:-.}" rev-parse --show-toplevel 2>/dev/null || pwd)
blocks_registry="${XDG_DATA_HOME:-$HOME/.local/share}/168blocks/instances"

blocks_claim_slot() {
  mkdir -p "$blocks_registry"
  n=0
  # already registered?
  while [ $n -lt 20 ]; do
    if [ "$(cat "$blocks_registry/$n/path" 2>/dev/null)" = "$blocks_root" ]; then echo $n; return; fi
    n=$((n + 1))
  done
  # first free (or stale: its checkout is gone) slot; mkdir is the atomic claim
  n=0
  while [ $n -lt 20 ]; do
    p=$(cat "$blocks_registry/$n/path" 2>/dev/null || true)
    if [ -n "$p" ] && [ ! -d "$p" ]; then rm -rf "$blocks_registry/$n"; fi
    if mkdir "$blocks_registry/$n" 2>/dev/null; then
      printf '%s\n' "$blocks_root" > "$blocks_registry/$n/path"
      echo $n
      return
    fi
    n=$((n + 1))
  done
  echo "tools/instance.sh: all 20 instance slots are taken (see $blocks_registry)" >&2
  return 1
}

# A slot inherited from another checkout's shell (BLOCKS_INSTANCE_FOR differs) is not ours; one set by
# hand (no BLOCKS_INSTANCE_FOR) is an override.
if [ -n "${BLOCKS_INSTANCE_FOR:-}" ] && [ "$BLOCKS_INSTANCE_FOR" != "$blocks_root" ]; then
  unset BLOCKS_INSTANCE_SLOT
fi
if [ -z "${BLOCKS_INSTANCE_SLOT:-}" ]; then
  BLOCKS_INSTANCE_SLOT=$(blocks_claim_slot) || BLOCKS_INSTANCE_SLOT=0
fi
export BLOCKS_INSTANCE_FOR="$blocks_root"
blocks_off=$((BLOCKS_INSTANCE_SLOT * 10))

export BLOCKS_ROOT="$blocks_root"
export BLOCKS_INSTANCE_SLOT
export BLOCKS_COMPOSE_PROJECT="blocks168-$BLOCKS_INSTANCE_SLOT"
export BLOCKS_EDGE1_PORT=$((25501 + blocks_off))
export BLOCKS_EDGE2_PORT=$((25502 + blocks_off))
export BLOCKS_EDGE3_PORT=$((25503 + blocks_off))
export BLOCKS_BACKEND_PORT=$((8080 + blocks_off))
export BLOCKS_POSTGRES_PORT=$((55432 + blocks_off))
export BLOCKS_S3_PORT=$((8333 + blocks_off))
export BLOCKS_DEV_SERVER_PORT=$((25565 + blocks_off))
# what the end-to-end tests connect to (e2e/.../Network.kt)
export BLOCKS_E2E_EDGES="127.0.0.1:$BLOCKS_EDGE1_PORT,127.0.0.1:$BLOCKS_EDGE2_PORT,127.0.0.1:$BLOCKS_EDGE3_PORT"
export BLOCKS_E2E_BACKEND="http://127.0.0.1:$BLOCKS_BACKEND_PORT"
unset blocks_off blocks_registry n p
