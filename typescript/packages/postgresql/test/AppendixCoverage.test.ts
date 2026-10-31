import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ObjectKind } from "@epicquest-studios/schema-reconciler-core";
import type { SqlConnection } from "@epicquest-studios/schema-reconciler-core";
import { PostgreSqlDialect } from "../src/PostgreSqlDialect.ts";
import { PgTestDataSource, available } from "./PgTestDatabase.ts";

/**
 * Spec Appendix C: every construct in the "detected but unsupported" list
 * surfaces as an `OTHER` object with its documented name prefix, and the
 * comment/security-label mirroring rules hold.
 */

/** [setup SQL (run in the schema, search_path bound), expected OTHER
 *  prefix, "super" if a superuser is required]. */
const CASES: readonly (readonly [string, string] | readonly [string, string, string])[] = [
    ["CREATE TABLE pt2 (k int) PARTITION BY RANGE (k);"
            + " CREATE INDEX pix ON pt2 (k)", "partindex:"],
    ["CREATE TABLE parent (id int); CREATE TABLE child () INHERITS (parent)",
            "inherited:"],
    ["CREATE DEFAULT CONVERSION conv FOR 'LATIN1' TO 'UTF8'"
            + " FROM iso8859_1_to_utf8", "conversion:"],
    ["CREATE TABLE cmtp (id int); CREATE TABLE cmtc () INHERITS (cmtp);"
            + " COMMENT ON TABLE cmtc IS 'x'", "comment:"],
    ["CREATE TABLE gp (id int); CREATE TABLE gc () INHERITS (gp);"
            + " GRANT SELECT ON gc TO PUBLIC", "privileges:"],
    // a cast needs an in-namespace anchor that is not managed — the
    // rowtypes of an inheritance pair are the last unmanaged anchor:
    // an inout cast between them stays cast: OTHER
    ["CREATE TABLE cast_p (a int); CREATE TABLE cast_c () INHERITS (cast_p);"
            + " CREATE CAST (cast_c AS cast_p) WITH INOUT", "cast:"],
    ["CREATE ROLE role_cov_owner; CREATE TABLE ow_p (id int);"
            + " CREATE TABLE ow_c () INHERITS (ow_p);"
            + " ALTER TABLE ow_c OWNER TO role_cov_owner", "ownership:"],
    // superuser-only: a stand-in transform function, and a catalog row in
    // place of a security-label provider module (none ships with a stock
    // server)
    ["CREATE FUNCTION tfrom(internal) RETURNS internal LANGUAGE internal"
            + " IMMUTABLE AS 'textin';"
            + " CREATE TRANSFORM FOR int4 LANGUAGE plpgsql"
            + " (FROM SQL WITH FUNCTION tfrom(internal))",
            "transform:", "super"],
    ["CREATE TABLE lp (id int); CREATE TABLE lc () INHERITS (lp);"
            + " INSERT INTO pg_seclabel (objoid, classoid, objsubid,"
            + " provider, label) VALUES ((SELECT oid FROM pg_class"
            + " WHERE relname = 'lc'), 'pg_class'::regclass::oid, 0,"
            + " 'dummy', 'x')", "seclabel:", "super"],
];

const dbUp = await available();
const ds = new PgTestDataSource();
const dialect = new PostgreSqlDialect();

let sequence = 0;

async function superuser(c: SqlConnection): Promise<boolean> {
    const rows = await c.query<{ rolsuper: boolean }>(
            "SELECT rolsuper FROM pg_roles WHERE rolname = current_user");
    return rows.length > 0 && rows[0]!.rolsuper;
}

describe("AppendixCoverageTest", {
    skip: !dbUp ? "no PostgreSQL test database" : false,
}, () => {
    it("detected-but-unsupported constructs surface as OTHER", async () => {
        for (const testCase of CASES) {
            const schema = "cov_" + process.hrtime.bigint().toString(16)
                    + "_" + (++sequence);
            const quoted = dialect.quoteIdentifier(schema);
            const c = await ds.connect();
            try {
                await c.setAutoCommit(false);
                if (testCase.length > 2 && !(await superuser(c))) {
                    continue;
                }
                try {
                    await c.execute("CREATE SCHEMA " + quoted);
                    await c.execute("SET LOCAL search_path = " + quoted);
                    await c.execute(testCase[0]);
                    const snap = await dialect.readSnapshot(c, schema);
                    assert.ok([...snap.objects.keys()].some(id =>
                            id.kind === ObjectKind.OTHER
                            && id.name.startsWith(testCase[1])),
                            testCase[0] + " -> expected an OTHER object"
                            + " prefixed " + testCase[1] + " in "
                            + JSON.stringify([...snap.objects.keys()]));
                } finally {
                    await c.rollback();
                }
            } finally {
                await c.close();
            }
        }
    });

    it("label on managed target is managed", async (t) => {
        // superuser-only: a catalog row stands in for a security-label
        // provider module — none ships with a stock server
        const schema = "cov_" + process.hrtime.bigint().toString(16)
                + "_" + (++sequence);
        const quoted = dialect.quoteIdentifier(schema);
        const c = await ds.connect();
        try {
            await c.setAutoCommit(false);
            if (!(await superuser(c))) {
                t.skip("requires a superuser");
                return;
            }
            try {
                await c.execute("CREATE SCHEMA " + quoted);
                await c.execute("SET LOCAL search_path = " + quoted);
                await c.execute("CREATE TABLE lt (id int)");
                await c.execute("INSERT INTO pg_seclabel (objoid, classoid,"
                        + " objsubid, provider, label) VALUES"
                        + " ('lt'::regclass::oid, 'pg_class'::regclass::oid,"
                        + " 0, 'dummy', 'x')");
                const snap = await dialect.readSnapshot(c, schema);
                const entry = [...snap.objects.entries()].find(([id]) =>
                        id.kind === ObjectKind.SECURITY_LABEL);
                assert.ok(entry !== undefined, "no SECURITY_LABEL in "
                        + JSON.stringify([...snap.objects.keys()]));
                const [id, obj] = entry;
                assert.equal(id.name, "dummy:lt");
                assert.equal(obj.definition.canonical, "x");
                assert.ok([...obj.dependsOn.values()].some(
                        d => d.kind === ObjectKind.TABLE && d.name === "lt"),
                        "expected a dependency on TABLE lt");
            } finally {
                await c.rollback();
            }
        } finally {
            await c.close();
        }
    });

    it("label on column is managed and parented", async (t) => {
        // superuser-only catalog row — a column label mirrors the column:
        // parented SECURITY_LABEL (spec 4.1)
        const schema = "cov_" + process.hrtime.bigint().toString(16)
                + "_" + (++sequence);
        const quoted = dialect.quoteIdentifier(schema);
        const c = await ds.connect();
        try {
            await c.setAutoCommit(false);
            if (!(await superuser(c))) {
                t.skip("requires a superuser");
                return;
            }
            try {
                await c.execute("CREATE SCHEMA " + quoted);
                await c.execute("SET LOCAL search_path = " + quoted);
                await c.execute("CREATE TABLE lt (id int)");
                await c.execute("INSERT INTO pg_seclabel (objoid, classoid,"
                        + " objsubid, provider, label) VALUES"
                        + " ('lt'::regclass::oid, 'pg_class'::regclass::oid,"
                        + " 1, 'dummy', 'y')");
                const snap = await dialect.readSnapshot(c, schema);
                const label = [...snap.objects.keys()].find(
                        id => id.kind === ObjectKind.SECURITY_LABEL);
                assert.ok(label !== undefined, "no SECURITY_LABEL in "
                        + JSON.stringify([...snap.objects.keys()]));
                assert.ok(label.name === "dummy:id" && label.parent === "lt",
                        "expected parented dummy:id, got " + label);
            } finally {
                await c.rollback();
            }
        } finally {
            await c.close();
        }
    });

    it("comment on partition and foreign-table column is managed",
            async (t) => {
        // partition and foreign-table columns are managed COLUMN objects —
        // their comments mirror like a table column's (A.5)
        const schema = "cov_" + process.hrtime.bigint().toString(16)
                + "_" + (++sequence);
        const quoted = dialect.quoteIdentifier(schema);
        const c = await ds.connect();
        try {
            await c.setAutoCommit(false);
            try {
                await c.execute("CREATE SCHEMA " + quoted);
                await c.execute("SET LOCAL search_path = " + quoted);
                try {
                    await c.execute("CREATE EXTENSION IF NOT EXISTS"
                            + " postgres_fdw");
                } catch {
                    t.skip("postgres_fdw not installable here");
                    return;
                }
                await c.execute("CREATE TABLE pp (id int)"
                        + " PARTITION BY RANGE (id);"
                        + " CREATE TABLE pc PARTITION OF pp"
                        + " FOR VALUES FROM (0) TO (100);"
                        + " COMMENT ON COLUMN pc.id IS 'pc-col';"
                        + " CREATE SERVER fsrv FOREIGN DATA WRAPPER"
                        + " postgres_fdw;"
                        + " CREATE FOREIGN TABLE ft (id int) SERVER fsrv;"
                        + " COMMENT ON COLUMN ft.id IS 'ft-col'");
                const snap = await dialect.readSnapshot(c, schema);
                const comments = [...snap.objects.keys()]
                        .filter(id => id.kind === ObjectKind.COMMENT)
                        .map(id => id.toString());
                assert.ok(comments.includes("COMMENT pc.id"),
                        "expected COMMENT pc.id in "
                        + JSON.stringify(comments));
                assert.ok(comments.includes("COMMENT ft.id"),
                        "expected COMMENT ft.id in "
                        + JSON.stringify(comments));
            } finally {
                await c.rollback();
            }
        } finally {
            await c.close();
        }
    });
});
