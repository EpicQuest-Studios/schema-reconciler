package studio.epicquest.schema.reconciler.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.Decision;
import studio.epicquest.schema.reconciler.DesiredState;
import studio.epicquest.schema.reconciler.ReconcileOutcome;
import studio.epicquest.schema.reconciler.ReconcilePolicy;
import studio.epicquest.schema.reconciler.ReconcileReport;
import studio.epicquest.schema.reconciler.SchemaReconciler;
import studio.epicquest.schema.reconciler.model.ObjectKind;

/** Spec 5.4 / A.5: grants on a managed target become {@code PRIVILEGES} objects —
 * desired GRANTs apply, acl drift renders GRANT/REVOKE to converge. */
class PrivilegesComparisonTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    @AfterAll
    static void stopDatabase() {
        PgTestDatabase.stop();
    }

    private static String names(ReconcileReport report) {
        return report.unsupported().stream().map(d -> d.id().name())
                .sorted().collect(Collectors.joining(","));
    }

    private static ReconcileReport run(String ns, String script) {
        final var policy = ReconcilePolicy.defaults()
                .withUnsupportedCategory("privileges", Decision.REPORT)
                .withUnsupportedCategory("ownership", Decision.REPORT);
        return SchemaReconciler.builder().dataSource(PgTestDatabase.dataSource()).namespace(ns)
                .desiredState(DesiredState.ofScript(script)).policy(policy)
                .dialect(new PostgreSqlDialect()).build().reconcile();
    }

    @Test
    void grantsApplyAndAclDriftConverges() throws Exception {
        final var ds = PgTestDatabase.dataSource();
        final var id = Long.toHexString(System.nanoTime()) + SEQUENCE.incrementAndGet();
        final var ns = "privcmp_" + id;
        final var reader = "priv_reader_" + id;
        final var writer = "priv_writer_" + id;
        PgTestDatabase.execute(ds, "CREATE ROLE " + reader + " NOLOGIN");
        PgTestDatabase.execute(ds, "CREATE ROLE " + writer + " NOLOGIN");
        PgTestDatabase.execute(ds, "CREATE SCHEMA " + ns);
        try {
            final var script = "CREATE TABLE t (id bigint);"
                    + " GRANT SELECT ON t TO " + reader + "; GRANT INSERT ON t TO " + reader;

            // the first run creates the table and grants the desired privileges
            final var first = run(ns, script);
            assertEquals(ReconcileOutcome.RECONCILED, first.outcome());
            assertEquals("", names(first), first.unsupported().toString());
            assertTrue(first.applied().stream().anyMatch(c ->
                            c.target().kind() == ObjectKind.PRIVILEGES
                                    && c.statements().stream()
                                            .anyMatch(s -> s.startsWith("GRANT SELECT"))),
                    "expected a GRANT in " + first.applied());
            assertEquals(ReconcileOutcome.UP_TO_DATE, run(ns, script).outcome());

            // an extra privilege on the target is drift: it is revoked
            PgTestDatabase.execute(ds, "GRANT DELETE ON " + ns + ".t TO " + reader);
            final var extra = run(ns, script);
            assertEquals(ReconcileOutcome.RECONCILED, extra.outcome());
            assertTrue(extra.applied().stream().anyMatch(c ->
                            c.target().kind() == ObjectKind.PRIVILEGES
                                    && c.statements().stream()
                                            .anyMatch(s -> s.startsWith("REVOKE DELETE"))),
                    "expected a REVOKE in " + extra.applied());
            assertEquals(ReconcileOutcome.UP_TO_DATE, run(ns, script).outcome());

            // a different grantee is drift too: the foreign grant is revoked
            PgTestDatabase.execute(ds, "GRANT SELECT ON " + ns + ".t TO " + writer);
            final var grantee = run(ns, script);
            assertEquals(ReconcileOutcome.RECONCILED, grantee.outcome());
            assertTrue(grantee.applied().stream().anyMatch(c ->
                            c.target().kind() == ObjectKind.PRIVILEGES
                                    && c.statements().stream().anyMatch(s ->
                                            s.startsWith("REVOKE SELECT")
                                                    && s.contains(writer))),
                    "expected a REVOKE in " + grantee.applied());
            assertEquals(ReconcileOutcome.UP_TO_DATE, run(ns, script).outcome());

            // the grant option is part of the desired state
            final var grantable = run(ns, "CREATE TABLE t (id bigint);"
                    + " GRANT SELECT ON t TO " + reader + " WITH GRANT OPTION;"
                    + " GRANT INSERT ON t TO " + reader);
            assertEquals(ReconcileOutcome.RECONCILED, grantable.outcome());
            assertTrue(grantable.applied().stream().anyMatch(c ->
                            c.target().kind() == ObjectKind.PRIVILEGES
                                    && c.statements().stream().anyMatch(s ->
                                            s.contains("WITH GRANT OPTION"))),
                    "expected WITH GRANT OPTION in " + grantable.applied());

            // a different owner shows up as an unexpected OWNERSHIP object, and the owner-normalised
            // ACL still compares equal: the held privileges reconcile, nothing drifts
            PgTestDatabase.execute(ds, "ALTER TABLE " + ns + ".t OWNER TO " + writer);
            final var owned = run(ns, script);
            assertTrue(owned.unexpected().stream().anyMatch(d ->
                            d.id().kind() == ObjectKind.OWNERSHIP
                                    && d.id().name().equals("t")),
                    owned.unexpected().toString());
        } finally {
            PgTestDatabase.execute(ds, "DROP SCHEMA " + ns + " CASCADE");
            PgTestDatabase.execute(ds, "DROP OWNED BY " + reader);
            PgTestDatabase.execute(ds, "DROP OWNED BY " + writer);
            PgTestDatabase.execute(ds, "DROP ROLE " + reader);
            PgTestDatabase.execute(ds, "DROP ROLE " + writer);
        }
    }
}
