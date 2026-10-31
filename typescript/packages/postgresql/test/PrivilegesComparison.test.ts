import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { Decision, DesiredState, ObjectKind, ReconcileOutcome,
        ReconcilePolicy, SchemaReconciler }
        from "@epicquest-studios/schema-reconciler-core";
import type { ReconcileReport } from "@epicquest-studios/schema-reconciler-core";
import { PostgreSqlDialect } from "../src/PostgreSqlDialect.ts";
import { PgTestDataSource, available, execute }
        from "./PgTestDatabase.ts";

/** Spec 5.4 / A.5: grants on a managed target become `PRIVILEGES` objects —
 *  desired GRANTs apply, acl drift renders GRANT/REVOKE to converge. */

const dbUp = await available();
const dialect = new PostgreSqlDialect();

function names(report: ReconcileReport): string {
    return report.unsupported.map(d => d.id.name).sort().join(",");
}

function run(ns: string, script: string) {
    const policy = ReconcilePolicy.defaults()
            .withUnsupportedCategory("privileges", Decision.REPORT)
            .withUnsupportedCategory("ownership", Decision.REPORT);
    return SchemaReconciler.builder().dataSource(new PgTestDataSource())
            .namespace(ns).desiredState(DesiredState.ofScript(script))
            .policy(policy).dialect(dialect).build().reconcile();
}

describe("PrivilegesComparisonTest", {
    skip: !dbUp ? "no PostgreSQL test database" : false,
}, () => {
    it("grants apply and acl drift converges", async () => {
        const ds = new PgTestDataSource();
        const id = process.hrtime.bigint().toString(16);
        const ns = "privcmp_" + id;
        const reader = "priv_reader_" + id;
        const writer = "priv_writer_" + id;
        await execute(ds, "CREATE ROLE " + reader + " NOLOGIN");
        await execute(ds, "CREATE ROLE " + writer + " NOLOGIN");
        await execute(ds, "CREATE SCHEMA " + ns);
        try {
            const script = "CREATE TABLE t (id bigint);"
                    + " GRANT SELECT ON t TO " + reader
                    + "; GRANT INSERT ON t TO " + reader;

            // the first run creates the table and grants the desired
            // privileges
            const first = await run(ns, script);
            assert.equal(first.outcome, ReconcileOutcome.RECONCILED);
            assert.equal(names(first), "",
                    JSON.stringify(first.unsupported));
            assert.ok(first.applied.some(c =>
                    c.target.kind === ObjectKind.PRIVILEGES
                    && c.statements.some(s => s.startsWith("GRANT SELECT"))),
                    "expected a GRANT in " + JSON.stringify(first.applied));
            assert.equal((await run(ns, script)).outcome,
                    ReconcileOutcome.UP_TO_DATE);

            // an extra privilege on the target is drift: it is revoked
            await execute(ds, "GRANT DELETE ON " + ns + ".t TO " + reader);
            const extra = await run(ns, script);
            assert.equal(extra.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(extra.applied.some(c =>
                    c.target.kind === ObjectKind.PRIVILEGES
                    && c.statements.some(s => s.startsWith("REVOKE DELETE"))),
                    "expected a REVOKE in " + JSON.stringify(extra.applied));
            assert.equal((await run(ns, script)).outcome,
                    ReconcileOutcome.UP_TO_DATE);

            // a different grantee is drift too: the foreign grant is revoked
            await execute(ds, "GRANT SELECT ON " + ns + ".t TO " + writer);
            const grantee = await run(ns, script);
            assert.equal(grantee.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(grantee.applied.some(c =>
                    c.target.kind === ObjectKind.PRIVILEGES
                    && c.statements.some(s => s.startsWith("REVOKE SELECT")
                            && s.includes(writer))),
                    "expected a REVOKE in " + JSON.stringify(grantee.applied));
            assert.equal((await run(ns, script)).outcome,
                    ReconcileOutcome.UP_TO_DATE);

            // the grant option is part of the desired state
            const grantable = await run(ns, "CREATE TABLE t (id bigint);"
                    + " GRANT SELECT ON t TO " + reader
                    + " WITH GRANT OPTION; GRANT INSERT ON t TO " + reader);
            assert.equal(grantable.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(grantable.applied.some(c =>
                    c.target.kind === ObjectKind.PRIVILEGES
                    && c.statements.some(s =>
                            s.includes("WITH GRANT OPTION"))),
                    "expected WITH GRANT OPTION in "
                    + JSON.stringify(grantable.applied));

            // a different owner shows up as an unexpected OWNERSHIP object,
            // and the owner-normalised ACL still compares equal: the held
            // privileges reconcile, nothing drifts
            await execute(ds, "ALTER TABLE " + ns + ".t OWNER TO " + writer);
            const owned = await run(ns, script);
            assert.ok(owned.unexpected.some(d =>
                    d.id.kind === ObjectKind.OWNERSHIP
                    && d.id.name === "t"), JSON.stringify(owned.unexpected));
        } finally {
            await execute(ds, "DROP SCHEMA " + ns + " CASCADE");
            await execute(ds, "DROP OWNED BY " + reader);
            await execute(ds, "DROP OWNED BY " + writer);
            await execute(ds, "DROP ROLE " + reader);
            await execute(ds, "DROP ROLE " + writer);
        }
    });
});
