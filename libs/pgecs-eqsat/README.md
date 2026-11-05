# pgecs-eqsat

Equality saturation for the pgecs query planner: [egglog](https://github.com/egraphs-good/egglog)
(Rust, MIT) compiled **unmodified** to `wasm32-wasip1` and run inside the JVM by
[Endive](https://endive.run) (Apache-2.0). The planner will serialize logical plans as egglog terms,
run the core and per-source rules, and parse the extracted term back (pgecs design doc, *Optimizer and
equality saturation*). This module is the runtime part only.

Two backends, both compiled at build time:

* **Native** (default where available): Endive's experimental **Redline** backend compiles egglog to
  machine code with Cranelift. Loaded through Panama (Java 25); no JVM warm-up. Built for Linux
  x86_64 and aarch64.
* **Bytecode** (fallback): Endive's build-time compiler turns egglog into JVM classes, run with a
  contiguous `FlatMemory`. Runs on any JVM; needs warm-up.

`Egglog.create()` picks native when this JVM can load it, otherwise bytecode (with a warning in the
log). `-Dpgecs.eqsat.backend=native|bytecode|auto` forces one; `Egglog.create(EgglogBackend.BYTECODE)`
too. `egglog.backend` says which one an instance runs on.

```kotlin
val egglog = Egglog.create()          // one instance, not thread-safe
egglog.run("""
    (datatype Math (Num i64) (Var String) (Mul Math Math))
    (rewrite (Mul a (Num 1)) a)
    (let e (Mul (Var "x") (Num 1)))
    (run 5)
    (extract e)
""")                                  // == "(Var \"x\")\n"

// rules loaded once per instance, each query between (push) and (pop)
val pool = EgglogPool(size = 4, prelude = rulesAndDatatypes)
pool.query("(let q ...) (run-schedule (saturate (run))) (extract q)")
```

Errors from egglog (parse, type, runtime) are thrown as `EgglogException` with egglog's message.

**JVM flags** for every JVM that runs the optimizer (backend, Velocity, Minecraft servers):
`--enable-native-access=ALL-UNNAMED` (Redline's Panama runner; without it Java 25 prints a warning,
later JDKs refuse) and `-XX:-DontCompileHugeMethods` (the bytecode fallback, see below). The tests and
the benchmark set both.

## How it is built

| Step | Where |
| --- | --- |
| `egglog-wasi/`: a small crate with a reactor-style C ABI over egglog (`egglog_alloc`, `egglog_run`, `egglog_session_run`, `egglog_session_reset`, `egglog_out_ptr/len`) | this directory |
| Nix builds it for `wasm32-wasip1` with Rust 1.91.0 (rust-overlay), egglog pinned to a commit: once with `opt-level = "s"` (bytecode) and once with `3` (native) | `nix/egglog-wasm.nix`, `nix build .#egglog-wasm` |
| **Native:** Nix runs Endive's Redline generator (Cranelift, itself JVM bytecode) on the opt-level 3 build: `EgglogNativeModule` with native code per target (~37 MB x86_64, ~29 MB aarch64) | `nix/egglog-redline.nix`, `redline/RedlineGen.java`, `nix build .#egglog-redline` |
| **Bytecode:** Gradle runs Endive's build-time compiler on the `"s"` build (`compileEgglog`, `EgglogModule`, ~6 MB of classes), failing if any function would fall back to the interpreter | `build.gradle.kts` |
| The dev shell sets `BLOCKS_EGGLOG_WASM` (the `"s"` wasm) and `BLOCKS_EGGLOG_REDLINE` (the generated native module); Gradle compiles and packages both | `flake.nix`, `build.gradle.kts` |
| `FlatMemory`: Wasm linear memory as one contiguous `byte[]` for the bytecode backend (Endive's own is paged; Redline has its own off-heap memory) | `src/main/kotlin` |

**The native build is slow**: ~23 minutes for the two Linux targets on 4 cores. It is a Nix derivation so
it runs once per change of egglog, the crate, `RedlineGen.java` or Endive, and is otherwise taken from
the Nix store (CI caches the store). Add targets (`x86_64-apple-darwin`, `aarch64-apple-darwin`,
`x86_64-pc-windows-msvc`, `aarch64-pc-windows-msvc`) in `nix/egglog-redline.nix` if pgecs runs there;
each adds ~12 minutes and ~30 MB to the jar (~15 MB compressed). Elsewhere the bytecode backend is used.

The Redline jars in `nix/egglog-redline.nix` are pinned by hash; when `endive` changes in
`gradle/libs.versions.toml`, update them too (the generated code and the runner must match).

To move to another egglog commit: change `rev` and `hash` in `nix/egglog-wasm.nix`, then regenerate
`egglog-wasi/Cargo.lock` against that source (put the checkout next to the crate as `../egglog` and run
`cargo generate-lockfile`; the lock pins egglog's dependencies for the Nix build).

## Performance

`./dev ./gradlew :libs:pgecs-eqsat:benchmark [-Pruns=3000] [-Pbackend=native|bytecode]` times the
join-reordering workload (`src/test/resources/join-reorder.egg`: 4-way join, 2 filters, projection,
join commutativity and associativity, filter splitting and pushdown, `saturate`, `extract`).

Both backends on the same 4-vCPU cloud container (2026-11-05, Java 25.0.5, 1200 runs; this machine is
slower than the main dev machine, so compare the columns, not the numbers with older ones):

| | Native (Redline) | Bytecode |
| --- | --- | --- |
| Instantiate (first in the JVM) | ~1.5 s (loads the native code) | ~0.4 s |
| First query | ~50 ms | ~0.9 s |
| Runs 0-10 / 50-100 / 200-400 | ~20 / ~16 / ~15 ms | ~320 / ~140 / ~60 ms |
| Steady state, fresh e-graph per query | 14.8 ms | 45 ms |
| Steady state, `EgglogPool` (session rebuilt every 200 queries) | 13.7 ms | 29 ms |

On the main dev machine the bytecode backend's steady state was 10-15 ms (fresh) and ~10 ms (pool), with
runs 50-100 / 100-200 / 200-400 at ~60 / ~34 / ~20 ms. Native egglog: 4.3 ms (spike).

The point of the native backend is mostly the warm-up: it is at full speed from the first query, while
the bytecode backend needs a few hundred.

What matters for the bytecode backend, from the spike (`docs/egglog-endive-spike.md`):

* **`-XX:-DontCompileHugeMethods` on every JVM that runs the optimizer.** egglog's hot functions become
  JVM methods above HotSpot's 8000-bytecode limit, which it otherwise never JIT-compiles (2-3x slower).
  The tests and the benchmark set it. It applies to the whole JVM, including Minecraft's code.
* **Rust `opt-level = "s"`**: smaller functions JIT far better than `opt-level = 3`. `wasm-opt -O3`
  is 4x worse: its inlining pushes functions over the JVM's 64 KB method limit, back into Endive's
  interpreter. Native code is the other way round (opt-level 3: ~12 ms, `"s"`: ~16 ms in the spike),
  hence the two wasm builds.
* **Contiguous memory** (`FlatMemory`): ~30% faster than Endive's paged `ByteArrayMemory`.
* **Warm-up is long**: a few hundred queries before HotSpot settles. JIT-compiled code lives in the
  generated classes, so it is shared by all instances: warming up one instance at startup (replaying
  representative queries on a background thread) warms them all.
* Instances are single-threaded; use one per thread or the pool. Smaller rule sets and bounded
  schedules instead of `saturate` are the next lever, more than runtime tuning.

Found while bringing it in: egglog's `(pop)` does not release everything a query allocated. A session
e-graph grows by ~20 KiB per push/query/pop (67 MiB of Wasm memory after 3000 queries) and gets slower
as it grows (~17 ms instead of ~10). `EgglogPool` therefore rebuilds each instance's session from the
prelude every `rebuildEvery` (200) queries, which keeps it at a few MiB.
