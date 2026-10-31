# @epicquest-studios/schema-reconciler-core

Vendor-neutral, declarative, data-safe schema reconciliation — API, SPI
and engine. TypeScript binding of [SPEC.md](../../../SPEC.md)
(Appendix D).

Brings a database namespace to the state declared in a SQL script:
plans, applies and verifies changes, and never drops data by default.
The desired script executes inside the real database in a rolled-back
scratch namespace, so the library never parses vendor SQL itself.

```ts
import { SchemaReconciler, DesiredState }
        from "@epicquest-studios/schema-reconciler-core";

const reconciler = SchemaReconciler.builder()
        .dataSource(dataSource)          // SqlDataSource — see below
        .namespace("svc")
        .desiredState(DesiredState.ofPath("db/schema.sql"))
        .dialect(dialect)                // e.g. @epicquest-studios/schema-reconciler-postgresql
        .build();

const report = await reconciler.reconcile();
```

All engine operations are `Promise`-returning. `SqlDataSource` /
`SqlConnection` are the async analogs of `javax.sql.DataSource` /
`java.sql.Connection` — a dialect package typically documents a ready
adapter for its driver (see `@epicquest-studios/schema-reconciler-postgresql` for `pg`).

- `plan()` / `planTimed()` — inspect the deterministic change plan
- `reconcile()` — plan + apply + verify under the configured
  `ReconcilePolicy`
- `apply(plan)` — apply a previously computed plan (staleness-checked)
- `dump()` — render the actual namespace as a namespace-neutral script
- `registerDialect` / `unregisterDialect` — the `ServiceLoader` analog
  for automatic dialect selection

Dialect authors implement the `Dialect` SPI and validate against
`@epicquest-studios/schema-reconciler-tck` (scenarios C-01…C-67).

Requires Node ≥ 22.18 (packages ship compiled ESM + `.d.ts`; the
workspace itself runs sources under native type stripping).
Zero runtime dependencies.
