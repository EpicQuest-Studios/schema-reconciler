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

/** Spec 5.4 / A.5: {@code privileges:*} compares the real ACL, so grants the target already has are not reported. */
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
    void grantsTheTargetAlreadyHasAreNotReportedButDifferencesAre() throws Exception {
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

            // the first run creates the table; the grants are unsupported and still absent
            final var first = run(ns, script);
            assertEquals(ReconcileOutcome.RECONCILED, first.outcome());
            assertEquals("privileges:t", names(first));
            assertTrue(first.unsupported().get(0).actual().isEmpty());

            // the target gets the same grants, in the opposite order: nothing left to report
            PgTestDatabase.execute(ds, "GRANT INSERT ON " + ns + ".t TO " + reader);
            PgTestDatabase.execute(ds, "GRANT SELECT ON " + ns + ".t TO " + reader);
            final var equal = run(ns, script);
            assertEquals(ReconcileOutcome.UP_TO_DATE, equal.outcome());
            assertEquals("", names(equal), equal.unsupported().toString());

            // an extra privilege, and then a different grantee, are real differences
            PgTestDatabase.execute(ds, "GRANT DELETE ON " + ns + ".t TO " + reader);
            final var extra = run(ns, script);
            assertEquals("privileges:t", names(extra));
            assertTrue(extra.unsupported().get(0).actual().isPresent());
            PgTestDatabase.execute(ds, "REVOKE DELETE ON " + ns + ".t FROM " + reader);
            PgTestDatabase.execute(ds, "GRANT SELECT ON " + ns + ".t TO " + writer);
            assertEquals("privileges:t", names(run(ns, script)));
            PgTestDatabase.execute(ds, "REVOKE SELECT ON " + ns + ".t FROM " + writer);
            assertEquals("", names(run(ns, script)));

            // the grant option is part of the comparison
            final var grantable = run(ns, "CREATE TABLE t (id bigint);"
                    + " GRANT SELECT ON t TO " + reader + " WITH GRANT OPTION;"
                    + " GRANT INSERT ON t TO " + reader);
            assertEquals("privileges:t", names(grantable));

            // a different owner shows up as an unexpected ownership object, and the owner-normalised
            // ACL still compares equal: no privileges difference
            PgTestDatabase.execute(ds, "ALTER TABLE " + ns + ".t OWNER TO " + writer);
            final var owned = run(ns, script);
            assertEquals("", names(owned), owned.unsupported().toString());
            assertTrue(owned.unexpected().stream().anyMatch(d -> d.id().name().equals("ownership:t")),
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
