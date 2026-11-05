# podman from Nix, isolated from any podman installed on the host: its own configuration (registries,
# signature policy, containers.conf) and its own image/container storage, so neither installation sees
# or breaks the other's state.
#
# Rootless podman still needs the host's setuid newuidmap/newgidmap and subordinate id ranges
# (/etc/subuid, /etc/subgid); setuid binaries cannot come from Nix.
{
  lib,
  writeShellScriptBin,
  writeText,
  podman,
}:
let
  policy = writeText "policy.json" (
    builtins.toJSON {
      default = [ { type = "insecureAcceptAnything"; } ];
      transports.docker-daemon."" = [ { type = "insecureAcceptAnything"; } ];
    }
  );
  registries = writeText "registries.conf" ''
    unqualified-search-registries = ["docker.io"]
    short-name-mode = "enforcing"
  '';
in
writeShellScriptBin "podman" ''
  set -eu
  run="''${XDG_RUNTIME_DIR:-/tmp/168blocks-podman-$(id -u)}/168blocks-podman"
  data="''${BLOCKS_PODMAN_HOME:-''${XDG_DATA_HOME:-$HOME/.local/share}/168blocks/podman}"
  conf="$run/config/containers"
  mkdir -p "$conf" "$data" "$run/tmp"
  ln -sf ${policy} "$conf/policy.json"
  ln -sf ${registries} "$conf/registries.conf"
  cat > "$conf/storage.conf" <<STORAGE
  [storage]
  driver = "overlay"
  runroot = "$run/storage"
  graphroot = "$data/storage"
  STORAGE
  cat > "$conf/containers.conf" <<CONTAINERS
  [engine]
  tmp_dir = "$run/tmp"
  # File locks instead of the default shared-memory segment (/dev/shm/libpod_rootless_lock_<uid>),
  # which a host podman built against another libc (e.g. musl) would share with incompatible mutexes.
  lock_type = "file"
  CONTAINERS
  export XDG_CONFIG_HOME="$run/config"
  export CONTAINERS_STORAGE_CONF="$conf/storage.conf"
  export CONTAINERS_CONF="$conf/containers.conf"
  export CONTAINERS_REGISTRIES_CONF="$conf/registries.conf"
  exec ${lib.getExe podman} "$@"
''
