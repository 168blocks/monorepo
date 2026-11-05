# Matrix bot

The backend can run a Matrix bot (`backend/src/main/kotlin/dev/vxcc/b8/backend/matrix/`) that posts to
rooms and sends end-to-end encrypted direct messages to players on any homeserver. It uses
[Trixnity](https://gitlab.com/connect2x/trixnity/trixnity) (`trixnity-client`), the full client with
the vodozemac crypto driver: encrypted sending needs a sync loop, device lists, Olm sessions and Megolm
key sharing, which only the full client does.

## Configuration

Off unless `BLOCKS_MATRIX_HOMESERVER` is set. Credentials come from the environment only (deploy
secrets), never from the repository; see `MatrixBotConfig`:

| Variable | |
| --- | --- |
| `BLOCKS_MATRIX_HOMESERVER` | base URL of the bot's homeserver |
| `BLOCKS_MATRIX_USER` | the bot's user (localpart or `@bot:server`) |
| `BLOCKS_MATRIX_PASSWORD` | only for the first login |
| `BLOCKS_MATRIX_DEVICE_ID` | device id of the first login, default `168blocks-backend` |
| `BLOCKS_MATRIX_ANNOUNCE_ROOM` | optional room id: the backend posts there when it starts (example) |

The bot starts in the background, so an unreachable homeserver never holds up the backend.

## Store

Trixnity's whole store (account, access token, Olm/Megolm sessions, device keys, room state, outbox)
lives in the backend's PostgreSQL, schema `matrix_bot`, through `trixnity-client-repository-exposed`
on R2DBC (`MatrixStore`). The bot logs in with the password only when that store is empty; afterwards
it reuses its access token, device and keys, so players do not see a new unknown device after every
restart or redeploy. Do not drop the schema unless you want the bot to start over as a new device.

## vodozemac (wasm on Endive, no native library)

vodozemac is Rust. Trixnity's JVM bindings (`trixnity-vodozemac-jvm`) declare one `native` method per
vodozemac function and load a JNI library for them. The backend runs vodozemac as wasm on the JVM
instead, like egglog in `libs/pgecs-eqsat`, so it has no native code, nothing per OS, CPU or libc:

* `nix/vodozemac-wasm.nix` builds the same Rust source as Trixnity's JNI library
  (`trixnity-vodozemac-binaries`, the tag Trixnity's release pins) for `wasm32-unknown-unknown`. The
  module exports the same C functions and imports one, `env.extern_rng` (random bytes).
* `libs/vodozemac-endive` compiles it to JVM classes with Endive's build-time compiler. `VodozemacWasm`
  hosts one instance (random bytes from `SecureRandom`, calls serialized by a lock) and does what the JNI
  wrappers do: pointers become wasm32 pointers, Java arrays are copied into wasm memory and back, result
  words are 4 bytes instead of 8.
* The same module carries `trixnity-vodozemac-jvm`'s classes, rewritten at build time
  (`blocks.RewriteNativeMethods`, ASM): each `native` method calls `VodozemacWasm.call(name, args)`, and
  the library loader does nothing. The backend substitutes the module for `trixnity-vodozemac-jvm`
  (and excludes Trixnity's prebuilt glibc binaries).

Bump the tag in `nix/vodozemac-wasm.nix` together with `trixnity` in `gradle/libs.versions.toml`
(`vodozemac-tag` in Trixnity's own version catalog). The rewrite fails the build if the bindings have no
native methods any more; a changed calling convention shows up in `VodozemacWasmTest` and `MatrixBotTest`.

Speed: about 15-25x slower than the native library (JDK 25, x86-64, `VodozemacBench`), which is still
milliseconds per message:

| Operation | JNI | Endive |
| --- | --- | --- |
| first call (load, instantiate) | 70-90 ms | 250 ms |
| new Olm account | 31 µs | 0.58 ms |
| Olm session setup (3 DH) | 150 µs | 1.7-2.2 ms |
| Olm encrypt + decrypt, both directions, 1 KB | 245 µs | 3.7-4.7 ms |
| Megolm encrypt + decrypt, 1 KB | 66 µs | 1.4-2.0 ms |
| Ed25519 sign / verify, 1 KB | 20 / 35 µs | 0.5 / 0.5-0.65 ms |

Rust `opt-level = "s"` and curve25519-dalek's 64-bit backend did not help; `-XX:-DontCompileHugeMethods`
gains 10-30%. Endive's Redline backend (Cranelift native code, see issue #65) would close most of the gap
if that is ever needed. `nix/vodozemac-jni.nix` (`nix build .#vodozemac-jni`) still builds the JNI
library, for comparisons.

## Examples and tests

`MatrixExamples`: a room message (`announceStartup`) and an encrypted DM (`notifyPlayer`). A DM creates
a private room with `is_direct`, `m.room.encryption` and an invite for the player, or reuses the
existing one from `m.direct`.

`MatrixBotTest` runs both against a throwaway homeserver (`TestHomeserver`: tuwunel from the dev shell,
`BLOCKS_MATRIX_TEST_HOMESERVER`, users created through its admin console) with a second Trixnity client
as the player, which joins and decrypts the DM. It also restarts the bot on the same store and checks
that the device and DM room stay the same.

continuwuity does not work as the bot's homeserver for now: its `/keys/claim` response leaves out
`failures`, which Trixnity requires, so every encrypted send fails. tuwunel and Synapse send it.

## Not done yet

* Cross-signing and server-side key backup for the bot (needs a recovery key as a deploy secret).
* Accepting only invites and commands from Matrix ids linked to an account, rejecting the rest.
* Recording DM rooms in pgecs instead of only in `m.direct`.
* A homeserver in `deploy/compose.yaml` for the end-to-end network.
