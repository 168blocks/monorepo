> Findings of the standalone egglog-on-Endive spike (2026-11-05), kept as written. The spike was a Maven
> project; its code now lives in `libs/pgecs-eqsat` (`FlatMemory`, `Egglog`, the `egglog-wasi` crate, and
> the workload as `src/test/resources/join-reorder.egg`). Current numbers and usage: `libs/pgecs-eqsat/README.md`.

# egglog in Endive via wasip1: findings

Date: 2026-11-05. egglog @ 6c3a0c9 (v3.0.0), Endive 1.2.0, Java 25.0.5 (nix), Kotlin 2.4.21, Rust 1.91.0.
Machine: 4 vCPU cloud container. Numbers are rough, single-machine, not rigorous benchmarks.

## Verdict

**Update (round 2):** after tuning, AOT runs this query in ~26 ms on a fresh e-graph and ~22 ms with a persistent one, about 5x native. See "Round 2" below. The round 1 numbers follow.

It works end to end in all three Endive modes with zero changes to egglog. The API shape
"program string in, extracted terms out" is a ~60-line Kotlin class (`spike.Egglog`).
The catch is speed: steady state is about 12x slower than native egglog and ~8x slower than
wasmtime, and the first call in a fresh JVM costs ~0.5 s. Build-time (AOT) compilation is
the mode to use: fast startup, low memory, same steady-state speed as the runtime compiler.

## What worked

- **Build.** `cargo build --target wasm32-wasip1` of egglog with `default-features = false`
  just works. No patches, no wasm-bindgen. rayon/threads are not a problem (single-threaded in wasm).
  Output is ~5 MB (LTO, opt-level 3, panic=abort). Imports are 15 plain WASI p1 functions
  (fd_*, clock, random, environ, proc_exit), all of which Endive's `WasiPreview1` provides.
- **ABI.** Reactor-style cdylib exports: `egglog_alloc`, `egglog_run(ptr,len) -> rc`,
  `egglog_out_ptr/len`. One Endive instance serves many programs sequentially; each run builds a
  fresh `EGraph`. Errors (parse/type errors) come back as rc=1 plus the message.
- **Interpreter, runtime compiler, build-time compiler** all produce the same, correct plan
  for the query workload (both filters pushed down into scans, joins reordered):
  `(Project "orders.id" (Join ... (FScan (EqConst "customer" "region" 3) "customer") (Join ... (Join ... (FScan (EqConst "item" "kind" 7) "item") (Scan "lineitem")) (Scan "orders"))))`
- **Build-time compiler** via `endive-compiler-maven-plugin` 1.2.0: no functions fell back to the
  interpreter; generates ~7 MB of classes. Works fine from Kotlin (Kotlin sees the generated Java).
- **Tooling** via a nix flake (rust-overlay for the pinned toolchain plus the wasip1 target).

## Numbers

Workload: `query.egg`, a 4-way join + 2 conjunctive filters + projection (~15 e-nodes), rules for join
commutativity/associativity, filter split, filter pushdown, filter-into-scan fusion; `saturate`, then
`extract`. Times are per full egglog run (parse program, build e-graph, saturate, extract).

| Runtime | First query (cold) | Steady state median (min) | Startup | Peak RSS |
|---|---|---|---|---|
| native egglog | n/a | 4.3 ms (4.2) | n/a | 9 MB |
| wasmtime 50 | n/a | 7.0 ms (6.6) | n/a | 159 MB |
| Endive AOT (build-time) | ~490 ms | 58 ms (43) | 0.28 s instantiate, no parse | ~200 MB (27 MB heap) |
| Endive runtime compiler | ~530 ms | 50 ms (33) | 2.2 s parse + 4.6 s compile | ~1.1 GB (517 MB heap) |
| Endive interpreter | ~1.9 s | ~1.8 s | 2.1 s parse | ~1.2 GB |

Steady state = 2nd half of 1500 runs, with `-Dmem=array -XX:-DontCompileHugeMethods`.
egglog's wasm linear memory stayed at 2 MB for this workload.

### JVM tuning that mattered

- **`-XX:-DontCompileHugeMethods`** is the big one. egglog's hot functions (free-join `run_plan`,
  36 KB of wasm each; `run_rule_set`; extractor cost computation) become JVM methods above the
  8000-bytecode limit, so HotSpot leaves them interpreted by default.
- **`ByteArrayMemory` instead of the default `ByteBufferMemory`** (`withMemoryFactory { ByteArrayMemory(it) }`):
  the profile was dominated by `ByteBufferMemory.readInt/writeI32`.
- Medians over 200–300 runs (AOT): default 156 ms; flag only 137 ms; byte[] memory only 139 ms;
  both 85 ms. With longer warm-up (1500 runs) both together reach ~50–60 ms.
- Warm-up is long: it takes hundreds of runs to reach steady state. Larger C2 node limits and
  ParallelGC made no difference.
- AOT runs fine with `-Xmx256m`; the 0.5–1 GB heap in runtime/interpreter modes is from parsing
  and compiling the 5 MB module at runtime.

## Blockers and risks

- **Per-query latency.** ~50 ms steady state and ~0.5 s cold for a 15-node query is fine for an
  offline or cached optimizer, marginal for planning every query online. Mitigations: cache plans by
  query shape, keep one warm instance per thread, keep the rule set small, or move the hot path
  (rules run per query) out of a full egglog program parse each time.
- **No threads.** Each instance is single-threaded and not thread-safe; use a pool of instances.
  egglog's parallel features are unused in wasm.
- **Runtime compiler is costly to start** (~7 s and ~1 GB to parse + compile 5 MB of wasm). Use AOT
  unless the wasm has to be swapped at runtime.
- Gradle needs the third-party `wasm2class` plugin for AOT. Maven has the official plugin.
- Not tried yet: wasm-opt (binaryen) on the module, trimming egglog features to shrink it,
  a persistent EGraph with incremental `push`/`pop` across queries, CRaC or a warm-up step at startup.

## Round 2: pushing AOT performance (same day)

Result: AOT steady state went from **~55 ms to ~26 ms per query** on a fresh e-graph, and **~22 ms**
with a persistent e-graph. That is ~5-6x native egglog (4.3 ms) and ~3x wasmtime (7 ms), down from
~12x and ~8x. All runs are AOT, `-XX:-DontCompileHugeMethods`, 1000-1200 runs, median of the 2nd half.
Raw log: `bench.log`.

| Step | Median | What changed |
|---|---|---|
| Round 1 baseline | 55 ms | opt-level 3, Endive's paged `ByteArrayMemory` |
| C2 only (`-XX:-TieredCompilation`) | 46 ms | C1 bails out ("out of virtual registers") on ~30 huge egglog functions, incl. the hot `get_index`, so they stayed in the bytecode interpreter |
| Rust `opt-level = "s"` | 36 ms | Smaller wasm functions turn into JVM methods that HotSpot handles far better |
| `FlatMemory` (one contiguous `byte[]`) | 25 ms | Endive's `ByteArrayMemory` splits memory into 64 KiB pages, which costs an extra load and branch on every access. `jvm/src/main/java/spike/FlatMemory.java`, ~150 lines |
| Tiered compilation back on | 26 ms | Once the code is small and memory is flat, C2-only no longer helps, and tiered warms up faster |
| Persistent e-graph (`egglog_session_run`) | 22 ms | Rules loaded once, then `(push) query (extract) (pop)` per call. Small win, since saturation dominates (natively 3.6 of 4.4 ms) |

Tried, did not help:
- `wasm-opt -O3` is **4x worse** (207 ms): its inlining makes functions exceed the JVM's 64 KB method
  limit, so Endive falls back to its interpreter for them.
- `opt-level = "z"` (31 ms), lower or higher LLVM inline thresholds, no LTO (all ~25 ms, noise),
  ParallelGC, bigger C2 node limits.
- JDK 25 AOT cache (`-XX:AOTCacheOutput` training run, needs a jar classpath): instantiate 290 → 170 ms
  and runs 10-50 about 1.5-2x faster, but it doesn't shorten the time to steady state.

### Warm-up is the remaining problem

Median ms per query, by run number, final config: runs 0-10: 190, 10-25: 133, 25-50: 99, 50-100: 56,
100-200: 49, 200-400: 33, 400-800: 28. The first query is ~0.6 s. It takes a few hundred queries
(~15 s of work) before the JIT settles. JIT-compiled code lives in the generated classes, so it is shared by
every instance in the JVM: warming one instance (e.g. replaying a few hundred representative
queries in a background thread at startup) warms them all.

### What's left (would need Endive changes)

Profile of the final config: ~14% is bounds checks in the `byte[]` VarHandle accesses, ~3.5% is
`Instance.global(i)` (the shadow stack pointer is read through an indirect lookup on every call),
and ~2.5% is `checkInterruption()` (a `Thread.isInterrupted` check in loops). Generating globals as fields
and making the interruption check optional are reasonable upstream proposals, worth maybe 5-10%.
Beyond that, the remaining gap is HotSpot compiling very large, register-heavy methods, plus egglog's
work per query. Smaller rule sets or bounded schedules (instead of `saturate`) would move the
needle more than runtime tuning now.

### Recommended config

- Rust: `opt-level = "s"`, LTO, `codegen-units = 1`, `panic = "abort"`.
- Endive: build-time compiler, `withMemoryFactory { FlatMemory(it) }`.
- JVM: `-XX:-DontCompileHugeMethods`, tiered compilation on (default), warm up at startup.
- API: persistent e-graph per instance, rules loaded once, `(push)`/`(pop)` per query; pool instances per thread.

