# egglog compiled to wasm32-wasip1 (libs/pgecs-optimizer/egglog-wasi), for the pgecs optimizer.
# Output: $out/egglog.wasm, a reactor-style module that Endive compiles to JVM classes at build time.
{
  lib,
  stdenv,
  fetchFromGitHub,
  runCommand,
  rustPlatform,
  rust-bin,
}:
let
  # egglog pins Rust 1.91.0 (rust-toolchain.toml); rust-overlay provides the wasm32-wasip1 std for it.
  toolchain = rust-bin.stable."1.91.0".minimal.override { targets = [ "wasm32-wasip1" ]; };

  egglog = fetchFromGitHub {
    owner = "egraphs-good";
    repo = "egglog";
    rev = "6c3a0c910247f616e6d17b2c3923a757948a7841"; # main after v3.0.0, as in the spike
    hash = "sha256-OsH0YMN+y6S3dv9FYcuzEW3FCLbT8EhNPjfEO6LdFl4=";
  };

  crate = lib.fileset.toSource {
    root = ../libs/pgecs-optimizer/egglog-wasi;
    fileset = lib.fileset.unions [
      ../libs/pgecs-optimizer/egglog-wasi/Cargo.toml
      ../libs/pgecs-optimizer/egglog-wasi/Cargo.lock
      ../libs/pgecs-optimizer/egglog-wasi/src
    ];
  };

  # egglog-wasi depends on egglog by path (../egglog)
  src = runCommand "egglog-wasi-src" { } ''
    mkdir $out
    cp -r ${crate} $out/egglog-wasi
    cp -r ${egglog} $out/egglog
  '';
in
stdenv.mkDerivation {
  pname = "egglog-wasm";
  version = "3.0.0-6c3a0c9";
  inherit src;
  sourceRoot = "egglog-wasi-src/egglog-wasi";

  cargoDeps = rustPlatform.importCargoLock { lockFile = ../libs/pgecs-optimizer/egglog-wasi/Cargo.lock; };
  nativeBuildInputs = [
    rustPlatform.cargoSetupHook
    toolchain
  ];

  buildPhase = ''
    runHook preBuild
    cargo build --release --offline --locked --target wasm32-wasip1
    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall
    install -Dm644 target/wasm32-wasip1/release/egglog_wasi.wasm $out/egglog.wasm
    runHook postInstall
  '';

  dontFixup = true;
}
