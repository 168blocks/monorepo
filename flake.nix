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

      # egglog as wasm32-wasip1 for libs/pgecs-eqsat (Endive compiles it to JVM classes)
      egglogWasmFor = pkgs: (pkgs.extend rust-overlay.overlays.default).callPackage ./nix/egglog-wasm.nix { };

      # vodozemac's JNI library for Trixnity (backend Matrix bot, end-to-end encryption), built against
      # the same glibc as Nix's JDK instead of Trixnity's prebuilt one
      vodozemacJniFor = pkgs: pkgs.callPackage ./nix/vodozemac-jni.nix { };

      # Environment for the real game clients of the end-to-end tests (e2e/.../client, docs/testing.md):
      # `blocks-client-env <command>` runs <command> with the libraries Minecraft's LWJGL/SDL natives
      # dlopen (glibc builds; a musl host has none of them) and Mesa software rendering (llvmpipe /
      # lavapipe), so clients need no GPU. Only the client processes get this, never the dev shell.
      clientEnvFor =
        pkgs:
        let
          libs = with pkgs; [
            libglvnd
            mesa
            vulkan-loader
            wayland
            libxkbcommon
            libdecor
            libx11
            libxcursor
            libxrandr
            libxi
            libxext
            libxcb
            libpulseaudio
            alsa-lib
            openal
            udev
            flite
            stdenv.cc.cc.lib
          ];
        in
        pkgs.writeShellScriptBin "blocks-client-env" ''
          export LD_LIBRARY_PATH=${pkgs.lib.makeLibraryPath libs}
          export LIBGL_DRIVERS_PATH=${pkgs.mesa}/lib/dri
          export __EGL_VENDOR_LIBRARY_FILENAMES=${pkgs.mesa}/share/glvnd/egl_vendor.d/50_mesa.json
          export __GLX_VENDOR_LIBRARY_NAME=mesa
          export GBM_BACKENDS_PATH=${pkgs.mesa}/lib/gbm
          export VK_ICD_FILENAMES=$(echo ${pkgs.mesa}/share/vulkan/icd.d/lvp_icd.*.json)
          export LIBGL_ALWAYS_SOFTWARE=1
          export XKB_CONFIG_ROOT=${pkgs.xkeyboard-config}/share/X11/xkb
          exec "$@"
        '';

      # hermetic Gradle builds (nix/gradle.nix): backend, frontend-web, velocity-example-plugin, libs
      gradleFor =
        pkgs:
        pkgs.callPackage ./nix/gradle.nix {
          jdk = jdkFor pkgs;
          postgresql = postgresFor pkgs;
          egglogWasm = egglogWasmFor pkgs;
        };
    in
    {
      devShells = forAllSystems (
        pkgs:
        let
          jdk = jdkFor pkgs;
          clientEnv = clientEnvFor pkgs;
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
              # real game clients in the end-to-end tests (BLOCKS_CLIENT_ENV, BLOCKS_SWAY below)
              clientEnv
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
              export BLOCKS_VODOZEMAC_JNI=${vodozemacJniFor pkgs}
              # throwaway Matrix homeserver for the backend's Matrix bot tests (TestHomeserver)
              export BLOCKS_MATRIX_TEST_HOMESERVER=${pkgs.matrix-tuwunel}/bin/tuwunel
              export BLOCKS_CLIENT_ENV=${clientEnv}/bin/blocks-client-env
              export BLOCKS_SWAY=${pkgs.sway}/bin/sway
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
          gradle = gradleFor pkgs;
        in
        {
          inherit (gradle)
            backend
            frontend-web
            velocity-example-plugin
            libs
            ;

          postgresql = postgresFor pkgs;

          egglog-wasm = egglogWasmFor pkgs;

          vodozemac-jni = vodozemacJniFor pkgs;

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

          # PowerDNS authoritative server for the player zone (deploy/compose.yaml: pdns, docs/dns.md). All
          # answers come from the dns service through the remote backend; the config is mounted at
          # /etc/pdns/pdns.conf (deploy/dns/pdns.conf).
          pdns-image = pkgs.dockerTools.buildLayeredImage {
            name = "localhost/168blocks/pdns";
            # tag = content hash chosen by Nix (tools/network.sh reads it), so worktrees never share a tag
            tag = null;
            contents = [
              pkgs.pdns
              pkgs.dockerTools.fakeNss
            ];
            extraCommands = "mkdir -p tmp etc/pdns && chmod 1777 tmp";
            config = {
              Entrypoint = [
                "${pkgs.pdns}/bin/pdns_server"
                "--config-dir=/etc/pdns"
              ];
              ExposedPorts = {
                "53/udp" = { };
                "53/tcp" = { };
              };
            };
          };

          # Runtime image for every JVM service in deploy/compose.yaml. The services mount their staged
          # files (deploy/build/stage/<service>) at /srv, so the image only changes with the JRE.
          # Also the WireGuard tooling (nix/wg-up.sh): the core container and the edges bring up their
          # wg0 with it, with the kernel module or userspace wireguard-go.
          runtime-image = pkgs.dockerTools.buildLayeredImage {
            name = "localhost/168blocks/runtime";
            # tag = content hash chosen by Nix (tools/network.sh reads it), so worktrees never share a tag
            tag = null;
            # one merged /bin (several of these packages bring their own bin/ and sbin/)
            contents = pkgs.buildEnv {
              name = "runtime-root";
              paths = [
                jre
                pkgs.bashInteractive
                pkgs.coreutils
                pkgs.cacert
                pkgs.dockerTools.fakeNss
                pkgs.iproute2
                pkgs.wireguard-tools
                pkgs.wireguard-go
                (pkgs.writeShellScriptBin "wg-up" (builtins.readFile ./nix/wg-up.sh))
              ];
              pathsToLink = [
                "/bin"
                "/etc"
              ];
            };
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

      # `gradlew build` (all unit and integration tests) in the Nix sandbox
      checks = forAllSystems (pkgs: {
        gradle = (gradleFor pkgs).check;
      });

      # nix run .#update-gradle-deps: re-records nix/gradle-deps.json (run in the repository root)
      apps = forAllSystems (pkgs: {
        update-gradle-deps = {
          type = "app";
          program = "${(gradleFor pkgs).mitmCache.updateScript}";
        };
      });

      formatter = forAllSystems (pkgs: pkgs.nixfmt-rfc-style);
    };
}
