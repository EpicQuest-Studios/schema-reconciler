import pg from "pg";
import type { DatabaseMetadata, SqlConnection, SqlDataSource }
        from "@schema-reconciler/core";

/**
 * Supplies the disposable database for PostgreSQL dialect tests. Set
 * `RECONCILER_TEST_URL` (a `postgresql://` URI, or `host:port/db` combined
 * with `RECONCILER_TEST_USER`/`RECONCILER_TEST_PASSWORD`) to point at an
 * existing PostgreSQL 14+ instance. Without it, the tests fall back to the
 * conventional local container
 * (`docker run -d --name reconciler-tck -e POSTGRES_USER=rcl
 *  -e POSTGRES_PASSWORD=rcl -e POSTGRES_DB=rcl -p 55999:5432 postgres:18`)
 * and skip when nothing answers there — the Java Testcontainers fallback
 * has no zero-dependency analog here.
 */

const LOCAL_URL = "postgresql://rcl:rcl@localhost:55999/rcl";

function config(url: string, user?: string, password?: string): pg.ClientConfig {
    const c: pg.ClientConfig = { connectionString: url.startsWith("postgres")
            ? url : "postgresql://" + url };
    if (user !== undefined) {
        c.user = user;
        c.password = password;
    }
    return c;
}

/** A `SqlConnection` over one `pg.Client`. */
export class PgTestConnection implements SqlConnection {
    private readonly client: pg.Client;
    private autoCommit = true;
    private meta: DatabaseMetadata | undefined;

    constructor(client: pg.Client) {
        this.client = client;
    }

    /** The underlying driver client, for test-side assertions. */
    raw(): pg.Client {
        return this.client;
    }

    async metadata(): Promise<DatabaseMetadata> {
        if (this.meta === undefined) {
            const r = await this.client.query<{ server_version: string }>(
                    "SHOW server_version");
            this.meta = {
                databaseProductName: "PostgreSQL",
                databaseMajorVersion: Number(
                        r.rows[0]!.server_version.split(".")[0]),
            };
        }
        return this.meta;
    }

    async execute(sql: string): Promise<void> {
        await this.client.query(sql);
    }

    async query<R = Record<string, unknown>>(sql: string,
            params?: readonly unknown[]): Promise<R[]> {
        const r = await this.client.query(sql, params === undefined
                ? [] : [...params]);
        return r.rows as R[];
    }

    getAutoCommit(): boolean {
        return this.autoCommit;
    }

    async setAutoCommit(on: boolean): Promise<void> {
        if (this.autoCommit === on) {
            return;
        }
        if (on) {
            await this.client.query("COMMIT");
        } else {
            await this.client.query("BEGIN");
        }
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

    async close(): Promise<void> {
        await this.client.end();
    }
}

/** A `SqlDataSource` issuing a fresh `pg.Client` per `connect()`. */
export class PgTestDataSource implements SqlDataSource {
    private readonly cfg: pg.ClientConfig;

    constructor(url?: string, user?: string, password?: string) {
        const env = process.env.RECONCILER_TEST_URL;
        const u = url ?? (env !== undefined && env !== "" ? env : LOCAL_URL);
        this.cfg = config(u, user ?? process.env.RECONCILER_TEST_USER,
                password ?? process.env.RECONCILER_TEST_PASSWORD);
    }

    async connect(): Promise<SqlConnection> {
        const client = new pg.Client(this.cfg);
        await client.connect();
        return new PgTestConnection(client);
    }
}

/** True when the configured test database answers. */
export async function available(): Promise<boolean> {
    try {
        const c = await new PgTestDataSource().connect();
        await c.close();
        return true;
    } catch {
        return false;
    }
}

/** Runs a statement on a throwaway connection (fixture setup/teardown). */
export async function execute(ds: SqlDataSource, sql: string): Promise<void> {
    const c = await ds.connect();
    try {
        await c.execute(sql);
    } finally {
        await c.close();
    }
}

export async function serverMajor(ds: SqlDataSource): Promise<number> {
    const c = await ds.connect();
    try {
        return (await c.metadata()).databaseMajorVersion;
    } finally {
        await c.close();
    }
}

/**
 * Installs a `pg_catalog.uuidv7()` stand-in on pre-18 servers so fixtures
 * written against the v18 built-in still exercise the whole pipeline.
 * Test databases only — it returns a random uuid, not a time-ordered one.
 */
export async function ensureUuidv7(ds: SqlDataSource): Promise<void> {
    if (await serverMajor(ds) < 18) {
        await execute(ds, "CREATE OR REPLACE FUNCTION pg_catalog.uuidv7()"
                + " RETURNS uuid LANGUAGE sql"
                + " AS $$ SELECT gen_random_uuid() $$");
    }
}
