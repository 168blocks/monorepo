# Trixnity's JNI library for vodozemac (Matrix end-to-end encryption, used by the backend's Matrix bot).
# Output: $out/natives/<os>-<arch>/libvodozemac.so, the classpath layout Trixnity's JvmLoader expects.
#
# Trixnity ships this library prebuilt (trixnity-vodozemac-binaries-jvm), built on some other distro's
# glibc. We build it here instead, from the same source and tag, against the glibc of Nix's JDK, so it
# loads into every JVM we run (dev shell, tests, the runtime image) whatever the host's libc is.
{
  lib,
  stdenv,
  fetchFromGitLab,
  rustPlatform,
}:
let
  # os-arch directory names of de.connect2x.trixnity.vodozemac.JvmLoader (JNA's platform prefix)
  platformDir =
    {
      x86_64-linux = "linux-x86-64";
      aarch64-linux = "linux-aarch64";
    }
    .${stdenv.hostPlatform.system};
in
rustPlatform.buildRustPackage {
  pname = "trixnity-vodozemac-jni";
  # tag in Trixnity 5.10.1's gradle/libs.versions.toml (vodozemac-tag); bump together with trixnity
  version = "0.1.3";

  src = fetchFromGitLab {
    owner = "connect2x/trixnity";
    repo = "trixnity-vodozemac-binaries";
    rev = "eba3dbef3980844cdd237d1e2d277ca09f8d3024"; # tag v0.1.3
    hash = "sha256-+j3Es2JEnjF9sfy/jYVipdSbzA6qVpoMk8oimWukP1M=";
  };

  # upstream's .cargo/config.toml sets clang + lld for cross builds; use the stdenv toolchain instead
  postPatch = "rm .cargo/config.toml";

  cargoHash = "sha256-urFuhPkdEhw2TfxkbnN1mYb4H7nvHdvG99/9s2Mi7T0=";

  # only the shared library; the crate also builds a staticlib for Kotlin/Native
  buildPhase = ''
    runHook preBuild
    cargo rustc --release --offline --locked --lib --crate-type cdylib
    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall
    install -Dm755 target/release/libvodozemac.so $out/natives/${platformDir}/libvodozemac.so
    runHook postInstall
  '';

  doCheck = false;

  meta = {
    description = "JNI bindings of vodozemac for Trixnity";
    homepage = "https://gitlab.com/connect2x/trixnity/trixnity-vodozemac-binaries";
    license = lib.licenses.asl20;
    platforms = [
      "x86_64-linux"
      "aarch64-linux"
    ];
  };
}
