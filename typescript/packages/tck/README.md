# @epicquest-studios/schema-reconciler-tck

Dialect conformance suite for the schema reconciler — scenarios
C-01…C-67 of SPEC.md 9.2 as a reusable `node:test` suite. Third-party
dialects prove conformance by running it against a live database.

```ts
// in a *.test.ts file picked up by `node --test`
import { dialectConformanceSuite } from "@epicquest-studios/schema-reconciler-tck";
import { MyDialect } from "./MyDialect.ts";

dialectConformanceSuite({
    dataSource: () => myTestDataSource,
    dialect: () => new MyDialect(),
    createNamespace: async () => {
        const name = uniqueName();
        await run(`CREATE SCHEMA ${name}`);
        return name;
    },
    dropNamespace: async (ns) => {
        await run(`DROP SCHEMA ${ns} CASCADE`);
    },
    // optional hooks declare optional capabilities: scratchDataSource,
    // unsupportedScript, extensionDdl, ownershipRoleDdl, …
});
```

`dataSource`/`dialect`/`createNamespace`/`dropNamespace` are required;
every other hook is optional — omitting it skips the scenario that
needs it (the only permitted deviation, SPEC.md 9.1). See
`ConformanceHooks` in `src/DialectConformanceTest.ts` for the full
contract and `@epicquest-studios/schema-reconciler-postgresql`'s own test suite for the
reference wiring.

Requires Node ≥ 22.18 (compiled ESM + `.d.ts`). Depends only on `@epicquest-studios/schema-reconciler-core`.
