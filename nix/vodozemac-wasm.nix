# Trixnity's vodozemac bindings (Matrix end-to-end encryption, the backend's Matrix bot) compiled to
# wasm32-unknown-unknown. Output: $out/vodozemac.wasm. libs/vodozemac-endive compiles it to JVM classes
# with Endive, so the backend needs no native library. The module exports the same C functions the
# JNI library registers (vodozemac_*, alloc, dealloc, copy_nonoverlapping) and imports one function,
# env.extern_rng (random bytes), which the host provides.
{
  fetchFromGitLab,
  stdenv,
  rustPlatform,
  rust-bin,
}:
let
  # same toolchain as nix/egglog-wasm.nix, so both share one download
  toolchain = rust-bin.stable."1.91.0".minimal.override { targets = [ "wasm32-unknown-unknown" ]; };

  # tag in Trixnity 5.10.1's gradle/libs.versions.toml (vodozemac-tag); bump together with trixnity
  src = fetchFromGitLab {
    owner = "connect2x/trixnity";
    repo = "trixnity-vodozemac-binaries";
    rev = "eba3dbef3980844cdd237d1e2d277ca09f8d3024"; # tag v0.1.3
    hash = "sha256-+j3Es2JEnjF9sfy/jYVipdSbzA6qVpoMk8oimWukP1M=";
  };
in
stdenv.mkDerivation {
  pname = "vodozemac-wasm";
  version = "0.1.3";
  inherit src;

  cargoDeps = rustPlatform.fetchCargoVendor {
    inherit src;
    hash = "sha256-urFuhPkdEhw2TfxkbnN1mYb4H7nvHdvG99/9s2Mi7T0=";
  };
  nativeBuildInputs = [
    rustPlatform.cargoSetupHook
    toolchain
  ];

  # upstream's .cargo/config.toml only sets linkers for its native cross builds
  postPatch = "rm .cargo/config.toml";

  buildPhase = ''
    runHook preBuild
    # extern_rng stays an import (the host's random source)
    RUSTFLAGS="-C link-arg=--allow-undefined" \
      cargo rustc --release --offline --locked --lib --crate-type cdylib --target wasm32-unknown-unknown
    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall
    install -Dm644 target/wasm32-unknown-unknown/release/vodozemac.wasm $out/vodozemac.wasm
    runHook postInstall
  '';

  dontFixup = true;
}
