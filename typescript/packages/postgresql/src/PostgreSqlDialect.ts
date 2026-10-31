import { ConfigurationException } from "@schema-reconciler/core";
import { DesiredStateException } from "@schema-reconciler/core";
import { LockTimeoutException } from "@schema-reconciler/core";
import type { Difference, ObjectId, PlannedChange } from "@schema-reconciler/core";
import { ObjectKind } from "@schema-reconciler/core";
import type { SchemaObject, SchemaSnapshot } from "@schema-reconciler/core";
import type { DatabaseMetadata, Dialect } from "@schema-reconciler/core";
import { DialectCapabilities } from "@schema-reconciler/core";
import type { Duration, SqlConnection } from "@schema-reconciler/core";
import * as PgCatalog from "./internal/PgCatalog.ts";
import * as PgIdentifiers from "./internal/PgIdentifiers.ts";
import * as PgRender from "./internal/PgRender.ts";
import * as PgScriptCheck from "./internal/PgScriptCheck.ts";
import * as PgSql from "./internal/PgSql.ts";

/**
 * The PostgreSQL dialect of spec Appendix A — the reference implementation.
 * Supports PostgreSQL 14 and later.
 */

const SAVEPOINT = "rcl_scratch";
const LOCK_KEY_PREFIX = "studio.epicquest.schema.reconciler:";

const CREATE_EXTENSION = /^\s*CREATE\s+EXTENSION\s+(?:IF\s+NOT\s+EXISTS\s+)?("(?:[^"]|"")*"|[\w-]+)/i;

/** Creates the dialect — stateless, safe to share. */
export class PostgreSqlDialect implements Dialect {

    id(): string {
        return "postgresql";
    }

    supports(metaData: DatabaseMetadata): boolean {
        return metaData.databaseProductName === "PostgreSQL"
                && metaData.databaseMajorVersion >= 14;
    }

    capabilities(): DialectCapabilities {
        const kinds = new Set<ObjectKind>(
                Object.values(ObjectKind) as ObjectKind[]);
        kinds.delete(ObjectKind.OTHER);
        return new DialectCapabilities(true, true, kinds);
    }

    // ---- A.2 locking -------------------------------------------------------

    async acquireLock(connection: SqlConnection, namespace: string,
            timeout: Duration): Promise<void> {
        await connection.execute("SET LOCAL lock_timeout = '"
                + millisBounded(timeout) + "ms'");
        try {
            await connection.query(
                    "SELECT pg_advisory_xact_lock(hashtextextended("
                            + "'" + LOCK_KEY_PREFIX + "'"
                            + " || current_database() || ':' || $1, 0))",
                    [namespace]);
        } catch (e) {
            if (sqlState(e) === "55P03") {
                throw new LockTimeoutException("namespace '" + namespace
                        + "': lock not acquired within " + timeout, { cause: e });
            }
            throw e;
        }
        await connection.execute("SET LOCAL lock_timeout = DEFAULT");
    }

    async releaseLock(connection: SqlConnection, _namespace: string): Promise<void> {
        await connection.execute("SELECT pg_advisory_unlock_all()");
    }

    async bindSessionNamespace(connection: SqlConnection, namespace: string): Promise<void> {
        await connection.execute("SET LOCAL search_path = "
                + PgIdentifiers.quote(namespace));
    }

    async statementTimeout(connection: SqlConnection, timeout: Duration): Promise<void> {
        await connection.execute("SET LOCAL statement_timeout = '"
                + millisBounded(timeout) + "ms'");
    }

    // ---- A.3 scratch namespace ----------------------------------------------

    async createScratchNamespace(connection: SqlConnection,
            targetNamespace: string): Promise<string> {
        const scratch = PgIdentifiers.scratchName(
                targetNamespace, Math.floor(Math.random() * 0x1000000000000));
        // a separate scratch connection arrives here with no open
        // transaction; BEGIN is required before SAVEPOINT and is a
        // harmless warning when the transaction is already open
        await connection.execute("BEGIN");
        await connection.execute("SAVEPOINT " + SAVEPOINT);
        await connection.execute("CREATE SCHEMA " + PgIdentifiers.quote(scratch));
        return scratch;
    }

    async dropScratchNamespace(connection: SqlConnection,
            scratchNamespace: string): Promise<void> {
        try {
            await connection.execute("ROLLBACK TO SAVEPOINT " + SAVEPOINT);
            // the rollback undoes settings but not session advisory locks, which a
            // function the script invoked may have taken (the static check cannot
            // see through a call): release them before the connection is reused
            await connection.execute("SELECT pg_advisory_unlock_all()");
        } catch (e) {
            // the savepoint is gone: the script committed (or aborted) the
            // transaction — remove the committed schema explicitly (A.3)
            await rollbackQuietly(connection);
            await connection.execute("SELECT pg_advisory_unlock_all()");
            await connection.execute("DROP SCHEMA IF EXISTS "
                    + PgIdentifiers.quote(scratchNamespace) + " CASCADE");
            await connection.commit();
            throw new DesiredStateException("the desired script left the transaction: "
                    + "the run's savepoint is gone (" + message(e) + ")", { cause: e });
        }
    }

    // ---- A.4 script execution -----------------------------------------------

    async checkAccess(connection: SqlConnection, namespace: string | undefined,
            createNamespace: boolean): Promise<void> {
        if (namespace !== undefined) {
            // to_regnamespace parses its argument as an identifier, so
            // quote it to keep mixed-case namespaces exact
            const quoted = PgIdentifiers.quote(namespace);
            // a comma list means "any of", so each privilege is checked alone
            const rows = await connection.query<{ exists: unknown; granted: boolean }>(
                    "SELECT to_regnamespace($1) AS exists,"
                            + " has_schema_privilege(current_user, to_regnamespace($2), 'USAGE')"
                            + " AND has_schema_privilege(current_user, to_regnamespace($3), 'CREATE')"
                            + " AS granted",
                    [quoted, quoted, quoted]);
            const row = rows[0];
            if (row !== undefined && row.exists == null) {
                throw new ConfigurationException("namespace '" + namespace
                        + "' does not exist on the target database");
            }
            if (row === undefined || !row.granted) {
                throw new ConfigurationException("namespace '" + namespace
                        + "': the connecting user lacks USAGE or CREATE"
                        + " on the target namespace");
            }
        }
        if (createNamespace) {
            const rows = await connection.query<{ granted: boolean }>(
                    "SELECT has_database_privilege(current_user, current_database(),"
                            + " 'CREATE') AS granted");
            if (rows[0] === undefined || !rows[0].granted) {
                throw new ConfigurationException("the connecting user lacks"
                        + " CREATE on the database, needed for the scratch"
                        + " namespace; configure scratchDataSource to"
                        + " offload scratch creation");
            }
        }
    }

    // ---- A.4 script execution -----------------------------------------------

    async executeScript(connection: SqlConnection, script: string,
            scratchNamespace: string): Promise<void> {
        PgScriptCheck.check(script, scratchNamespace);
        await connection.execute(script);
        await this.anchorScriptExtensions(connection, script, scratchNamespace);
    }

    /**
     * An extension is a database-wide object anchored to a namespace; a
     * `CREATE EXTENSION IF NOT EXISTS` in the desired script no-ops
     * when the extension is already installed anywhere, so the scratch
     * snapshot would never see it. Relocating a relocatable pre-installed
     * extension into the scratch namespace lets the snapshot report the
     * anchored `EXTENSION` object — the savepoint rollback undoes the
     * relocation with everything else.
     */
    private async anchorScriptExtensions(connection: SqlConnection, script: string,
            scratchNamespace: string): Promise<void> {
        for (const statement of PgSql.statements(script)) {
            const m = CREATE_EXTENSION.exec(statement.text);
            if (m === null) {
                continue;
            }
            const raw = m[1]!;
            const name = raw.startsWith("\"")
                    ? raw.substring(1, raw.length - 1).replaceAll("\"\"", "\"")
                    : raw.toLowerCase();
            const rows = await connection.query<{ extrelocatable: boolean }>(
                    "SELECT e.extrelocatable FROM pg_extension e"
                            + " JOIN pg_namespace n ON n.oid = e.extnamespace"
                            + " WHERE e.extname = $1 AND n.nspname <> $2",
                    [name, scratchNamespace]);
            if (rows[0] !== undefined && rows[0].extrelocatable) {
                await connection.execute("ALTER EXTENSION "
                        + PgIdentifiers.quote(name)
                        + " SET SCHEMA " + PgIdentifiers.quote(scratchNamespace));
            }
        }
    }

    // ---- A.5 snapshot ---------------------------------------------------------

    readSnapshot(connection: SqlConnection, namespace: string): Promise<SchemaSnapshot> {
        return PgCatalog.read(connection, namespace);
    }

    // ---- A.6 rendering ---------------------------------------------------------

    render(difference: Difference, desired: SchemaSnapshot,
            targetNamespace: string): PlannedChange[] {
        return PgRender.render(difference, desired, targetNamespace);
    }

    quoteIdentifier(identifier: string): string {
        return PgIdentifiers.quote(identifier);
    }

    /**
     * 6.1: the object's complete create-fragment list in `${namespace}`
     * token form. `PgCatalog.DUMP_SQL` joins the statements with a
     * U+0000 separator — impossible in PostgreSQL text — so the split is
     * lossless; absent means folded or unrenderable. A trailing semicolon
     * (pg_get_viewdef leaves one) is dropped: the dump terminator adds it.
     */
    dumpStatements(object: SchemaObject): string[] {
        const dump = object.attributes.get(PgCatalog.DUMP_SQL);
        if (dump === undefined) {
            return [];
        }
        return dump.split("\u0000").map(PgSql.dropTerminator);
    }

    coveredChildren(created: Difference, desired: SchemaSnapshot): Iterable<ObjectId> {
        return PgRender.coveredChildren(created, desired);
    }

    nameScopes(id: ObjectId): Iterable<string> {
        return PgRender.nameScopes(id);
    }
}

/** A positive duration below one millisecond must still bound the wait. */
function millisBounded(timeout: Duration): number {
    return Math.max(1, timeout.toMillis());
}

async function rollbackQuietly(connection: SqlConnection): Promise<void> {
    try {
        await connection.rollback();
    } catch {
        // best effort — the connection is already on its way out
    }
}

function sqlState(e: unknown): string | undefined {
    return typeof e === "object" && e !== null && "code" in e
            ? String((e as { code: unknown }).code) : undefined;
}

function message(e: unknown): string {
    return e instanceof Error ? e.message : String(e);
}
