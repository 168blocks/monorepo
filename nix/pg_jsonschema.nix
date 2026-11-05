# pg_jsonschema (https://github.com/supabase/pg_jsonschema): JSON Schema validation in PostgreSQL.
# Required by pgecs. Not in nixpkgs, so built here with pgrx, like nixpkgs' other pgrx extensions.
{
  buildPgrxExtension,
  cargo-pgrx_0_16_1,
  fetchFromGitHub,
  lib,
  postgresql,
}:
(buildPgrxExtension (finalAttrs: {
  inherit postgresql;
  cargo-pgrx = cargo-pgrx_0_16_1;

  pname = "pg_jsonschema";
  version = "0.3.4";

  src = fetchFromGitHub {
    owner = "supabase";
    repo = "pg_jsonschema";
    tag = "v${finalAttrs.version}";
    hash = "sha256-tM8XEHvnmN5KlwXOOdPJHUZBj5+tKJge7hxdqqgWlE8=";
  };

  cargoHash = "sha256-oZR91U5AAXDjGrn7CSdSyJAIX0Lwt3Xn0hSbJrjGDuQ=";

  # pgrx tests try to install the extension into the postgresql store path
  doCheck = false;

  meta = {
    description = "PostgreSQL extension providing JSON Schema validation";
    homepage = "https://github.com/supabase/pg_jsonschema";
    license = lib.licenses.asl20;
  };
})).overrideAttrs (old: {
  # buildPgrxExtension runs `cargo pgrx init`, which runs initdb, which needs /bin/sh inside the build
  # sandbox. Not every Nix installation provides that (sandbox-paths), and packaging needs no database:
  # write pgrx's config directly and only package.
  buildPhase = ''
    runHook preBuild
    export PGRX_HOME="$(mktemp -d)"
    printf '[configs]\npg${lib.versions.major postgresql.version} = "%s"\n' \
      "${postgresql.pg_config}/bin/pg_config" > "$PGRX_HOME/config.toml"
    PGRX_BUILD_FLAGS="--frozen -j $NIX_BUILD_CORES" \
    cargo pgrx package \
      --pg-config ${postgresql.pg_config}/bin/pg_config \
      --features "pg${lib.versions.major postgresql.version}" \
      --out-dir "$out"
    runHook postBuild
  '';
  installPhase = ''
    runHook preInstall
    mv $out/${postgresql}/* $out
    rm -rf $out/nix
    runHook postInstall
  '';
})
