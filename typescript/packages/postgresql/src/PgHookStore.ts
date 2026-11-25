import type { HookPhase, HookSqlExecutor, HookStore } from "@epicquest-studios/schema-reconciler-core";
import { IllegalArgumentError, ObjectId, ObjectKind } from "@epicquest-studios/schema-reconciler-core";
import * as PgIdentifiers from "./internal/PgIdentifiers.ts";

/** PostgreSQL's durable hook store: one versioned history table keyed by `(namespace, hook_id)`. */
export class PgHookStore implements HookStore {
    static readonly DEFAULT_TABLE = "schema_reconciler_hooks";

    private readonly schema: string;
    private readonly table: string;

    private constructor(schema: string, table: string) {
        if (schema == null || schema === "") {
            throw new IllegalArgumentError("schema must not be empty");
        }
        if (table == null || table === "") {
            throw new IllegalArgumentError("table must not be empty");
        }
        this.schema = schema;
        this.table = table;
    }

    static inSchema(schema: string): PgHookStore {
        return new PgHookStore(schema, PgHookStore.DEFAULT_TABLE);
    }

    static of(schema: string, table: string = PgHookStore.DEFAULT_TABLE): PgHookStore {
        return new PgHookStore(schema, table);
    }

    async recordedChecksum(conn: HookSqlExecutor, namespace: string,
                           hookId: string): Promise<string | undefined> {
        await this.ensureTable(conn);
        const rows = await conn.query<{ checksum: string }>(
                "SELECT checksum FROM " + this.qualified()
                        + " WHERE namespace = $1 AND hook_id = $2",
                [namespace, hookId]);
        return rows[0]?.checksum;
    }

    async record(conn: HookSqlExecutor, namespace: string, hookId: string,
                 phase: HookPhase, checksum: string): Promise<void> {
        await this.ensureTable(conn);
        await conn.query("INSERT INTO " + this.qualified()
                + " (namespace, hook_id, phase, checksum) VALUES ($1, $2, $3, $4)"
                + " ON CONFLICT (namespace, hook_id) DO NOTHING",
                [namespace, hookId, phase, checksum]);
    }

    managedTable(targetNamespace: string): ObjectId | undefined {
        return this.schema === targetNamespace
                ? new ObjectId(ObjectKind.TABLE, this.table) : undefined;
    }

    private async ensureTable(conn: HookSqlExecutor): Promise<void> {
        await conn.execute("CREATE TABLE IF NOT EXISTS " + this.qualified() + " ("
                + "namespace text NOT NULL,"
                + "hook_id text NOT NULL,"
                + "phase text NOT NULL,"
                + "checksum text NOT NULL,"
                + "applied_at timestamptz NOT NULL DEFAULT now(),"
                + "PRIMARY KEY (namespace, hook_id))");
    }

    private qualified(): string {
        return PgIdentifiers.quote(this.schema) + "." + PgIdentifiers.quote(this.table);
    }
}

