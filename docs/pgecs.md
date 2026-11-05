# pgecs design

Status: design, agreed November 2026. Implementation is tracked in GitHub issues (#10, optimizer: #16, #18).

## Scope and requirements

pgecs is a Kotlin and Java client library that treats one Postgres database as the ECS. Every durable fact 168blocks keeps outside Minecraft lives there, and a committed `SERIALIZABLE` Postgres transaction is the only point where state becomes true.

### What it stores

- Identity and access: invite-only accounts, emails, linked Minecraft UUIDs, per-server access grants and denials, invites, OTPs, sessions.
- Anything else Minecraft itself does not persist: moderation records, economy or progression data, audit trails, player presence, the outbox.
- Not per-tick game state (positions, block changes). That stays in Minecraft or in the ephemeral in-memory ECS.

### Requirements, in priority order

1. **Integrity first.** Every write happens inside a `SERIALIZABLE` transaction, and the database enforces it. Invariants (uniqueness, exclusivity, referential integrity, value shape, reserved entities) are enforced by Postgres wherever possible, not only by Kotlin code.
2. **Postgres is the only sync point.** There are no distributed locks and no caches that accept writes. The processes are the Velocity proxies, the Fabric lobby servers, the main game server, the process hosting many creative `MinecraftServer` instances, and the Ktor backends. They write concurrently and stay correct.
3. **Flecs semantics.** Entities, components, tags, pairs (relationship, target), wildcards, traits and cleanup policies behave like flecs, unless a section here says otherwise.
4. **Fixed schema.** One entity table and one component table. Registering a component never creates a table. Per-component indexes and constraints come from an in-code registry that pgecs applies when the registry is frozen.
5. **Expressive queries.** A Kotlin DSL and a Java DSL with value predicates, joins through variables, ordering, aggregation and paging. Both are compiled to a single SQL statement for the Postgres source.
6. **Federation.** One query can span Postgres, the in-process ephemeral in-memory ECS, and in-process virtual sources such as the live Minecraft server.
7. **Scheduled systems.** Periodic jobs such as expiry sweeps run safely with several JVMs alive. A lease stops two processes from starting the same run, and `SKIP LOCKED` lets batches run in parallel without overlap.

### Non-goals

- Distributed or cross-source ACID transactions. Only Postgres writes are transactional.
- Dynamic DDL at runtime, outside freeze.
- Matching flecs' raw performance. The target is correctness at small-to-medium scale, up to a few million component rows.

## Data model

An entity holds each id at most once, exactly like flecs. Multiple emails are modelled as flecs relationships: pairs pointing to separate email entities, not repeated components.

### Flecs concepts mapped

| Flecs | pgecs | Storage |
| --- | --- | --- |
| Entity (64-bit id) | Entity, UUIDv7 | row in `ecs_entity` |
| Component | Component with a JSON value, such as `b8:iam:email:v1` | row with nil `target`, `value` set |
| Tag | Component with no value | row with `value IS NULL` |
| Pair `(Rel, Target)` | Pair. The relationship must be a registered relation; the target is any entity | row with `target` set |
| Component is an entity | Each component, relation and capability type is also an entity, id = UUIDv5(name) | catalog row + entity row |
| Wildcard `(Rel, *)`, `(*, Tgt)` | Same, in queries | index scans on `(rel, target)` / `(target, rel)` |
| Exclusive | Adding a second pair replaces the first, in the same tx | partial unique index on `(entity)` |
| Acyclic (on `ChildOf`) | Cycles are rejected | trigger walks the target's ancestors |
| Symmetric | Adding `(R, b)` to `a` also adds `(R, a)` to `b`; removal removes both | trigger in the same tx |
| OneOf | The target must be a child of the declared parent entity | trigger check |
| Cleanup: `OnDelete`, `OnDeleteTarget` | Same policies (Remove, Delete, Panic) | FK cascade + trigger |
| `ChildOf` | `pgecs:child-of`, Exclusive + Acyclic + `OnDeleteTarget: Delete` | pairs |
| `IsA` (prefabs, inheritance) | Not in v1. Added later with flecs semantics spelled out | none |
| Hooks (`on_add`, `on_set`, `on_remove`) | In-transaction hooks in the library | Kotlin, same tx |
| Observers | Post-commit observers over the change log | `ecs_change` |
| Systems + pipeline | Scheduled systems | see Systems section |

One deliberate restriction versus flecs: a relationship must be a registered relation type. An arbitrary entity like `Bob` cannot be used as the relationship in `(Bob, x)`, but it can always be a target.

### Multiple values of one kind

Flecs never stores the same id twice on one entity. For several emails, pgecs uses flecs relationships, with each value as a distinct target entity:

- Each email is its own entity with `b8:iam:email:v1 = {"address":"aea","verifiedAt":…}` and the pair `(pgecs:child-of, <account>)`. `verifiedAt` uses `InstantAsEpochMillis`, so it is range-scannable.
- Uniqueness of the address across all accounts is a unique index declared on the component, so two accounts can never claim one email, even under concurrency.
- Deleting the account deletes its emails through the `child-of` cleanup policy.
- Each email can carry its own data later (verified, primary, added-at) without changing the account.

The Minecraft link works the same way: an entity with `b8:iam:mc-uuid:v1 = {"uuid":…}`, unique on the UUID, child of the account. Access flags stay plain tags on the account (`b8:iam:smpmain-access-allowed`), or become pairs `(b8:iam:access, <server entity>)` if servers turn into entities.

The alternative, a pair whose target is a value-derived id such as `(b8:iam:email:v1, uuidv5("aea"))`, avoids the extra entity. But it breaks the rule that targets are real entities, so foreign keys and cleanup no longer apply. pgecs does not use it.

### Naming and versions

Component names roughly follow `<project>:<namespace(s)>:<name>:<version>`, for example `b8:iam:email:v1` or `b8:mc:world:profile:v2`. The project segment is required, and there can be any number of namespace segments. The version is recommended for value components but optional. The catalog only enforces non-empty, colon-separated segments with no whitespace or quotes. Additive changes (new optional fields with defaults) stay on the same name. Any other shape change is a new version plus a migration system, so a stored value never changes meaning in place.

Namespaces: `pgecs:` is reserved for the library's own core components and relationships (`pgecs:child-of`, `pgecs:outbox:v1`, `pgecs:process:v1`, `pgecs:endpoint:v1`, `pgecs:can`). Everything 168blocks-specific uses `b8:`, including generic-looking utilities like expiry (`b8:util:expires-slow:v1`), which is a b8 module built on pgecs systems. The catalog refuses a `pgecs:` name unless the library registers it itself.

## Postgres schema

Two tables hold everything: `ecs_entity` for liveness and locking, and `ecs_component` with a `target` column so pairs fit the primary key. Indexes are declared per component, and there is no global GIN. Postgres 18 (native `uuidv7()`) and `pg_jsonschema` are required.

```sql
-- Every entity, including component, relation and capability types. The nil
-- UUID is a reserved entity meaning "no target".
create table ecs_entity (
  id         uuid primary key default uuidv7(),
  created_at timestamptz not null default now(),
  version    bigint not null default 0   -- bumped by trigger on any component change
);
insert into ecs_entity (id) values ('00000000-0000-0000-0000-000000000000');

-- Catalog. Written only by registry freeze.
create table ecs_component_type (
  name             text primary key check (name ~ '^[^:\s''"\\]+(:[^:\s''"\\]+)+$'),
  entity           uuid not null unique references ecs_entity(id),  -- uuidv5(ns, name)
  kind             text not null check (kind in ('tag', 'value', 'relation', 'capability')),
  state            text not null check (state in ('pending', 'active', 'retired')),
  traits           jsonb not null default '{}',  -- exclusive, acyclic, symmetric, oneOf, cleanup
  json_schema      jsonb null,                   -- value kinds; checked via pg_jsonschema
  fingerprint      text not null                 -- per component, compared at freeze
);

-- All component data.
create table ecs_component (
  entity     uuid  not null references ecs_entity(id) on delete cascade,
  rel        text  not null references ecs_component_type(name),
  target     uuid  not null default '00000000-0000-0000-0000-000000000000'
                   references ecs_entity(id) on delete cascade,
  value      jsonb null,
  updated_at timestamptz not null default now(),
  primary key (entity, rel, target)
);

-- Generic indexes: component -> entities, and reverse pair lookups.
create index ecs_c_rel    on ecs_component (rel, target, entity);
create index ecs_c_target on ecs_component (target, rel, entity)
  where target <> '00000000-0000-0000-0000-000000000000';
```

### Database-enforced rules

- **Serializable only.** Setup runs `ALTER DATABASE … SET default_transaction_isolation = 'serializable'`. The core trigger on `ecs_component` and `ecs_entity` also rejects any write where `current_setting('transaction_isolation') <> 'serializable'`. Postgres' guarantee only holds among serializable transactions, so this keeps every trigger-based invariant safe, even against a hand-written SQL session.
- **Reserved entities.** A `BEFORE DELETE` trigger on `ecs_entity` refuses to delete the nil entity, ever. It also refuses component, relation and capability entities, except inside freeze when it retires them (freeze sets a transaction-local `pgecs.retiring` setting naming exactly those ids). Without this, one delete of the nil entity would cascade through every plain component in the database.
- **Kinds and schemas.** The core trigger checks `kind` (tags have null values, value components do not, relations have a target), refuses writes to `pending` or `retired` components, and rejects any value where `jsonb_matches_schema(json_schema, value)` is false.
- **Traits.** Exclusive is a partial unique index on `(entity)`. pgecs replaces the old pair in the same tx before inserting a new one, as flecs does. Acyclic, Symmetric and OneOf are triggers. Cleanup `remove` is the FK cascade. `delete` (as for `child-of`) and `panic` are a `BEFORE DELETE` trigger on `ecs_entity`.
- **Entity version.** Every component write bumps `ecs_entity.version`, so two transactions touching one entity always conflict instead of interleaving. That makes busy entities (accounts, process entities) hot rows; see the contention guidance.

### Per-component indexes (generated by registry freeze)

```sql
-- Declared as unique { it.address.lowercase() }
create unique index concurrently ecs_u_email on ecs_component (lower(value->>'address'))
  where rel = 'b8:iam:email:v1';
-- Exclusive relationship: at most one parent
create unique index concurrently ecs_x_child_of on ecs_component (entity)
  where rel = 'pgecs:child-of';
-- Opt-in GIN for one component with rich nested values
create index concurrently ecs_g_player_settings on ecs_component using gin (value jsonb_path_ops)
  where rel = 'b8:mc:player-settings:v1';
-- Range scans for expiry (value is epoch millis via InstantAsEpochMillis)
create index concurrently ecs_i_expires_slow on ecs_component ((value::bigint), entity)
  where rel = 'b8:util:expires-slow:v1';
```

### Registry and freeze

There are no hand-written migrations. Components are Kotlin objects that declare their own constraints, and `freeze()` reconciles the database with them once, at startup, before any `tx` is allowed.

```kotlin
object EmailComponent : Component<Email>("b8:iam:email:v1") {
    init {
        unique { it.address.lowercase() }
        validate { it.address.contains('@') }                    // must translate to SQL
        validate(kotlinOnly = true) { MxCheck.plausible(it.address) }
    }
}
object Account : Tag("b8:iam:account:v1")
object ExpiresSlowComponent : Component<Instant>("b8:util:expires-slow:v1", InstantAsEpochMillis) {
    init { index { it } }
}

val ecs = Pgecs.connect(dataSource)   // modules are found automatically
ecs.freeze()                          // registry read-only from here; tx and queries unlock
```

**One registration path.** `pgecs-ksp` generates a registry per Gradle module listing every `Component`, `Tag`, `Relation` and `Capability` object it contains, plus a `META-INF/services` entry. `Pgecs.connect` loads all of them through `ServiceLoader` and touches every object before freeze, so lazy Kotlin object initialisation can never skip a constraint. A handle that was not registered (for example, an object created reflectively) throws when it is used after freeze. The constraint verbs are `unique`, `index`, `index(gin)`, `validate` and the trait functions.

**Validation rules.** `validate { }` lambdas are decompiled and translated to SQL into the component's check, alongside its JSON Schema. If a rule cannot be translated, freeze fails, unless the rule is marked `kotlinOnly = true`. Kotlin-only rules run client-side before every write.

What `freeze()` does:

1. Builds the desired state from the registry: catalog rows, JSON Schemas, indexes, trait flags, and a fingerprint per component.
2. Takes the session-level `pg_advisory_lock(<pgecs schema lock>)` on one dedicated connection, held for the whole freeze, so two JVMs never run freeze at the same time.
3. Compares fingerprints per component. Components whose fingerprint matches are left alone, which is the normal case on restart.
4. **Transaction A:** inserts new components with `state = 'pending'`, applies additive schema changes, and records every index to build in `ecs_schema_index`. Trait flags are not changed yet.
5. **Outside any transaction:** runs `CREATE [UNIQUE] INDEX CONCURRENTLY IF NOT EXISTS` for each recorded index. Writes to existing components continue meanwhile.
6. **Verification:** checks `pg_index.indisvalid` for every index. An invalid one (from duplicate data or a crash mid-build) is dropped with `DROP INDEX CONCURRENTLY`. Freeze then stops startup and names the index and the conflicting entity ids. Nothing from Transaction A is undone: new components simply stay `pending`, which blocks writes to them.
7. **Transaction B:** activates trait flags, marks components `active`, retires components marked with `retire("name")`, writes the fingerprints and an `ecs_schema_history` row, then releases the lock. A trait such as `exclusive` therefore only becomes visible to the planner once its index is valid.

Safety rules for the diff:

- **Additive changes apply in place.** A new optional field with a default, a new index or a new trait. Freeze proves a schema change is additive by comparing the old and new JSON Schemas: the same fields with the same types, plus only optional new ones. Anything else for an existing name stops startup and needs a new version name.
- **Tightening is checked against data.** Adding `unique` or `exclusive` builds the index concurrently. If duplicates exist, the build fails and startup stops as in step 6.
- **Nothing is dropped implicitly.** A component missing from the registry stays in the catalog and in the data, marked unclaimed. Dropping it needs an explicit `retire("name")`.
- **Rolling deploys.** An older JVM starts fine if every component it knows has the same fingerprint in the database, or one that is an additive extension of its own. It never narrows the stored schema. It ignores components it does not know, and the database still enforces their constraints.

Indexes are always built with `CREATE INDEX CONCURRENTLY`, so freeze never takes a write-blocking lock on `ecs_component`. The cost is that freeze is not one atomic transaction. The `pending` state and the steps above make every interruption safe, and a rerun picks up where it stopped.

### kotlinx.serialization integration

The `KSerializer` of a component type is the single source of truth for its stored shape, its JSON Schema and its query field paths, so Kotlin types, database validation and the query compiler cannot disagree.

- **Registration takes the serializer.** `Component<Email>("b8:iam:email:v1")` captures `serializer<Email>()`, or takes an explicit `KSerializer<T>` for Java callers and custom formats. Scalar roots (`Long`, `String`, `Instant` with `InstantAsEpochMillis`, value classes) are allowed.
- **One shared `Json` instance.** pgecs owns the configuration, so every JVM writes the same shapes: `encodeDefaults = true`, `explicitNulls = true`, `ignoreUnknownKeys = true` (so older JVMs can read rows that newer JVMs extended), `classDiscriminator = "@type"`. Any other decode failure throws.
- **Unknown keys survive writes.** An older JVM must not drop fields it does not know. Every write of an object value is a merge: pgecs encodes the known fields and stores `stored_value || new_known_fields`, so keys written by a newer JVM are kept. Removing a field is not an additive change, so it never needs this merge to delete anything.
- **JSON Schema from the `SerialDescriptor`.** Freeze walks the descriptor. Primitive kinds map to `string`, `integer`, `number`, `boolean`. Classes become `object`, with `required` from non-optional elements and `additionalProperties: true` so additive extensions validate. Lists, maps, enums and nullability map directly, and sealed hierarchies become `oneOf` keyed on the discriminator.
- **Constraints as `@SerialInfo` annotations.** pgecs ships `@Pattern`, `@MinLength`, `@MaxLength`, `@Min`, `@Max` and `@Format("email")`. The descriptor exposes them through `getElementAnnotations`, so they become JSON Schema keywords enforced in Postgres.
- **Field paths for queries.** When the decompiler sees a getter, it resolves the property to its descriptor element, so `it.address` becomes `value->>'address'` and respects `@SerialName`. The element's kind decides the SQL cast, so comparisons are typed correctly.
- **Standard serializers for awkward types.** `InstantAsEpochMillis`, `UuidAsString` and `DurationAsMillis`. The compiler recognises them by `serialName` and knows how to compare and index them.
- **Java consumers** use the same path with an explicit serializer, or a Jackson-backed adapter that supplies a hand-written JSON Schema. The database contract is the same either way.

### Change log

`ecs_change`, written by trigger in the same transaction, records `(xid8, entity, rel, target, op, old_value)` for observers. Each observer stores its cursor (the last `xid8` it processed) on its system's row and advances it in the same `tx` as its effects. Consumers read only up to the current snapshot's `xmin`, so they never skip a row from a transaction that commits late. A pgecs system prunes rows that every registered observer has passed and that are older than the retention window (default 7 days). Outbox rows are excluded from the change log, since the outbox has its own delivery.

## Transactions and integrity

Every transaction is `SERIALIZABLE` with automatic retry, and the database refuses anything else. That is the rule that makes "Postgres is the only sync point" true without hand-written locking.

- **Isolation.** `SERIALIZABLE` is the only isolation level. `ecs.tx { }` opens a `SERIALIZABLE` transaction. On SQLSTATE `40001` (serialization failure) or `40P01` (deadlock), it rolls back and reruns the block with jittered backoff, up to a limit (default 5). `ecs.read { }` runs `SERIALIZABLE READ ONLY` with the same retry. Long reports can ask for `DEFERRABLE`, which waits for a safe snapshot and then never fails.
- **Blocks must be pure.** A block can run more than once, so it may only touch the database. Sending an email, kicking a player or calling an API goes through the outbox.
- **Transactional outbox.** Side effects are outbox entities written in the same transaction, routed to a process endpoint or a capability (see below). Delivery is at least once, so effects need an idempotency key.
- **Invariants live in Postgres.** Uniqueness, exclusivity, acyclicity, referential integrity, cleanup, value schemas, reserved entities and the isolation level are constraints or triggers. Kotlin hooks are for convenience (defaults, derived components), never the only guard.
- **Hooks run in-transaction.** `onAdd`, `onSet`, `onRemove` hooks run inside the same `tx` before commit, mirroring flecs hooks. If one throws, the whole transaction rolls back. They do not fire for rows removed by a database cascade; side effects of removal belong in observers.
- **Observers run post-commit.** They consume `ecs_change`, so they only see committed data and survive restarts. They are at least once, ordered per entity.
- **Validate at decision time.** Any decision based on data read outside the transaction (in-memory state, virtual sources, a previous request) must be re-checked inside `tx` before writing. Example: an OTP is accepted only if the `tx` itself sees it unexpired and unused.
- **Ids.** Entity ids are UUIDv7, created client-side so a `tx` can build a graph of new entities before its first statement. Component, relation and capability entity ids are UUIDv5 of the name, so every process agrees without a lookup.

### Concurrency

pgecs is fully concurrent at both levels: many JVMs against one database, and many threads or coroutines inside one JVM. Postgres is the only arbiter, and no in-process locks guard data.

**Across processes**

- All processes (Velocity proxies, Fabric lobbies, the main server, the creative host, the Ktor backends) read and write at once. Correctness comes from `SERIALIZABLE` plus database constraints, never from processes coordinating with each other.
- Conflicting transactions are detected by Postgres and retried by the runner. Retries are visible in metrics per component and per call site.
- Freeze, systems and outbox workers coordinate only through advisory locks, leases and `SKIP LOCKED`.

**Inside one JVM**

- **Thread-safe core.** After `freeze()`, the `Ecs` object, registry, query caches and serializer tables are immutable or concurrent maps.
- **One connection per transaction.** Each `tx` or `read` borrows its own pooled connection (HikariCP). The blocking form runs on the caller's thread, and is meant for virtual threads and backend code. The `suspend` form runs on a dispatcher limited to the pool size.
- **Game threads never block.** A Fabric mod on the server thread uses `ecs.txAsync { }` / `ecs.readAsync { }`. They return a `CompletableFuture` that runs the block on a pgecs executor, and complete it there. The mod applies results back on the server thread with `server.execute { }`. The blocking `tx` / `read` throw `IllegalStateException` when called on a thread registered as a game thread (pgecs-fabric registers every `MinecraftServer` thread at startup).
- **Confined transaction handles.** A tx scope belongs to the block that opened it. Using it after commit, or from a coroutine that escaped the block, throws. Nested `tx { }` calls on the same coroutine or thread join the outer transaction, and `savepoint { }` gives a partial rollback.
- **Deterministic lock order.** Writes inside a `tx` are buffered and flushed sorted by entity id, before any query in that tx and at commit. That avoids most deadlocks and cuts round trips.
- **Parallel federated queries.** Independent fragments for different sources run concurrently with Kotlin coroutines (`coroutineScope` + `async`). If one fails or the caller is cancelled, the others are cancelled too. Java callers get the same behaviour through a plain executor behind the `CompletableFuture` API, so no preview APIs are needed.
- **Cancellation.** Cancelling during a `tx` cancels the running statement and rolls back. A transaction never half-commits.
- **Contention guidance.** Every write bumps the entity's `version`, so writes to different components of one entity still conflict. Keep hot data off busy entities: put per-session or per-login data on child entities rather than on the account, and never put frequently changing data on process entities. Avoid single hot rows like a global counter; use append-only entities or per-shard rows summed in queries.

**Connection budget.** Each process holds a pool, plus one lock connection (which also carries `LISTEN`) and, briefly at startup, one freeze connection. Size them against `max_connections`. An example layout:

| Process | Count | Pool | Extra | Total |
| --- | --- | --- | --- | --- |
| Velocity proxy | 2 | 4 | 1 | 10 |
| Fabric lobby | 2 | 4 | 1 | 10 |
| Main server | 1 | 8 | 1 | 9 |
| Creative host | 1 | 8 | 1 | 9 |
| Ktor backend | 2 | 10 | 1 | 22 |
| Freeze (transient) |  |  |  | up to 8 |

That totals about 68, well under the default `max_connections` of 100. pgecs needs session-level features (advisory locks, `LISTEN`, server-side prepared statements), so it must connect directly or through a PgBouncer in session mode, never in transaction-pooling mode.

### Processes, endpoints and outbox routing

Every JVM running pgecs is a process entity. Every place that can execute effects is an endpoint entity under it, and each outbox entry is routed either to one specific endpoint or to any endpoint with the required capability.

**Process and endpoint entities**

- **Process:** one per JVM, with a stable UUID from its config. It carries `pgecs:process:v1 = {name, kind, host, version, startedAt, lockKey}`.
- **Endpoint:** a child entity (`pgecs:child-of` the process) per execution target, with `pgecs:endpoint:v1 = {name}` and its stable UUID from config. The creative host has one endpoint per `MinecraftServer`. The Ktor backend has a single endpoint. Capabilities are pairs on the endpoint, `(pgecs:can, <capability>)`, such as `b8:iam:smtp` or `b8:mc:kick`. Capabilities are registered like components, with a payload type.
- **Liveness and uniqueness.** On start the process takes `pg_advisory_lock(lockKey)` on its lock connection and holds it while it runs. `lockKey` is a 64-bit hash of the process UUID. It is stored in `pgecs:process:v1` under a unique index, so a hash collision between two different processes fails at registration with a clear error instead of one process mysteriously refusing to start. A second process with the same UUID cannot take the lock and refuses to start. Other processes see liveness through `pg_locks`, with no heartbeat writes.
- **Capabilities on start.** Each endpoint's capability pairs are rewritten from its registered handlers. Outbox entries already targeted at an endpoint that no longer has the capability are moved to dead-letter in the same transaction, so they never wait forever.
- **Player presence.** `(b8:mc:online-on, <endpoint>)` on the player's `b8:iam:mc-uuid:v1` entity is an Exclusive pair kept up to date by the server endpoints on join and leave, with Velocity clearing it on disconnect. Any process can route "wherever player X is" from it in plain Postgres. If a server crashes, its presence pairs are stale: routing ignores endpoints whose process is not live (no advisory lock in pg\_locks), and an endpoint clears all of its presence pairs when it starts.

**Outbox entries**

- An entry is an entity with `pgecs:outbox:v1 = {capability, payload, idempotencyKey, attempts, notBefore, claimedBy, leaseUntil}`. It has at most one Exclusive pair `(pgecs:outbox-target, <endpoint>)`.
- **With a target:** only that endpoint may run it. A trigger rejects the insert if the target lacks `(pgecs:can, capability)`, so a misrouted entry can never be committed.
- **Without a target:** any live endpoint with the capability may run it. Two redundant Ktor backends with `b8:iam:smtp` share the work.
- The payload is validated against the capability's JSON Schema by `pg_jsonschema`.

Example: one backend `tx` that writes three entries.

```kotlin
ecs.tx {
    outbox(Kick(player), to = serverA)                  // only mc server A
    outbox(Message(player2, text), to = onlineOn(player2)) // wherever player2 is, from presence
    outbox(SendMail(address, otp))                      // any endpoint with b8:iam:smtp, including this one
}
```

**Claiming and delivery**

- A claim is one statement, so its serializable transaction is as short as possible:

```sql
update ecs_component o
   set value = value || jsonb_build_object('claimedBy', $me, 'leaseUntil', $until)
 where (o.entity, o.rel, o.target) in (
   select c.entity, c.rel, c.target
     from ecs_component c
    where c.rel = 'pgecs:outbox:v1' and …  -- targeted at me, or untargeted with my capability
    order by (c.value->>'notBefore')::bigint
    limit 50
      for update of c skip locked)
 returning o.entity, o.value;
```

- `FOR UPDATE OF c` locks only the outbox rows, not joined capability or target rows. The effect then runs outside any transaction, and a second `tx` deletes the entry. If the process crashes, the lease expires and the entry becomes claimable again.
- Concurrent claimers reading the same index range can still hit `40001`. Those are retried immediately, and claim retries per capability are a metric.
- **Wake-up:** inserting an entry sends `NOTIFY` on the target endpoint's channel, or on the capability's channel. Postgres only delivers notifications after commit. Workers also poll every few seconds as a fallback.
- Delivery is at least once. Handlers must be idempotent, using `idempotencyKey`. After the configured number of attempts, the entry gets `pgecs:outbox-dead` and stays for inspection.
- An entry targeted at an endpoint that is down waits until it returns. An optional deadline turns it dead instead.
- If a player moved between enqueue and execution, the handler re-reads presence and re-enqueues to the new endpoint in the same `tx` that completes the old entry.

## Query language and SQL compiler

Queries are built with a Kotlin DSL or a Java-compatible DSL. Predicates, projections and index expressions are lambdas decompiled from bytecode at runtime. Both DSLs produce the same IR, which adds value predicates, projection, ordering, aggregation and paging to flecs-style terms. The Postgres backend turns it into a single SQL statement.

### Kotlin DSL

The Kotlin DSL reads like the flecs term list. Its infix functions and operators only exist inside `query { }`, so they never leak into ordinary code.

| Flecs | Kotlin DSL | Notes |
| --- | --- | --- |
| `Account($this)` | `acc has Account` | infix `has`, flecs wording |
| `!LobbyDenied($this)` | `acc has !LobbyDenied` | unary `!` on a component |
| `?Mass($this)` | `acc maybe MassComponent` | optional; the value is null when absent |
| `ChildOf($mail, $acc)` | `mail childOf acc` or `ChildOf(mail, acc)` | KSP-generated infix, or `operator invoke` on the relation |
| `(ChildOf, *)` | `ChildOf(mail, any)` | `any` is the wildcard variable |
| `A \|\| B` | `acc has (A or B)` | infix `or` on components |
| value predicate | `mail has EmailComponent.where { it.address == addr }` | decompiled; `addr` becomes a bind parameter |

Variables are declared with `val acc by entity`, so the name shows up in plans and errors. A variable is a typed `Var<EntityId>`. Projection is a decompiled lambda whose return type types the query: `select { acc }` gives `Query<EntityId>`, and `select { acc to mail[EmailComponent].address }` gives `Query<Pair<EntityId, String>>`. Inside it, `mail[EmailComponent]` is a marker that the compiler turns into a JSON path; it never executes. In system batches, rows are read by variable: `it[e]`.

Predicates mean exactly what they say. `it.address == addr` is an exact match. A case-insensitive lookup is written as one, `it.address.lowercase() == addr.lowercase()`, and only then does the compiler use the matching `lower(value->>'address')` index.

The Java DSL is the same builder without Kotlin-only features (reified generics, extension functions, operator overloads). It passes component handles explicitly and takes the same `Serializable` functional interfaces.

```kotlin
val q = ecs.query {
    val acc by entity
    val mail by entity
    acc has Account
    acc has SmpMainAccess
    acc has !LobbyDenied
    mail childOf acc
    mail has EmailComponent.where { it.address.lowercase() == addr.lowercase() }
    select { acc to mail[EmailComponent].address }
}
```

```java
var q = ecs.query(b -> {
    var acc = b.entity("acc");
    var mail = b.entity("mail");
    b.has(acc, Iam.ACCOUNT);
    b.has(acc, Iam.SMPMAIN_ACCESS);
    b.hasNot(acc, Iam.LOBBY_DENIED);
    b.pair(Pgecs.CHILD_OF, mail, acc);
    b.has(mail, Iam.EMAIL, e -> e.address().toLowerCase().equals(addr.toLowerCase()));
    b.select(acc, b.value(mail, Iam.EMAIL));
});
```

Queries take their values from captured variables, bound as parameters, and are executed with a result method that says what the caller expects: `fetch()` returns all rows as a `List`, `fetchOne()` exactly one row (throws otherwise), `first()` one row or `null`, and `count()` compiles to `select count(*)`. A query is usually built inline where it is used. Rebuilding it each call is cheap and hits the plan cache.

```kotlin
fun accountByEmail(addr: String): EntityId? = ecs.read {
    ecs.query {
        val acc by entity; val m by entity
        m childOf acc
        m has EmailComponent.where { it.address == addr }   // addr captured, bound as $1
        select { acc }
    }.first()
}
```

### KSP code generation

`pgecs-ksp` generates, per Gradle module:

- The module registry and its `META-INF/services` entry (see Registry and freeze).
- **Infix relation functions:** for every `object X : Relation(...)`, a top-level extension `infix fun QueryScope.Var<EntityId>.x(target: Var<EntityId>)`, first letter lower-cased (`ChildOf` becomes `childOf`). KSP cannot add members to a class, so these are top-level extensions. They are harmless outside queries because `QueryScope.Var` only exists inside `query { }`.
- **Write accessors** for `create { }` and `update(e) { }`:

| Declared | Generated | Usage |
| --- | --- | --- |
| `object EmailComponent : Component<Email>` | set-only property `email` | `email = Email("aea")`, `email = null` removes it |
| `object Account : Tag` | `unaryPlus` / `unaryMinus` | `+Account`, `-Account` |
| Exclusive `object ChildOf : Relation` | set-only property `childOf` | `childOf = acc` replaces the old parent |
| non-exclusive `Relation` | `unaryPlus` / `unaryMinus` on the pair | `+OwnedBy(acc)`, `-OwnedBy(acc)` |

Value component objects end in `Component`, and the generated name drops that suffix (`EmailComponent` becomes `email`). `@PgecsName("...")` overrides it, and two generated names colliding in one package is a build error. The properties are set-only: their getter is marked `@Deprecated(level = ERROR)`, so reading one is a compile error. Reads are explicit and can suspend: `get(EmailComponent)` returns `Email?`. Without the processor, `this[EmailComponent] = Email("aea")`, `add(Account)` and `get(...)` always work, and the Java DSL uses `set`, `add`, `remove` and `get`.

### How lambdas are decompiled

Every lambda pgecs needs to translate, in `where { }`, `select { }`, `orderBy { }`, `unique { }`, `index { }` and `validate { }`, is typed as a Java `Serializable` functional interface declared by pgecs. Never a Kotlin function type: Kotlin 2.x compiles those to hidden classes with no readable bytecode. The `query { }` builder itself is an ordinary lambda. It is simply run, and it records terms.

- **Reading the bytecode.** For an invokedynamic lambda, the engine calls `writeReplace` to get its `SerializedLambda`, which names the implementing method and holds the captured values. For a lambda compiled to its own class, the engine reads that class's `invoke` method directly. Either way, the method is read with the `java.lang.classfile` API (final since Java 24), its stack code is symbolically executed into an expression tree, and the tree is lowered into the IR.
- **What it recognises.** Getters on component types (Java record accessors, Kotlin property getters) map to JSON fields. It also handles comparisons, `&&`, `||`, `!` (rebuilt from branches), null checks, a whitelist of `String` and `Instant` methods, the pgecs helpers (`has`, `near`, `now`), and Kotlin intrinsics such as `Intrinsics.areEqual`. Captured values become bind parameters.
- **No silent fallback.** A call no source can push down is an error when the query is first built, never a full scan filtered in memory. Local filtering is explicit, through `.filterLocal { }`.
- **Stability.** Translation relies on JVM bytecode, not Kotlin compiler internals. A golden test suite decompiles a fixed set of lambdas under each supported Kotlin and Java compiler version, including what Kotlin emits for SAM conversion to these interfaces, so a code-shape change shows up in CI.

### Pipeline

1. **DSL calls** record an AST. Predicate lambdas are decompiled and merged in. Everything is checked against the registry: an unknown component, a wrong field, or a predicate on a tag is an error when the query is first built.
2. **IR**: a term graph. Nodes are variables (entity or value), edges are terms, and each term knows its source.
3. **Logical plan**: relational operators, `Scan(rel, target)`, `Join`, `SemiJoin`, `AntiJoin`, `LeftJoin`, `Filter`, `Project`, `Aggregate`, `Sort`, `Limit`.
4. **Optimisation** with equality saturation (below).
5. **Physical plan** per source. For Postgres that is SQL.

### Compiling to SQL

| IR term | SQL |
| --- | --- |
| `Comp($x)` | join `ecs_component c` on `c.entity = $x`, `c.rel = 'Comp'`, `c.target = nil` |
| `Rel($x, $y)` | join on `entity = $x`, `target = $y` |
| `Rel($x, *)` | `exists` or join, `target` unbound |
| `!Comp($x)` | `not exists (...)` |
| `?Comp($x)` | `left join` |
| `A \|\| B` | `exists (...) or exists (...)` |
| `where c.f == $p` | `c.value->>'f' = $1`, cast by the field's kind |

The Kotlin example above becomes:

```sql
select a.entity as acc, e.value->>'address' as address
from ecs_component a
join ecs_component s  on s.entity = a.entity and s.rel = 'b8:iam:smpmain-access-allowed'
                     and s.target = '00000000-0000-0000-0000-000000000000'
join ecs_component co on co.target = a.entity and co.rel = 'pgecs:child-of'
join ecs_component e  on e.entity = co.entity and e.rel = 'b8:iam:email:v1'
                     and e.target = '00000000-0000-0000-0000-000000000000'
where a.rel = 'b8:iam:account:v1'
  and a.target = '00000000-0000-0000-0000-000000000000'
  and lower(e.value->>'address') = lower($1)
  and not exists (select 1 from ecs_component d
                  where d.entity = a.entity and d.rel = 'b8:iam:lobby-access-denied');
```

Two compiler rules matter for performance:

- **Component names are inlined as literals**, values are bind parameters. Postgres only uses a partial index (`where rel = 'b8:iam:email:v1'`) when the literal is visible at plan time. Names are safe to inline because they come from the catalog and match a strict pattern.
- **Index expressions are matched, never invented.** The compiler emits the SQL that the predicate literally means. When that SQL equals a declared index expression, Postgres uses the index. The compiler never rewrites a predicate into an index form with different semantics.

A small `ecs_stats` view of row counts per `rel` feeds cost estimates, because the single-table layout hides per-component cardinality from Postgres' own statistics.

### Optimizer and equality saturation

The optimizer runs equality saturation with egglog inside the JVM through [Endive](https://endive.run), so each source can add its own rewrite rules and costs without the planner hard-coding them. Every query shape goes through it, in every process.

- **Why e-graphs.** Virtual and in-memory sources have very different capabilities, such as spatial indexes, per-tick snapshots and archetype scans. Equality saturation applies all rules at once and extracts the cheapest plan, so adding a source only means adding its rules and cost function.
- **Runtime.** [egglog](https://github.com/egraphs-good/egglog) (Rust, MIT) compiled to `wasm32-wasip1`, run by Endive (a JVM-native Wasm runtime from the Bytecode Alliance, Apache-2.0) with its build-time (AOT) compiler.
- **Interface.** The logical plan is serialized as egglog terms. Rules are written in egglog: a core set from pgecs, and one set per source (for example, Minecraft `near` pushdown or bind-join preferences). Per-source cost estimates drive extraction, and the extracted term is parsed back into the plan.
- **Off the game thread.** Optimisation always runs on a pgecs compile executor, never on a game thread. A new shape reached through `txAsync` / `readAsync` compiles in the background and its future completes afterwards. A blocking call on a non-game thread waits for the compile.
- **Bounded.** Saturation runs with node, iteration and time limits, and the best plan found so far is used if a limit hits. If egglog cannot run at all, a fixed-point pass over the core rules produces a correct, possibly slower plan.
- **Spike results.** egglog 3.0.0 builds unmodified for `wasm32-wasip1` and runs under Endive 1.2.0 on Java 25 through a reactor ABI (`alloc`, `run`, `out_ptr`, `out_len`). Instances are not thread-safe, so pgecs keeps a small pool. Chosen setup: Endive AOT, egglog built with Rust `opt-level = "s"` (smaller functions JIT far better; `wasm-opt -O3` is 4x worse), a contiguous `byte[]` linear memory (a \~150-line `FlatMemory` instead of Endive's paged memory), `-XX:-DontCompileHugeMethods`, and a persistent e-graph per pooled instance (rules loaded once, push/pop per query). One optimisation of a 4-way join then takes about 22 ms in steady state, about 5x native egglog (4.3 ms) and 3x wasmtime. Warm-up is long: the first query takes \~0.6 s, runs 50 to 100 average \~55 ms, and steady state comes after \~400 runs. JIT code is shared across instances, so a warm-up on one instance at startup warms them all. The JDK 25 AOT cache only helps the first few dozen runs. Bounded rule schedules instead of full saturation are the next lever, more than further runtime tuning. That flag applies to the whole JVM, including Minecraft's code. Spike write-up and follow-up: #16, #18.

### Query compilation cache

Callers can build a query anywhere, even inline in a hot path. The builder always runs, which only records terms and is cheap, and compilation happens once per query shape per process.

| Layer | Key | Value |
| --- | --- | --- |
| 1. Lambda translation | Implementing method (class, name, descriptor) | Decompiled expression tree |
| 2. Plan | Hash of the recorded IR, constants lifted to params | Optimised logical plan (eqsat result) + per-source physical plans |
| 3. SQL | Plan fragment | SQL text + bind layout |

- **Shape keys.** Because the key is the IR the builder actually recorded, a builder with `if (strict) acc has !LobbyDenied` produces two shapes and two plans. Captured values and constants never enter the key; they become bind parameters. Component names are part of the shape, because they are inlined as literals.
- **Cache implementation.** [Caffeine](https://github.com/ben-manes/caffeine): Window TinyLFU eviction tracks usage frequency, `expireAfterAccess` gives the TTL, a maximum weight is sized by plan size, and stats are built in. Loads are single-flight: if 50 threads hit a new shape at once, one compiles and the rest wait.
- **Prepared statements.** pgecs caches SQL text. Server-side prepared statements are per connection, so pgjdbc's own statement cache handles them (`prepareThreshold`, `preparedStatementCacheQueries`).
- **Invalidation.** The registry is frozen, so plans only go stale when a source is added or changes capabilities (a global epoch bump clears layers 2 and 3), or when per-component row counts drift past a factor (re-plan that shape on its next use).
- **Pre-compiling.** Hot shapes seen on the previous boot are recorded, and freeze can compile them in the background at startup, so the eqsat cost is paid before the first player needs the query.
- **Metrics.** Hit rate per layer, compile and eqsat time (p50, p99), cache size and evictions.

## Federated planner

Every component type is owned by exactly one source. The planner splits a query into per-source fragments, pushes each fragment down whole, and joins fragments in the engine, usually by passing ids from the small side into the large side. Postgres is reachable from every process. The in-memory ECS and the Minecraft source are in-process only: a query can only see the in-memory state and the Minecraft servers of the JVM it runs in.

```
             Query (Kotlin or Java DSL)
                        |
                   IR term graph
                        |
     planner: split by component owner, eqsat, extract
         /                    |                      \
  Postgres source       In-memory ECS           Minecraft source
  (SQL, ACID,           (archetype tables,      (this JVM's servers:
   any process)          ephemeral, in-process)  players, positions)
         \                    |                      /
              engine joins: bind join / hash join
```

### Sources

A source implements one interface: which components it owns, which operators and predicates it can run (Postgres: everything; Minecraft: has-component, equality, a spatial `near`), its egglog rules and cost estimates, and `execute(fragment, bindings)` returning rows. The catalog records the owner of each component, so `b8:mc:position` belongs to the Minecraft source and `b8:iam:email:v1` to Postgres.

- **Postgres** runs any fragment as one SQL statement, inside the caller's transaction when there is one.
- **In-memory archetype ECS** (later) holds ephemeral state only. The components it owns are never persisted and never mirrored from Postgres. There is no automatic promotion: code that wants to persist something writes it through a normal `tx`.
- **Minecraft source** wraps the live `MinecraftServer` instances of this JVM. It exposes players and entities with their Minecraft UUID as entity id, and components like `b8:mc:player`, `b8:mc:position`, `b8:mc:world`. Reads must happen on the owning server thread. If the query already runs there, the source reads inline; otherwise it submits the read with `server.submit { }` and awaits a copied snapshot. It never blocks the server thread waiting on itself.
- **Other processes** see Minecraft state only through what is persisted in Postgres, such as player presence (`b8:mc:online-on`). A future RPC source could forward a fragment to another process's sources; it is out of scope for v1.

### Identity across sources

All sources share the UUID space. Cross-source joins go through value variables, not shared rows: the Minecraft player `p` matches the Postgres entity whose `b8:iam:mc-uuid:v1.uuid == p.id`.

### Example: accounts of players near a block

Run on the server that hosts the block:

```kotlin
val q = ecs.query {
    val p by entity            // Minecraft player, id = its Minecraft UUID
    val link by entity
    val account by entity
    p has McPositionComponent.where { it.world == "smpmain" && near(it, x, y, z, 16.0) }
    link has McUuidComponent.where { it.uuid == p.id }
    link childOf account
    account has Account
    select { account to p }
}
```

The planner sees a Minecraft fragment (`p`, small, spatial) and a Postgres fragment (the rest). It runs Minecraft first, then binds the player UUIDs into Postgres as `value->>'uuid' = any($1::text[])`, served by the unique index on that field. That is one round trip each.

### Consistency rules

- A federated result is a mixed snapshot: Postgres at one MVCC snapshot, Minecraft at one tick, the in-memory ECS at the moment it was read. It is never atomic across sources.
- Inside `ecs.tx { }`, queries may only touch Postgres by default. Using another source there needs an explicit opt-in, and the transaction must re-check in SQL any fact it writes based on.
- Only Postgres is writable through `tx`. Writes to Minecraft (kick, teleport, message) are outbox effects.

## Systems and scheduling

A system is an ECS query, an action and a schedule. A lease on the system's own row stops two processes from starting the same run, and its batches use `SKIP LOCKED`, so several processes can work through one run in parallel without taking the same rows.

### Kinds of systems

| Kind | Trigger | Runs where | Example |
| --- | --- | --- | --- |
| Periodic | Interval (`every(5.minutes)`) | Any process that registers it, batched in pg | Expiry sweep, invite cleanup |
| Observer | `ecs_change` rows for a component | Any process that registers it, at least once | Send a verification email when `b8:iam:email:v1` is added |
| Tick (later) | Game tick | In-memory ECS, local | Proximity effects |

### Scheduling

Each system is an entity with `pgecs:system:v1 = {name, nextRun, leaseOwner, leaseUntil, cursor}`. Starting a run is one statement that claims the lease: `update … set leaseOwner = $me, leaseUntil = $until where name = $name and nextRun <= now() and (leaseUntil is null or leaseUntil < now())`. Only the process that wins it starts the run, and it renews the lease while working. Other processes that registered the same system see the run is active and join it as helpers, taking batches with `SKIP LOCKED`. When a batch query comes back empty, the leader sets `nextRun` and clears the lease. If the leader dies, the lease expires and the next process restarts the run, which is safe because every batch is its own transaction.

`batch(n)` is a general system feature. Each batch is one `SERIALIZABLE` transaction: the compiled query runs with `limit n` and `FOR UPDATE OF <the matched component> SKIP LOCKED`, the action runs on those rows, and the batch commits. Batches repeat until the query returns nothing. Short transactions keep conflicts and retries small, and a crash loses at most one batch. Claim retries per system are a metric.

### Expiry (b8 module)

Expiry always deletes the entity that expires. Anything that should expire on its own is modelled as its own entity, so there is no per-component expiry action.

- `b8:util:expires-slow:v1` holds an `Instant`, stored with `InstantAsEpochMillis`, and is swept every 5 minutes. A `b8:util:expires-fast:v1` could be swept every few seconds.
- An OTP or invite is an entity with an expiry component, deleted when it expires.
- A temporary ban is not a tag on the account. It is a `b8:mod:ban:v1` entity with an expiry component and `(pgecs:child-of, <account>)`. Whether an account is banned is a query (`has a ban child`), and the ban entity can carry its reason, issuer and dates.

```kotlin
ecs.system("b8:util:expire-slow") {
    every(5.minutes)
    query {
        val e by entity
        e has ExpiresSlowComponent.where { it < now() }
        orderBy { e[ExpiresSlowComponent] }
    }
    batch(500) { rows -> rows.forEach { delete(it[e]) } }
}
```

`now()` is a parameter bound at each execution, never a constant baked into a cached plan. Deleting runs the normal cleanup policies, so the entity's children go too.

**Expiry is not a filter.** There is no default query filter. Queries that must ignore expired entities add an explicit term, `e.notExpired()`, which the b8 module defines as "has no expiry component whose instant is before `now()`". Security checks such as OTP verification check expiry inside their own `tx`, so an expired but unswept OTP is never accepted. The sweep is cleanup only.

### What happens when something goes away

Removal has three mechanisms, each for one job:

| Mechanism | Runs | Sees DB cascades | Use for |
| --- | --- | --- | --- |
| Cleanup policies (`OnDelete`, `OnDeleteTarget`) | Triggers, same tx | Yes | Structure: what happens to pairs and children |
| `onRemove` hooks | Kotlin, same tx | No | In-tx invariants on pgecs-initiated removals |
| Observers on `ecs_change` | Post-commit, at least once | Yes | Side effects, such as telling a player their ban ended via the outbox |

The change trigger records every delete, including rows removed by cascades, so observers never miss one. `onRemove` hooks only fire for removals pgecs performs directly, not for rows a cascade removes in the database.

**How a removal observer works**, using "tell the player their ban ended" as the example:

1. **Capture.** The sweep deletes the ban entity. Its rows (`b8:mod:ban:v1` and `(pgecs:child-of, <account>)`) are removed, and the change trigger writes one `ecs_change` row per removed row, in the same transaction, including `old_value` and `target`. The deleted entity's last state stays readable even though the entity is gone.
2. **Pick up.** The observer is a system with a lease, like any other. It reads changes for its component with `xid8` after its cursor and before the current snapshot's `xmin`, so it only sees committed transactions and never skips one that commits late.
3. **React.** pgecs groups the changes per entity and transaction and hands the observer a `Removed` view of the entity as it was. The observer runs in its own `SERIALIZABLE` tx. It may only write to the database, typically outbox entries, and it advances its cursor in that same tx. Database effects therefore happen exactly once, and external effects at least once through the outbox.

```kotlin
ecs.observer("b8:mod:notify-ban-ended") {
    onRemove(BanComponent)
    batch(100) { removed ->
        removed.forEach { ban ->
            val account = ban.old[ChildOf] ?: return@forEach
            outbox(Message.toAccount(account, "Your ban has ended"))
        }
    }
}
```

## Kotlin API sketch

Components are Kotlin objects bound to their names. Transactions, queries and systems hang off one `Ecs` object.

```kotlin
// Definitions (module b8:iam), found by the KSP-generated registry
@Serializable data class Email(
    val address: String,
    @Serializable(InstantAsEpochMillis::class) val verifiedAt: Instant? = null,
)
object EmailComponent : Component<Email>("b8:iam:email:v1") {
    init { unique { it.address.lowercase() } }
}
object Account : Tag("b8:iam:account:v1")
object SmpMainAccess : Tag("b8:iam:smpmain-access-allowed")
object LobbyDenied : Tag("b8:iam:lobby-access-denied")

// Startup
val ecs = Pgecs.connect(dataSource) {
    process(id = config.processId, name = "ktor-backend-1")
    endpoint(id = config.endpointId) { handles(Smtp) { mail -> smtp.send(mail) } }
}
ecs.freeze()

// Writes: SERIALIZABLE, retried, no I/O inside
val accountId = ecs.tx {
    val acc = create { +Account; +SmpMainAccess }
    create { email = Email("aea"); childOf = acc }
    outbox(SendMail("aea", otp))
    acc
}
ecs.tx { update(accountId) { -SmpMainAccess; +LobbyDenied } }

// Reads
val q = ecs.query {
    val acc by entity
    val mail by entity
    acc has Account
    acc has !LobbyDenied
    mail childOf acc
    mail has EmailComponent.where { it.address.lowercase() == addr.lowercase() }
    select { acc }
}
val ids: List<EntityId> = ecs.read { q.fetch() }

// From a Fabric server thread: never block
ecs.readAsync { q.fetch() }.thenAccept { ids -> server.execute { show(ids) } }
```

### Modules

| Module | Contents |
| --- | --- |
| `pgecs-core` | Model, registry, IR, planner, query cache, systems, outbox API |
| `pgecs-postgres` | Base schema, triggers, freeze, SQL compiler, tx runner, outbox worker |
| `pgecs-lambda` | Bytecode decompiler for lambdas (Java classfile API) |
| `pgecs-ksp` | KSP processor: module registries, infix relations, write accessors |
| `pgecs-eqsat` | egglog compiled to `wasm32-wasip1`, run by Endive AOT, plus core rules |
| `pgecs-fabric` | Minecraft source, game-thread registration, endpoints per `MinecraftServer`, presence |
| `pgecs-memory` | Ephemeral in-memory archetype ECS (later) |

Endive's build-time compiler has an official Maven plugin. Under Gradle it needs the third-party `wasm2class` plugin or a small custom task, which matters because the monorepo build decides which.

## Phasing

1. **Core on Postgres:** base schema and triggers (isolation, reserved entities, kinds, schemas, traits), KSP registry and freeze with concurrent indexes, `tx` runner with retry, create, add, set, remove and delete, cleanup. The IAM account model is the first user.
2. **Queries:** Kotlin and Java DSLs, lambda decompiler with golden tests, IR, SQL compiler, query cache, async API for game threads.
3. **Processes and outbox:** process and endpoint entities, capabilities, routing, claims, `NOTIFY` wake-up, dead-lettering, player presence.
4. **Systems:** scheduler with leases, `batch(n)`, change log, observers and retention, the b8 expiry module.
5. **Optimizer:** `pgecs-eqsat` with egglog in Endive, core rules, warm-up.
6. **Federation:** source interface, Minecraft source in `pgecs-fabric` (players near a block).
7. **In-memory archetype ECS** for ephemeral state.
