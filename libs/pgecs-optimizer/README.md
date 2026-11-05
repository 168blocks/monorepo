# pgecs-optimizer

Equality saturation for the pgecs query planner: [egglog](https://github.com/egraphs-good/egglog)
(Rust, MIT) compiled **unmodified** to `wasm32-wasip1` and run inside the JVM by
[Endive](https://endive.run) (Apache-2.0) with its build-time (AOT) compiler. The planner will
serialize logical plans as egglog terms, run the core and per-source rules, and parse the extracted
term back (pgecs design doc, *Optimizer and equality saturation*). This module is the runtime part only.

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

## How it is built

| Step | Where |
| --- | --- |
| `egglog-wasi/`: a small crate with a reactor-style C ABI over egglog (`egglog_alloc`, `egglog_run`, `egglog_session_run`, `egglog_session_reset`, `egglog_out_ptr/len`) | this directory |
| Nix builds it for `wasm32-wasip1` with Rust 1.91.0 (rust-overlay), egglog pinned to a commit, `opt-level = "s"` | `nix/egglog-wasm.nix`, `nix build .#egglog-wasm` |
| The dev shell sets `BLOCKS_EGGLOG_WASM` to the result | `flake.nix` |
| Gradle runs Endive's build-time compiler on it (`compileEgglog`, ~6 MB of classes in the jar), failing if any function would fall back to the interpreter | `build.gradle.kts` |
| `FlatMemory`: Wasm linear memory as one contiguous `byte[]` (Endive's own is paged) | `src/main/kotlin` |

To move to another egglog commit: change `rev` and `hash` in `nix/egglog-wasm.nix`, then regenerate
`egglog-wasi/Cargo.lock` against that source (put the checkout next to the crate as `../egglog` and run
`cargo generate-lockfile`; the lock pins egglog's dependencies for the Nix build).

## Performance

`./dev ./gradlew :libs:pgecs-optimizer:benchmark [-Pruns=3000]` times the join-reordering workload
(`src/test/resources/join-reorder.egg`: 4-way join, 2 filters, projection, join commutativity and
associativity, filter splitting and pushdown, `saturate`, `extract`). On the main dev machine
(2026-11-05, Java 25.0.5):

| | Time |
| --- | --- |
| Instantiate (first in the JVM) | 130-200 ms |
| First query | 350-650 ms |
| Runs 50-100 / 100-200 / 200-400 | ~60 / ~34 / ~20 ms |
| Steady state, fresh e-graph per query | 10-15 ms |
| Steady state, `EgglogPool` (session rebuilt every 200 queries) | ~10 ms |
| Native egglog (spike, other machine) | 4.3 ms |

What matters, from the spike (`docs/egglog-endive-spike.md`):

* **`-XX:-DontCompileHugeMethods` on every JVM that runs the optimizer.** egglog's hot functions become
  JVM methods above HotSpot's 8000-bytecode limit, which it otherwise never JIT-compiles (2-3x slower).
  The tests and the benchmark set it. It applies to the whole JVM, including Minecraft's code.
* **Rust `opt-level = "s"`**: smaller functions JIT far better than `opt-level = 3`. `wasm-opt -O3`
  is 4x worse: its inlining pushes functions over the JVM's 64 KB method limit, back into Endive's
  interpreter.
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
