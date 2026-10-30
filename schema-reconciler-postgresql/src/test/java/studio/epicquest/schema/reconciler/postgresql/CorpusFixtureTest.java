package studio.epicquest.schema.reconciler.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import studio.epicquest.schema.reconciler.Decision;
import studio.epicquest.schema.reconciler.DesiredState;
import studio.epicquest.schema.reconciler.ReconcileOutcome;
import studio.epicquest.schema.reconciler.ReconcilePolicy;
import studio.epicquest.schema.reconciler.SchemaReconciler;
import studio.epicquest.schema.reconciler.spi.Dialect;

/**
 * End-to-end coverage over the obfuscated real-world schema corpus in
 * {@code src/test/resources/corpus/corpus-*.sql}. Each file is a captured
 * deployment script: the A.7 migration steps are applied in memory (literal
 * schema qualifier to {@code ${namespace}}; deployment-only statements
 * removed), then the script is reconciled against a real namespace and
 * asserted to converge and stay idempotent.
 */
class CorpusFixtureTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Pattern ROLE_REF = Pattern.compile("\\bTO (w\\d{3})\\b");
    private static final Pattern EXTENSION = Pattern.compile(
            "CREATE EXTENSION IF NOT EXISTS (\\w+)");

    private final DataSource dataSource = PgTestDatabase.dataSource();
    private final Dialect dialect = new PostgreSqlDialect();
    private String namespace;

    @BeforeEach
    void createNs() throws Exception {
        namespace = "corpus_" + SEQUENCE.incrementAndGet();
        execute("CREATE SCHEMA " + dialect.quoteIdentifier(namespace));
    }

    @AfterEach
    void dropNs() throws Exception {
        execute("DROP SCHEMA " + dialect.quoteIdentifier(namespace) + " CASCADE");
    }

    @AfterAll
    static void stop() {
        PgTestDatabase.stop();
    }

    private void execute(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
                final var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** The corpus directory on the test classpath ({@code /corpus}). */
    private static Path corpusDir() {
        try {
            final var url = CorpusFixtureTest.class.getResource("/corpus");
            if (url == null) {
                throw new IllegalStateException("corpus test resources not found");
            }
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    static Stream<Arguments> corpusFiles() throws IOException {
        try (Stream<Path> files = Files.list(corpusDir())) {
            return files.filter(f -> f.getFileName().toString().matches("corpus-\\d+\\.sql"))
                    .sorted()
                    .map(f -> Arguments.of(Named.named(f.getFileName().toString(), f)))
                    .toList()
                    .stream();
        }
    }

    /** The A.7 transform: literal qualifier to placeholder, deployment statements removed. */
    private static String migrate(String raw) {
        var script = raw.replaceAll("\\bfixture\\b",
                Matcher.quoteReplacement("${namespace}"));
        script = script.replaceAll("(?m)^\\s*SELECT\\s+pg_advisory_lock[^;]*;\\s*", "");
        script = script.replaceAll("(?m)^\\s*CREATE\\s+SCHEMA[^;]*;\\s*", "");
        script = script.replaceAll("(?m)^\\s*CREATE\\s+EXTENSION[^;]*;\\s*", "");
        return script;
    }

    /** v15-only syntax some corpus files exercise (NULLS NOT DISTINCT
     * indexes, security_invoker views) is downgraded on pre-15 servers —
     * same coverage trade-off as the uuidv7 stand-in. */
    private static String preFifteenCompat(String script) {
        var s = script.replaceAll("(?i)\\s+NULLS\\s+(?:NOT\\s+)?DISTINCT", "");
        s = s.replaceAll("(?i)security_invoker\\s*=\\s*\\w+\\s*,\\s*", "");
        s = s.replaceAll("(?i),\\s*security_invoker\\s*=\\s*\\w+", "");
        s = s.replaceAll("(?i)WITH\\s*\\(\\s*security_invoker\\s*=\\s*\\w+\\s*\\)", "");
        return s.replaceAll("(?i)WITH\\s*\\(\\s*\\)", "");
    }

    /** Creates the roles referenced by GRANT/policy statements, and the
     * extensions the script declares (relocatable ones go to pg_catalog so
     * the scratch search_path still resolves them). */
    private void prepareEnvironment(String raw) throws SQLException {
        if (raw.contains("uuidv7")) {
            PgTestDatabase.ensureUuidv7(dataSource);
        }
        final var roles = new TreeSet<String>();
        final var rm = ROLE_REF.matcher(raw);
        while (rm.find()) {
            roles.add(rm.group(1));
        }
        for (final var role : roles) {
            execute("DROP ROLE IF EXISTS " + dialect.quoteIdentifier(role));
            execute("CREATE ROLE " + dialect.quoteIdentifier(role));
        }
        final var em = EXTENSION.matcher(raw);
        while (em.find()) {
            final var ext = em.group(1);
            assumeTrue(extensionAvailable(ext),
                    "extension " + ext + " not available on this server");
            execute("CREATE EXTENSION IF NOT EXISTS " + dialect.quoteIdentifier(ext)
                    + " SCHEMA pg_catalog");
        }
    }

    private boolean extensionAvailable(String name) throws SQLException {
        try (Connection c = dataSource.getConnection();
                final var s = c.createStatement();
                final var rs = s.executeQuery(
                        "SELECT 1 FROM pg_available_extensions WHERE name = '"
                                + name.replace("'", "''") + "'")) {
            return rs.next();
        }
    }

    private SchemaReconciler reconciler(String script, boolean unsupportedReport) {
        final var builder = SchemaReconciler.builder()
                .dataSource(dataSource)
                .namespace(namespace)
                .desiredState(DesiredState.ofScript(script))
                .dialect(dialect);
        if (unsupportedReport) {
            builder.policy(ReconcilePolicy.defaults().withUnsupported(Decision.REPORT));
        }
        return builder.build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpusFiles")
    @DisplayName("corpus schema reconciles and is idempotent")
    void corpusReconciles(Path file) throws Exception {
        String raw;
        try {
            raw = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        prepareEnvironment(raw);
        var script = migrate(raw);
        if (PgTestDatabase.serverMajor(dataSource) < 15) {
            script = preFifteenCompat(script);
        }
        // privileges:* acl rows are unsupported by design (spec: unsupported
        // constructs are reported, not applied); a corpus file whose GRANTs
        // leave unrendered acl drift must run under unsupported=REPORT and
        // keeps reporting it on every run.
        final var expectUnsupported = raw.contains("GRANT");

        final var first = reconciler(script, expectUnsupported).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, first.outcome(),
                () -> "reported: " + first.reported()
                        + " unsupported: " + first.unsupported());
        assertEquals(expectUnsupported, !first.unsupported().isEmpty(),
                "unsupported diffs: " + first.unsupported());

        final var second = reconciler(script, expectUnsupported).reconcile();
        assertEquals(ReconcileOutcome.UP_TO_DATE, second.outcome());
        if (expectUnsupported) {
            assertTrue(!second.unsupported().isEmpty(),
                    "policy objects must keep reporting as unsupported");
        } else {
            assertTrue(second.unsupported().isEmpty(),
                    "unsupported diffs appeared on the idempotent re-run: "
                            + second.unsupported());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpusFiles")
    @DisplayName("3.1 dump() round-trips the reconciled corpus schema")
    void corpusDumpRoundTrips(Path file) throws Exception {
        String raw;
        try {
            raw = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        prepareEnvironment(raw);
        var script = migrate(raw);
        if (PgTestDatabase.serverMajor(dataSource) < 15) {
            script = preFifteenCompat(script);
        }
        final var expectUnsupported = raw.contains("GRANT");
        assertEquals(ReconcileOutcome.RECONCILED,
                reconciler(script, expectUnsupported).reconcile().outcome());

        final var dumper = SchemaReconciler.builder()
                .dataSource(dataSource).namespace(namespace).dialect(dialect).build();
        final var dump = dumper.dump();
        // the emitted script is a desired-state script — feeding it back on
        // the same namespace must be a no-op
        final var self = reconciler(dump, expectUnsupported).reconcile();
        assertEquals(ReconcileOutcome.UP_TO_DATE, self.outcome(),
                () -> "dump replay reported changes: " + self.plan().changes());
        assertEquals(dump, dumper.dump(), "second dump is not byte-identical");
    }
}
