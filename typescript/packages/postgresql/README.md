# @schema-reconciler/postgresql

PostgreSQL 14+ dialect for the schema reconciler — TypeScript binding
of [SPEC.md](../../../SPEC.md) (Appendix D, PostgreSQL profile).

```ts
import { SchemaReconciler, DesiredState }
        from "@schema-reconciler/core";
import { PostgreSqlDialect }
        from "@schema-reconciler/postgresql";

const reconciler = SchemaReconciler.builder()
        .dataSource(dataSource)
        .namespace("svc")
        .desiredState(DesiredState.ofPath("db/schema.sql"))
        .dialect(new PostgreSqlDialect())
        .build();

await reconciler.reconcile();
```

The dialect is driver-agnostic: it works against the `SqlDataSource`
abstraction from `@schema-reconciler/core`. A `pg` adapter is a few
lines — connections must be dedicated sessions (one client each, no
pool sharing mid-run):

```ts
import pg from "pg";
import type { SqlConnection, SqlDataSource, DatabaseMetadata }
        from "@schema-reconciler/core";

class PgConnection implements SqlConnection {
    constructor(private readonly client: pg.Client) {}
    async metadata(): Promise<DatabaseMetadata> {
        const r = await this.client
                .query<{ server_version: string }>("SHOW server_version");
        return { databaseProductName: "PostgreSQL",
                 databaseMajorVersion:
                         Number(r.rows[0]!.server_version.split(".")[0]) };
    }
    async execute(sql: string): Promise<void> {
        await this.client.query(sql);
    }
    async query<R>(sql: string,
            params?: readonly unknown[]): Promise<R[]> {
        return (await this.client.query(sql, [...(params ?? [])]))
                .rows as R[];
    }
    getAutoCommit(): boolean { return this.autoCommit; }
    async setAutoCommit(on: boolean): Promise<void> {
        if (this.autoCommit === on) return;
        await this.client.query(on ? "COMMIT" : "BEGIN");
        this.autoCommit = on;
    }
    async commit(): Promise<void> {
        await this.client.query("COMMIT");
        this.autoCommit = true;
    }
    async rollback(): Promise<void> {
        await this.client.query("ROLLBACK");
        this.autoCommit = true;
    }
    async close(): Promise<void> { await this.client.end(); }
    private autoCommit = true;
}

class PgDataSource implements SqlDataSource {
    constructor(private readonly config: pg.ClientConfig) {}
    async connect(): Promise<SqlConnection> {
        const client = new pg.Client(this.config);
        await client.connect();
        return new PgConnection(client);
    }
}
```

The connecting role can be least-privilege:

```sql
GRANT USAGE, CREATE ON SCHEMA svc TO app_reconciler;
GRANT CREATE ON DATABASE appdb TO app_reconciler;
```

Requires Node ≥ 22.18. Depends only on `@schema-reconciler/core`;
`pg` is a peer concern of the application's adapter.
