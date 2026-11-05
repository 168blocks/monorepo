# Co-hosting instances in one JVM: global state

The creative host (#6) runs many `MinecraftServer`s with the same mods and data packs in one JVM. Every
mutable static of Minecraft, Fabric and the mods is then shared by all of them. This page is what
`libs/static-guard` (#75) found and how we keep the instances apart. The tool is generic; the numbers
below are for Minecraft 26.3 with C2ME, ScalableLux, VMP, Async, FerriteCore, Fabric API and
FabricProxy-Lite (the versions in `gradle/libs.versions.toml`).

## Approach

1. **Static scan** (`static-guard` CLI, ~4 s for 14k classes): every `putstatic` outside the owner's
   `<clinit>`, mutating calls and element stores on objects read from statics, statics whose type is a
   per-instance type (`MinecraftServer`, `ServerLevel`, `ServerPlayer`, ...), and calls with JVM-wide
   effect (`System.exit`, `Runtime.halt`, `System.setOut`, default handlers, ...). It over-reports:
   it cannot tell bootstrap from run time, and most statics written at bootstrap (registries, codecs,
   Fabric events) are shared on purpose, because every instance has the same mods and data packs.
2. **Runtime trace** (agent, `trace`): hooks every `putstatic` and records which thread wrote what,
   before and after the instance is up ("seal"). For one instance the writes after the seal are the
   candidates for leaking between instances. Bootstrapping Minecraft with all mods under the agent:
   3551 classes rewritten, none failed, 20 s instead of 17 s.
3. **Guard** (agent with tenants): the host binds each server's threads to a tenant id; the agent
   reports a static written by two tenants (`CROSS_WRITE`), read by one after another wrote it
   (`CROSS_READ`), or a static container used by two (`SHARED_READ`), with the stack of the first
   access. `strict` throws at the cross write: for tests with two servers, not for production.
4. **Containment** (agent, `isolate`): the listed fields get one slot per tenant
   (`getstatic`/`putstatic` rewritten to `TenantStatics`), for state we cannot patch at the source.

## Findings

Scan totals (HIGH + MEDIUM, without bundled libraries such as Caffeine, JCTools, RxJava, whose statics
are counters and per-thread probes): Minecraft 121, Fabric API 170 (nearly all bootstrap-time
registration), Async 66, C2ME 36, ScalableLux 5, FerriteCore 1, FabricProxy-Lite 1, VMP 0.

Problems for co-hosting, checked in the bytecode:

| Where | What | Effect with several servers | Fix |
| --- | --- | --- | --- |
| C2ME `base/mixin/util/log4j2shutdownhookisnomore/MixinMinecraftDedicatedServer` | after a `DedicatedServer` shuts down: `LogManager.shutdown()`, then `System.exit` on a new thread | stopping one server stops logging and exits the JVM | disable that C2ME module in its config, or host servers that are not `DedicatedServer` |
| `ServerWatchdog` | `Runtime.halt` when one server's tick hangs (`max-tick-time`) | one stuck server kills all | `max-tick-time=-1`, the host watches ticks itself and stops only that server |
| `BlockableEventLoop.delayedCrash` | static; every event loop that propagates crashes rethrows it | one server's delayed crash crashes the others | mixin: make it per server (or isolate the field) |
| Async `ParallelProcessor` | static `server`, executor, task queues; `setupThreadPool` on SERVER_STARTING, `stop()` on SERVER_STOPPING | all entity ticking goes to the last started server's state; stopping one server shuts the pool down for all | do not use Async on the creative host (or patch it per server) |
| Async `AsyncConfig` (via `/async` commands) | config in statics | one owner's `/async` changes all servers | same as above |
| `RegionFileVersion.selected` | set from `server.properties` (`region-file-compression`) | last started server's setting wins | same compression for all creative servers (a fixed host setting) |
| `ChaseCommand` (`/chase`, debug) | static client/server | shared between servers | do not register it |
| `Language.instance`, `Util.timeSource`, `Bootstrap` streams (`System.setOut`) | JVM-wide, set once at bootstrap | fine: same for all | none |
| `ServerLevel.ENTITY_COUNTER`, thread id counters | static atomics | ids stay unique in the JVM: fine | none |
| `StrongholdPieces` statics | mutable worldgen state | vanilla race, already made thread-local by C2ME's `fixes-worldgen-threading-issues` | keep C2ME |
| C2ME `GlobalExecutors`, ScalableLux executors | one pool per JVM | shared on purpose: what we want | none |

`ThreadLocal`s (C2ME's scheduling state, `Commands.CURRENT_EXECUTION_CONTEXT`, piston `NOCLIP`) are
safe as long as no thread works for two servers at once; shared pools (C2ME's worldgen workers) only
use them within one task.

## Fixes (`mods/cohost-fixes`)

`CoHost` tracks the servers running in the JVM (from `runServer` to `onServerExit`); the mixins keep
JVM-wide actions for the last one:

| Mixin | Fix |
| --- | --- |
| `C2meShutdownExitMixin` | cancels C2ME's `log4j2shutdownhookisnomore` handler (log4j shutdown + `System.exit`) while other servers run (MixinSquared `@TargetHandler`, only with C2ME loaded) |
| `DedicatedServerMixin` | `stopServer` calls `Util.shutdownExecutors()` only for the last server |
| `ServerWatchdogMixin` | a hung tick stops that server (`halt`) instead of exiting the JVM |
| `BlockableEventLoopMixin` | `delayedCrash` per event loop; crashes relayed from shared worker threads are logged, not thrown into every server, while several run |
| `CohostFixesMod` | starting a second server with Async loaded fails |

`RegionFileVersion.selected` is set only by `Main` (the first server's `server.properties`), so
servers started in-process share the host's region compression; nothing to fix.

## Test (`mods/cohost-test`)

`./dev ./gradlew :mods:cohost-test:cohostTest -PacceptMinecraftEula=true` (also in CI's network job)
runs a Fabric server with C2ME, ScalableLux, VMP, FerriteCore, Fabric API and `cohost-fixes` under
the static-guard agent in strict mode, each server thread bound to its own tenant. A dev-only mod
then starts extra servers next to it: A gets a delayed crash, B a hung tick (its watchdog fires),
C a clean stop, and after each the main server must still tick, the shared executors must still
run tasks and the JVM must still be there. Any static written by two servers' threads throws in strict
mode and fails the test; reviewed exceptions go into `mods/cohost-test/static-guard-allow.txt`.

## Recommendation

* **Don't isolate with class loaders.** One class loader per server would hide every static, but
  Fabric Loader and Mixin are JVM singletons, each copy of Minecraft costs its metaspace and its own
  JIT warm-up, and the shared bootstrap (registries, data packs, mixins) is the thing that makes a
  second server cheap to start.
* **Share, and fix the few real leaks at the source.** Bootstrap-time statics are the same for every
  server and should stay shared. The leaks that matter are the short list above; fix them with mixins
  in the creative host mod (watchdog, delayed crash, `/chase`), configuration (C2ME module, region
  compression, `max-tick-time`) and the mod list (no Async).
* **Keep a guard in the tests.** The e2e test of the creative host should start two servers in one
  JVM under the agent in `strict` mode with the host's `Tenants.bind`, and fail on any cross write
  that is not on a reviewed allowlist. Run the scanner on every mod update and diff its output
  (`--tsv`): new `INSTANCE_STATIC`, `JVM_GLOBAL` or runtime `STATIC_WRITE` findings need a look.
* **Isolate only as a stopgap.** `TenantStatics` works for plain fields (tests in this module) but not
  for state reached through reflection, `VarHandle`, `Unsafe` or a static container's contents that
  were filled at bootstrap; use it for a field we cannot patch yet, not as the isolation model.
