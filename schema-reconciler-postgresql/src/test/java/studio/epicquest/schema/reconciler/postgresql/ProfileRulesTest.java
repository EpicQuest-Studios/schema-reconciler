package studio.epicquest.schema.reconciler.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.ConfigurationException;
import studio.epicquest.schema.reconciler.DesiredState;
import studio.epicquest.schema.reconciler.DesiredStateException;
import studio.epicquest.schema.reconciler.SchemaReconciler;
import studio.epicquest.schema.reconciler.spi.Dialect;

/** Spec 2.2 (dialect module wiring), 8.3 (namespace validation) and A.3 (savepoint recovery). */
class ProfileRulesTest {

    private final PostgreSqlDialect dialect = new PostgreSqlDialect();

    @AfterAll
    static void stopDatabase() {
        PgTestDatabase.stop();
    }

    @Test
    void dialectModuleProvidesTheDialectOnBothDiscoveryPaths() throws Exception {
        final var location = Path.of(PostgreSqlDialect.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        final var descriptor = ModuleFinder.of(location).findAll().iterator().next().descriptor();
        assertEquals("studio.epicquest.schema.reconciler.postgresql", descriptor.name());
        assertTrue(descriptor.provides().stream().anyMatch(p ->
                p.service().equals(Dialect.class.getName())
                        && p.providers().contains(PostgreSqlDialect.class.getName())),
                descriptor.provides().toString());
        // class-path discovery through META-INF/services
        assertTrue(StreamSupport.stream(ServiceLoader.load(Dialect.class).spliterator(), false)
                .anyMatch(d -> d instanceof PostgreSqlDialect));
    }

    @Test
    void anIllegalNamespaceIsAConfigurationError() {
        final var tooLong = "n".repeat(64);
        final var reconciler = SchemaReconciler.builder()
                .dataSource(PgTestDatabase.dataSource()).namespace(tooLong)
                .desiredState(DesiredState.ofScript("CREATE TABLE t (id int)"))
                .dialect(dialect).build();
        final var e = assertThrows(ConfigurationException.class, reconciler::plan);
        assertTrue(e.getMessage().contains("not a legal identifier"), e.getMessage());
    }

    @Test
    void aScriptThatCommittedIsCleanedUpAndReported() throws Exception {
        final var ds = PgTestDatabase.dataSource();
        String scratch;
        try (var c = ds.getConnection()) {
            c.setAutoCommit(false);
            scratch = dialect.createScratchNamespace(c, "profile_rules");
            c.commit(); // what a script's COMMIT would do: the savepoint is gone
            final var e = assertThrows(DesiredStateException.class,
                    () -> dialect.dropScratchNamespace(c, scratch));
            assertTrue(e.getMessage().contains("savepoint"), e.getMessage());
        }
        try (var c = ds.getConnection(); var s = c.createStatement();
                var rs = s.executeQuery("SELECT count(*) FROM pg_namespace WHERE nspname = '"
                        + scratch + "'")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "the committed scratch schema must have been dropped");
            assertFalse(rs.next());
        }
    }

    @Test
    void tckScenarioIdsMatchTheSpecTable() throws Exception {
        final var spec = new java.util.TreeSet<String>();
        for (final var line : java.nio.file.Files.readAllLines(Path.of("..", "SPEC.md"))) {
            final var m = java.util.regex.Pattern.compile("^\\| (C-\\d+) \\|").matcher(line);
            if (m.find()) {
                spec.add(m.group(1));
            }
        }
        final var tck = new java.util.TreeSet<String>();
        for (final var method : studio.epicquest.schema.reconciler.tck.DialectConformanceTest
                .class.getDeclaredMethods()) {
            final var name = method.getAnnotation(org.junit.jupiter.api.DisplayName.class);
            if (name != null) {
                final var m = java.util.regex.Pattern.compile("^(C-\\d+)").matcher(name.value());
                if (m.find()) {
                    tck.add(m.group(1));
                }
            }
        }
        assertEquals(48, spec.size());
        assertEquals(spec, tck, "TCK scenarios and SPEC.md rows must match 1:1");
    }

    @Test
    void hostileCatalogNamesAreQuotedInEveryRenderedStatement() throws Exception {
        final var ns = "quoting_" + Long.toHexString(System.nanoTime());
        final var ds = PgTestDatabase.dataSource();
        PgTestDatabase.execute(ds, "CREATE SCHEMA " + ns);
        try {
            final var script = "CREATE TABLE \"we\"\"ird; DROP TABLE x --\" ("
                    + "\"id col\" bigint PRIMARY KEY, \"q\"\"t\" text DEFAULT 'a''b',"
                    + " CONSTRAINT \"ck;1\" CHECK (\"id col\" > 0));"
                    + " CREATE INDEX \"ix \"\"1\"\"\" ON \"we\"\"ird; DROP TABLE x --\" (\"q\"\"t\");"
                    + " CREATE VIEW \"v; w\" AS SELECT \"id col\" FROM \"we\"\"ird; DROP TABLE x --\";"
                    + " CREATE SEQUENCE \"s ;q\";"
                    + " CREATE FUNCTION \"f(\"\"x\"\")\"(a int) RETURNS int LANGUAGE sql AS $$ SELECT a $$";
            final var reconciler = SchemaReconciler.builder().dataSource(ds).namespace(ns)
                    .desiredState(DesiredState.ofScript(script)).dialect(dialect).build();
            assertEquals(studio.epicquest.schema.reconciler.ReconcileOutcome.RECONCILED,
                    reconciler.reconcile().outcome());
            assertEquals(studio.epicquest.schema.reconciler.ReconcileOutcome.UP_TO_DATE,
                    reconciler.reconcile().outcome());
            // drift every kind and converge again: DROP / CREATE statements are rendered too
            PgTestDatabase.execute(ds, "DROP INDEX " + ns + ".\"ix \"\"1\"\"\"");
            PgTestDatabase.execute(ds, "DROP VIEW " + ns + ".\"v; w\"");
            PgTestDatabase.execute(ds, "ALTER TABLE " + ns + ".\"we\"\"ird; DROP TABLE x --\""
                    + " DROP CONSTRAINT \"ck;1\"");
            assertEquals(studio.epicquest.schema.reconciler.ReconcileOutcome.RECONCILED,
                    reconciler.reconcile().outcome());
            assertEquals(studio.epicquest.schema.reconciler.ReconcileOutcome.UP_TO_DATE,
                    reconciler.reconcile().outcome());
        } finally {
            PgTestDatabase.execute(ds, "DROP SCHEMA " + ns + " CASCADE");
        }
    }
}
