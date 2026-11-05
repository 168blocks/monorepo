{
  description = "168blocks: Minecraft SMP monorepo (Fabric mods, Velocity plugins, Ktor backend, Compose frontend)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    # Rust toolchains with the wasm32-wasip1 target (egglog for the pgecs optimizer, nix/egglog-wasm.nix)
    rust-overlay = {
      url = "github:oxalica/rust-overlay";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      rust-overlay,
    }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});

      # Java 25 is the floor for the whole stack: Minecraft 26.3 and Velocity 4.3 are compiled for it.
      jdkFor = pkgs: pkgs.jdk25;
      jreFor = pkgs: pkgs.jdk25_headless;

      # PostgreSQL with the extensions pgecs requires (pg_jsonschema)
      postgresFor =
        pkgs:
        let
          pg = pkgs.postgresql_18;
          pg_jsonschema = pg.pkgs.callPackage ./nix/pg_jsonschema.nix { };
        in
        pg.withPackages (_: [ pg_jsonschema ]);

      # egglog as wasm32-wasip1 for libs/pgecs-optimizer (Endive compiles it to JVM classes)
      egglogWasmFor = pkgs: (pkgs.extend rust-overlay.overlays.default).callPackage ./nix/egglog-wasm.nix { };
    in
    {
      devShells = forAllSystems (
        pkgs:
        let
          jdk = jdkFor pkgs;
        in
        {
          default = pkgs.mkShell {
            packages = with pkgs; [
              jdk
              # Kotlin/Wasm (frontend) uses these instead of Gradle-downloaded binaries, which are
              # glibc builds and do not run on every host.
              nodejs_24
              binaryen
              # pgecs and backend tests start a throwaway PostgreSQL from here
              (postgresFor pkgs)
              # initdb runs `/bin/sh -c -- ...`; where the host's /bin/sh does not accept that,
              # TestPostgres runs initdb under proot with bash as /bin/sh
              proot
              bashInteractive
              # local network (deploy/compose.yaml): podman with its own config and storage (nix/podman.nix)
              (pkgs.callPackage ./nix/podman.nix { })
              podman-compose
              jq
              curl
              git
              gh
              python3
            ];

            shellHook = ''
              export JAVA_HOME=${jdk}/lib/openjdk
              [ -d "$JAVA_HOME" ] || export JAVA_HOME=${jdk}
              # The host's LD_LIBRARY_PATH may point at incompatible (e.g. musl) libraries.
              unset LD_LIBRARY_PATH LD_PRELOAD
              export BLOCKS_NODE=${pkgs.nodejs_24}/bin/node
              export BLOCKS_BINARYEN=${pkgs.binaryen}
              export BLOCKS_BASH=${pkgs.bashInteractive}/bin/bash
              export BLOCKS_EGGLOG_WASM=${egglogWasmFor pkgs}/egglog.wasm
              # per-checkout slot, ports and compose project (several worktrees side by side)
              if root=$(git rev-parse --show-toplevel 2>/dev/null) && [ -f "$root/tools/instance.sh" ]; then
                BLOCKS_ROOT=$root . "$root/tools/instance.sh"
              fi
            '';
          };
        }
      );

      packages = forAllSystems (
        pkgs:
        let
          jre = jreFor pkgs;
        in
        {
          postgresql = postgresFor pkgs;

          egglog-wasm = egglogWasmFor pkgs;

          # PostgreSQL with pg_jsonschema for deploy/compose.yaml (the official image lacks the extension)
          postgres-image = pkgs.dockerTools.buildLayeredImage {
            name = "localhost/168blocks/postgres";
            # tag = content hash chosen by Nix (tools/network.sh reads it), so worktrees never share a tag
            tag = null;
            contents = [
              (postgresFor pkgs)
              pkgs.bashInteractive
              pkgs.coreutils
              pkgs.su-exec
              (pkgs.dockerTools.fakeNss.override {
                extraPasswdLines = [ "postgres:x:70:70:PostgreSQL:/var/lib/postgresql:/bin/sh" ];
                extraGroupLines = [ "postgres:x:70:" ];
              })
            ];
            extraCommands = "mkdir -p tmp bin var/lib/postgresql run/postgresql && chmod 1777 tmp && ln -sf ${pkgs.bashInteractive}/bin/bash bin/sh";
            config = {
              Entrypoint = [
                "${pkgs.bashInteractive}/bin/bash"
                "${./nix/postgres-entrypoint.sh}"
              ];
              Env = [ "PATH=/bin" ];
              Volumes."/var/lib/postgresql" = { };
              ExposedPorts."5432/tcp" = { };
            };
          };

          # Runtime image for every JVM service in deploy/compose.yaml. The services mount their staged
          # files (deploy/build/stage/<service>) at /srv, so the image only changes with the JRE.
          runtime-image = pkgs.dockerTools.buildLayeredImage {
            name = "localhost/168blocks/runtime";
            # tag = content hash chosen by Nix (tools/network.sh reads it), so worktrees never share a tag
            tag = null;
            contents = [
              jre
              pkgs.bashInteractive
              pkgs.coreutils
              pkgs.cacert
              pkgs.dockerTools.fakeNss
            ];
            extraCommands = "mkdir -p tmp bin && chmod 1777 tmp && ln -sf ${pkgs.bashInteractive}/bin/bash bin/sh";
            config = {
              Env = [
                "JAVA_HOME=${jre}/lib/openjdk"
                "SSL_CERT_FILE=/etc/ssl/certs/ca-bundle.crt"
                "PATH=/bin"
              ];
              WorkingDir = "/srv";
            };
          };
        }
      );

      formatter = forAllSystems (pkgs: pkgs.nixfmt-rfc-style);
    };
}
