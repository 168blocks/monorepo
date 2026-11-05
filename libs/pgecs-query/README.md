# pgecs-query

The pgecs query engine: it turns a first-level IR query (`libs/pgecs-ir`) into SQL on the
`ecs_component` table, runs it and decodes the rows. Design: `docs/pgecs.md` ("Queries").

## Pipeline

1. **Lowering** (`lower/Lowering.kt`): IR to a logical plan (`plan/Plan.kt`) of blocks of scans,
   probes (`Pred.Has`), predicates, optional groups (left joins) and sub-blocks (`exists`).
   * Kotlin's two-valued semantics become SQL's three-valued logic: predicates go into negation
     normal form, `==` becomes `=` when one side is never null, else `is not distinct from`.
     A parameter that is null at runtime becomes a constant, so the null set is part of the plan key.
   * A match whose positions are all bound is a probe (primary key, at most one row). A match with a
     wildcard (`*` subject, target or relation) is a `distinct` derived table, so it never multiplies
     rows. UUIDs compare as text; instants and durations are epoch millis (`bigint`);
     `now()` is bound once per execution.
2. **Optimisation** (`opt/`): the Kotlin `Simplifier` always runs; egglog (`core.egg`, via
   `libs/pgecs-eqsat`) runs a bounded schedule over predicates. A compile waits at most
   `eqsatTimeout` (250 ms), then uses the simplifier's plan and upgrades when egglog finishes.
3. **SQL** (`sql/SqlCompiler.kt`, `sql/JoinOrder.kt`): component names inlined as literals, values
   as typed binds, joins ordered greedily from per-component row counts (`plan/Stats.kt`, read from
   `pg_stats`). String ordering uses `collate "C"`. Modes `FETCH`, `ONE` (limit 2), `FIRST`, `COUNT`.
   Row locks: `for update of cN [nowait | skip locked]`. Field SQL: `SqlCompiler.fieldSql`; index
   expressions must use exactly this rendering.
4. **Cache** (`QueryEngine.kt`): two Caffeine async caches, (shape, null params) to plan and
   (plan, mode) to SQL, single-flight, weighted, expire after access. Plans are rebuilt when stats
   drift 10x. `metrics()` reports hits, compile times and eqsat use.
5. **Hot shapes** (`HotShapes.kt`): shapes used in a process are flushed to `ecs_query_shape`;
   at startup the most used ones are parsed (`Canonical.parse`) and compiled, and egglog warmed up.
6. **Execution** (`exec/Execution.kt`): binds by kind and decodes by the IR's value types.

## Tests

`./dev ./gradlew :libs:pgecs-query:test` (real PostgreSQL via `TestPostgres`):

* `EngineTest`: scenarios from the design doc (IAM lookups, relations, wildcards, optionals,
  aggregates, locks, caching, drift, hot shapes).
* `OptimizerTest`: the egglog rules one by one, including three-valued-logic safety.
* `RandomQueryTest`: 1000 random queries checked against `Oracle`, a naive reference evaluator of
  the IR semantics, with and without egglog.

## Known differences from Kotlin semantics

* `lowercase()` / `uppercase()` follow the database's rules, which differ from Kotlin's for
  non-ASCII text under the `C` locale; `length` counts code points, not UTF-16 units.
* UUIDs order as their text form, not by `UUID.compareTo`.
* A JSON field missing from a stored value reads as null, even if the Kotlin class has a default.
* Only the Postgres source exists; queries on other sources throw `UnsupportedQueryException`.
