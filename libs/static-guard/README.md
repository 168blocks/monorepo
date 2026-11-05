# static-guard

Finds, traces and contains process-global (static) state that would be shared by several independent
instances co-hosted in one JVM: the creative host of #6 runs many `MinecraftServer`s in one process.
Nothing in here is Minecraft-specific; findings and the recommendation for the creative host are in
[`docs/co-hosting.md`](../../docs/co-hosting.md).

Three parts:

| Part | What | Cost |
| --- | --- | --- |
| `scan` (Kotlin, `scan/`) | Static bytecode pass over jars (jar-in-jar too) with the JDK ClassFile API | ~4 s for Minecraft + 7 mods (14k classes) |
| agent: trace / guard (Java, `agent/`) | `-javaagent`: hooks every `putstatic` (and chosen `getstatic`), attributes it to the instance of the current thread | ~20% slower bootstrap; hooks are a thread-local read and an array CAS |
| agent: isolate | rewrites `getstatic`/`putstatic` of listed fields to per-instance slots | only the listed sites |

## Scanner

```sh
./dev ./gradlew :libs:static-guard:installDist
libs/static-guard/build/install/static-guard/bin/static-guard \
  --instance-type net/minecraft/server/MinecraftServer --instance-type net/minecraft/server/level/ServerLevel \
  --tsv findings.tsv --fields watch.txt server-26.3.jar mods/*.jar
```

Kinds, most severe first: `INSTANCE_STATIC` (a static of a per-instance type), `JVM_GLOBAL`
(`System.exit`, `Runtime.halt`, `System.setOut`, default handlers, ...), `STATIC_WRITE` (`putstatic`
outside the owner's `<clinit>`), `STATIC_CONTAINER_MUTATION` / `STATIC_ARRAY_STORE` (a mutating call
or element store on an object read straight from a static), then the usually benign `LAZY_INIT`,
`STATIC_CACHE` (`computeIfAbsent`), `THREAD_LOCAL`, and `MUTABLE_TYPE` (static final collection with
no mutation found). Classes annotated `@Mixin` are marked, since their code ends up in the target.

Limits: one method at a time; the value of a `getstatic` is followed through straight-line code to its
first use only; reflection, `VarHandle`, `Unsafe` and JNI are invisible. It cannot tell writes done
once at bootstrap (shared on purpose) from writes while an instance runs; the tracer can.

## Agent

```sh
java -Dfabric.systemLibraries=static-guard-agent.jar \
     -javaagent:static-guard-agent.jar='trace;sealFile=/tmp/up;bind=Server thread=1;report=/tmp/report.txt' \
     -jar fabric-server-launch.jar nogui
```

Options (`;`-separated): `include=` / `exclude=` class prefixes, `gets=all|watch|none`,
`watch=<file>` (fields whose reads are tracked, e.g. the scanner's `--fields`), `isolate=<file>`
(`owner/Name.field share|copy|reset` per line), `bind=<thread-name regex>=<tenant>`, `strict`,
`trace`, `sealAfterMs=` / `sealFile=`, `report=`, `quiet`. Details in `StaticGuardAgent`.

* A host binds each instance's threads with `Tenants.bind(id)` (threads they start inherit it); threads
  of no tenant (bootstrap, shared pools) are not attributed.
* Per field the guard keeps the last writing tenant and the first reading one, and reports
  `CROSS_WRITE` (a second tenant writes), `CROSS_READ` (a tenant reads another's value) and
  `SHARED_READ` (two tenants read the same static container). `strict` throws at a cross write.
* `trace` counts every static write with thread names, split into startup and runtime at the seal:
  for a single instance this lists the statics that change while it runs.
* Fabric: Knot only lets game classes see classpath jars listed in `fabric.systemLibraries`, so the
  agent jar must be listed there (its hooks are called from transformed game classes). The agent
  sees classes after Mixin, as the JVM defines them. It is plain Java without dependencies so it
  adds no second Kotlin stdlib to the game's class path.

`TenantStatics` (isolate) keeps the real static for threads of no tenant and gives each tenant its
own slot, seeded from the real value (`share`), a copy (`copy`: arrays, `clone()`) or null/zero
(`reset`). It is the IBM multi-tenant JVM's "static virtualisation" applied to a short list of fields.
