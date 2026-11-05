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

## vodozemac (native library)

vodozemac is Rust; Trixnity loads its JNI library from the classpath at
`natives/<os>-<arch>/libvodozemac.so`. Trixnity's prebuilt copies (`trixnity-vodozemac-binaries`) are
excluded. `nix/vodozemac-jni.nix` builds the same source (`trixnity-vodozemac-binaries`, the tag
Trixnity's release pins) with Nix, the dev shell exports it as `BLOCKS_VODOZEMAC_JNI`, and the backend
puts it into its resources (and so into the staged backend jar).

It is built against Nix's glibc, not musl: the library is loaded into the JVM process, and every JVM we
run (dev shell, tests, the runtime image) is Nix's JDK, which uses Nix's glibc whatever the host's libc
is. A musl build would not load into it. Bump the tag in `nix/vodozemac-jni.nix` together with
`trixnity` in `gradle/libs.versions.toml` (`vodozemac-tag` in Trixnity's own version catalog).

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
