# Hermetic Nix builds of the Gradle modules that do not need Minecraft (see AGENTS.md, "Hermetic Nix
# build"). Gradle runs in the Nix sandbox without network; every file it downloads is replayed by
# mitm-cache from nix/gradle-deps.json, which records URL and hash of each one (nixpkgs gradle.fetchDeps).
#
#   nix build .#backend               (and .#frontend-web, .#velocity-example-plugin, .#libs)
#   nix flake check                   `gradlew build` with all tests, as .#checks.<system>.gradle
#   nix run .#update-gradle-deps      re-record nix/gradle-deps.json after a dependency change
#
# Left out: the Fabric mods and :deploy (settings.gradle.kts, -Pblocks.minecraft=false). Loom downloads
# Minecraft from Mojang while configuring them; see AGENTS.md for why that is not hermetic yet.
{
  lib,
  stdenv,
  gradle_9,
  jdk,
  nodejs_24,
  binaryen,
  postgresql,
  proot,
  bashInteractive,
  egglogWasm,
  vodozemacJni,
  matrix-tuwunel,
  writeShellScript,
  cacert,
}:
let
  # The backend's tests start tuwunel (TestHomeserver), which will not start in the Nix sandbox: it
  # reads /etc/resolv.conf (absent) unless given DNS servers, and wants a CA bundle. Federation is off,
  # so it never resolves or connects anywhere.
  testHomeserver = writeShellScript "tuwunel-test" ''
    export SSL_CERT_FILE=${cacert}/etc/ssl/certs/ca-bundle.crt
    exec ${matrix-tuwunel}/bin/tuwunel -O 'dns_servers=["127.0.0.1"]' "$@"
  '';
  gradle = gradle_9.override { java = jdk; };
  root = ./..;

  # only what the Gradle build reads, so edits to docs, deploy or tools do not rebuild
  src = lib.fileset.toSource {
    inherit root;
    # (a flake's source has only git-tracked files already)
    fileset = lib.fileset.unions [
      (root + "/settings.gradle.kts")
      (root + "/build.gradle.kts")
      (root + "/gradle.properties")
      (root + "/gradle")
      (root + "/build-logic")
      (root + "/libs")
      (root + "/backend")
      (root + "/frontend")
      (root + "/velocity")
      (root + "/e2e")
      (root + "/kotlin-js-store")
    ];
  };

  version = lib.removePrefix "version=" (
    lib.findFirst (lib.hasPrefix "version=") "version=0" (
      lib.splitString "\n" (builtins.readFile (root + "/gradle.properties"))
    )
  );

  # Every module's tasks, so that recording the dependencies (update-gradle-deps) sees all downloads.
  allTasks = [
    "build"
    ":backend:installDist"
    ":frontend:webDist"
    ":velocity:example-plugin:pluginJar"
  ];

  mitmCache = gradle.fetchDeps {
    pkg = mkGradle {
      pname = "168blocks";
      tasks = allTasks;
      installPhase = "touch $out";
    };
    data = ./gradle-deps.json;
    # the update runs the tests too; initdb needs the user in /etc/passwd
    bwrapFlags = ''--ro-bind "$PWD" "$PWD" --ro-bind /etc/passwd /etc/passwd --ro-bind /etc/group /etc/group'';
  };

  mkGradle =
    {
      pname,
      tasks,
      installPhase,
    }:
    stdenv.mkDerivation {
      inherit
        pname
        version
        src
        installPhase
        mitmCache
        ;

      nativeBuildInputs = [
        gradle
        jdk
        # Kotlin/Wasm (libs/api, frontend): Node.js and Binaryen from Nix, as in the dev shell
        nodejs_24
        binaryen
        # tests: TestPostgres runs initdb/postgres; the sandbox has no /bin/sh, so initdb runs under
        # proot with bash bound to /bin/sh
        postgresql
        proot
      ];

      env = {
        JAVA_HOME = "${jdk}/lib/openjdk";
        BLOCKS_NODE = "${nodejs_24}/bin/node";
        BLOCKS_BINARYEN = "${binaryen}";
        BLOCKS_BASH = "${bashInteractive}/bin/bash";
        # libs/pgecs-eqsat compiles egglog (nix/egglog-wasm.nix) to JVM classes
        BLOCKS_EGGLOG_WASM = "${egglogWasm}/egglog.wasm";
        # backend's Matrix bot: vodozemac's JNI library, and the tests' throwaway homeserver
        BLOCKS_VODOZEMAC_JNI = "${vodozemacJni}";
        BLOCKS_MATRIX_TEST_HOMESERVER = "${testHomeserver}";
      };

      gradleFlags = [
        "-Pblocks.minecraft=false"
        "-Dfile.encoding=utf-8"
      ];
      gradleBuildTask = tasks;
      gradleUpdateTask = allTasks;
      # yarn (Kotlin/Wasm's npm packages) goes through mitm-cache as well: it needs the proxy as a URL
      # and, like all of Node.js, ignores SSL_CERT_FILE
      preBuild = ''
        if [ -n "''${MITM_CACHE_ADDRESS-}" ]; then
          export http_proxy="http://$MITM_CACHE_ADDRESS" https_proxy="http://$MITM_CACHE_ADDRESS"
          export NODE_EXTRA_CA_CERTS="$MITM_CACHE_CA"
        fi
      '';

      __darwinAllowLocalNetworking = true;

      meta.sourceProvenance = with lib.sourceTypes; [
        fromSource
        binaryBytecode # dependencies from Maven repositories
      ];
    };
in
{
  inherit mitmCache;

  backend = mkGradle {
    pname = "168blocks-backend";
    tasks = [ ":backend:installDist" ];
    installPhase = "cp -r backend/build/install/backend $out";
  };

  # the production web build the backend serves (BLOCKS_FRONTEND_DIR)
  frontend-web = mkGradle {
    pname = "168blocks-frontend-web";
    tasks = [ ":frontend:webDist" ];
    installPhase = "cp -r frontend/build/web $out";
  };

  velocity-example-plugin = mkGradle {
    pname = "168blocks-velocity-example-plugin";
    tasks = [ ":velocity:example-plugin:pluginJar" ];
    installPhase = "install -Dm644 -t $out velocity/example-plugin/build/libs/*-plugin.jar";
  };

  libs = mkGradle {
    pname = "168blocks-libs";
    tasks = [
      ":libs:pgecs:jar"
      ":libs:common:jar"
      ":libs:api:jvmJar"
    ];
    installPhase = "install -Dm644 -t $out libs/*/build/libs/*.jar";
  };

  # `gradlew build`: compiles everything and runs all unit and integration tests
  check = mkGradle {
    pname = "168blocks-check";
    tasks = allTasks;
    installPhase = "touch $out";
  };
}
