# pgecs-ir

The first-level query IR of pgecs (`docs/pgecs.md`, *Pipeline* step 2): what the Kotlin and Java DSLs
and the lambda decompiler lower to, and what the planner, the egglog optimizer and the SQL compiler
consume. Package `net.blocks168.pgecs.ir`, no dependencies beyond the Kotlin standard library.

```
DSL calls + decompiled lambdas ──► QueryIr ──parameterize()──► BoundQuery(shape, arguments)
                                     │                               │
                              validate()                  shape = plan-cache key
                                                                     ▼
                                                     logical plan ► eqsat ► SQL / sources
```

## The model

A query is a term graph: **variables** are nodes, **terms** are edges, plus what to return.

| Type | What |
| --- | --- |
| `EntityVar`, `ValueVar` | Variables, numbered by declaration order. A value variable is the value of one component on one entity. |
| `Term.Match(subject, component, target?, value?)` | Flecs' term: `subject` has `component` (a pair with `target`), optionally binding its value. Joins happen by sharing variables. |
| `Term.Filter(expr)` | A boolean condition over visible variables. |
| `Term.Optional(terms)` | Flecs' `?`, generalised to a group: a left join. Variables first bound inside are null outside when it does not match. |
| `Expr` | Typed value expressions: `Var`, `Field` (JSON path into a value), `Param`, `Literal`, `Compare`, `And`/`Or`/`Not`, `IsNull`, `Coalesce`, `Arith`, `Negate`, `Call`, `Exists`, `Aggregate`, and `Arg` (an un-attached lambda argument). |
| `ComponentRef` | A registered type as the IR needs it: name, kind (tag, value, relation, capability), value type and owning source. |
| `EntityRef` | Subject or target of a match: a variable, `AnyEntity` (`*`), a `TypeEntity` (a capability's entity) or a UUID parameter. |
| `QueryIr` | Variables, parameter types, terms, `select` (named projections), `distinct`, `groupBy`, `orderBy`, `limit`, `offset`, `lock`. |

Negation, `or` and nested conditions are not separate term kinds, they are filters over `Exists`:

| DSL | IR |
| --- | --- |
| `acc has Account` | `Match(acc, Account)` |
| `mail childOf acc` | `Match(mail, ChildOf, target = acc)` |
| `ChildOf(mail, any)` | `Match(mail, ChildOf, target = AnyEntity)` |
| `(*, acc)` | `Match(x, AnyRelation(), target = acc)` |
| `mail has Email.where { p(it) }` | `Match(mail, Email, value = m)`, `Filter(p(m))` |
| `acc has !LobbyDenied` | `Filter(Not(Exists([Match(acc, LobbyDenied)])))` |
| `acc has (A or B)` | `Filter(Or(Exists([Match(acc, A)]), Exists([Match(acc, B)])))` |
| `acc maybe Mass` | `Optional([Match(acc, Mass, value = m)])` |
| `e.notExpired()` | `Filter(Not(Exists([Match(e, ExpiresSlow, value = x), Filter(x < now())])))` |
| `count()` | `select = [count(*)]` |
| system `batch(n)` | `limit = ?n`, `lock = RowLock(e, component, UPDATE, SKIP_LOCKED)` |

`IrBuilder` builds a query term by term and validates it; see the tests for every example of the
design doc.

## Semantics

* **Bag of matches.** One result row per match of the top-level terms, as flecs iterates: an account
  with two emails is two rows of `mail childOf acc`. `distinct` and `groupBy` change that.
  `AnyEntity` never adds rows (a semi-join).
* **Kotlin semantics for expressions.** `Compare(EQ)` is Kotlin `==`, which is null-safe; ordering
  comparisons on a value that may be null are rejected by validation, as Kotlin would not compile them.
  `And`/`Or` are two-valued. Sort keys default to Kotlin's null placement (first ascending). The
  SQL compiler preserves this, for example with `=` only where `Nullability.mayBeNull` is false on
  both sides.
* **Types decide casts.** Every `Field`, `Param` and variable has a `ValueType`: `BOOL`, `INT`
  (64-bit), `FLOAT`, `TEXT`, `UUID` (stored as a string), `INSTANT` (epoch millis), `DURATION`
  (millis) or `JSON`, plus declared nullability. The lowering decides it from the `SerialDescriptor`.
* **Scopes.** Variables bound by top-level matches are visible everywhere. Variables bound first
  inside an `Exists` are local to it. Variables bound only inside an `Optional` are visible outside
  but may be null (`Nullability.optionalVariables`).
* **Functions.** `Functions` lists the built-ins every source must understand (`pgecs:lowercase`,
  `uppercase`, `trim`, `length`, `startsWith`, `endsWith`, `contains`, `now`). Sources add their own
  under their namespace (`mc:near`). `now()` is one instant per execution, never a constant in a plan.

## Shapes and the plan cache

`parameterize()` lifts every `Literal` into a parameter and returns a `BoundQuery`: the **shape**
(a `QueryIr` without literals) and the argument values. Two shapes are equal exactly when they are the
same query up to argument values. The shape is the key of the plan cache (layer 2 of the design doc):

* `equals`/`hashCode` are structural, and the hash is computed once per instance.
* `canonicalText` is a versioned s-expression of exactly the shape, and `fingerprint` its SHA-256:
  stable across JVMs, for recording hot shapes across restarts.
* Part of the shape: component names, kinds, value types and sources (names are inlined in SQL, so
  partial indexes apply), variable names (they show in plans), types, term order, the builder's
  conditional terms (`if (strict) acc has !LobbyDenied` gives two shapes).
* Not part of it: argument values, including entity ids, limits and captured values.

Term order is kept as recorded rather than normalised: the builder of one call site always records
the same order, so normalising would only cost time on every lookup.

## Lambdas

The decompiler translates a lambda once per implementing method (cache layer 1) into an `Expr` with
`Arg(i)` for the lambda's parameters and `Param(j)` for its captured values, numbered per lambda. The
DSL attaches it to a term with `bindArgs` (the term's value variable for `it`) and `mapParams` (into
the query's parameter numbering), so a cached translation is reused by every query that uses it.

## Validation

`QueryIr.validate()` (and `IrBuilder.build()`) reports every problem at once, with the query's
canonical text: unbound or undeclared variables, a variable local to two scopes, a value bound from a
tag, a target on a plain component, a relation without target, a capability matched as a component,
mismatched value or parameter types, non-boolean filters, ordering comparisons of nullable values,
unknown fields on scalar values, built-in signature mismatches, unsubstituted lambda arguments,
unused parameters, aggregates outside `select`/`orderBy` or not matching `groupBy`, invalid paging,
and locks on rows that no top-level match produces. Whether a source can run a term (`mc:near` on a
Postgres component) is the planner's check, not the IR's.
