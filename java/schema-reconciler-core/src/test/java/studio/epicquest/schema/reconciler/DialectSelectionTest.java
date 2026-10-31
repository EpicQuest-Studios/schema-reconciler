package studio.epicquest.schema.reconciler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.fake.FakeDb;
import studio.epicquest.schema.reconciler.fake.FakeDialect;
import studio.epicquest.schema.reconciler.model.Difference;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;
import studio.epicquest.schema.reconciler.spi.Dialect;
import studio.epicquest.schema.reconciler.spi.DialectCapabilities;

/** Spec 3.4 (selection), 6.3.2 (same-engine scratch) and 10.3 (core major version). */
class DialectSelectionTest {

    private static final String NS = "corex";

    /** A dialect with no behaviour: selection only ever asks it {@code supports}. */
    public abstract static class Stub implements Dialect {
        private final String id;
        private final boolean supported;
        private final int coreMajor;

        protected Stub(String id, boolean supported, int coreMajor) {
            this.id = id;
            this.supported = supported;
            this.coreMajor = coreMajor;
        }

        @Override public String id() { return id; }
        @Override public boolean supports(DatabaseMetaData m) throws SQLException {
            return supported && "FakeDB".equals(m.getDatabaseProductName());
        }
        @Override public int coreMajorVersion() { return coreMajor; }
        @Override public DialectCapabilities capabilities() {
            return new DialectCapabilities(true, true, Set.of());
        }
        @Override public void acquireLock(Connection c, String ns, Duration t) { }
        @Override public void releaseLock(Connection c, String ns) { }
        @Override public String createScratchNamespace(Connection c, String ns) {
            throw new UnsupportedOperationException();
        }
        @Override public void dropScratchNamespace(Connection c, String ns) { }
        @Override public void executeScript(Connection c, String s, String ns) { }
        @Override public SchemaSnapshot readSnapshot(Connection c, String ns) {
            throw new UnsupportedOperationException();
        }
        @Override public List<PlannedChange> render(Difference d, SchemaSnapshot s, String ns) {
            throw new UnsupportedOperationException();
        }
        @Override public String quoteIdentifier(String identifier) { return identifier; }
    }

    public static final class Alpha extends Stub { public Alpha() { super("alpha", true, 1); } }
    public static final class Beta extends Stub { public Beta() { super("beta", true, 1); } }
    public static final class Unsupporting extends Stub {
        public Unsupporting() { super("unsupporting", false, 1); }
    }
    public static final class FutureCore extends Stub {
        public FutureCore() { super("future", true, 2); }
    }

    /** Runs {@code body} with {@code providers} registered as ServiceLoader dialects. */
    private static <T> T withProviders(Supplier<T> body, Class<?>... providers) throws IOException {
        final var dir = Files.createTempDirectory("dialect-providers");
        final var services = dir.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(Dialect.class.getName()), String.join("\n",
                java.util.Arrays.stream(providers).map(Class::getName).toList()));
        final var previous = Thread.currentThread().getContextClassLoader();
        try (var loader = new URLClassLoader(new URL[] {dir.toUri().toURL()},
                DialectSelectionTest.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            return body.get();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static SchemaReconciler discovering(FakeDb db) {
        return SchemaReconciler.builder().dataSource(db.dataSource()).namespace(NS)
                .desiredState(DesiredState.ofScript("CREATE TABLE t DEF v")).build();
    }

    @Test
    void severalMatchingDialectsAreAmbiguous() throws Exception {
        final var e = assertThrows(ConfigurationException.class, () -> withProviders(
                () -> discovering(new FakeDb()).plan(), Alpha.class, Beta.class));
        assertTrue(e.getMessage().contains("several dialects"), e.getMessage());
        assertTrue(e.getMessage().contains("alpha, beta"), e.getMessage());
        assertTrue(e.getMessage().contains("namespace '" + NS + "'"), e.getMessage());
    }

    @Test
    void noMatchingDialectNamesTheProductAndTheCandidates() throws Exception {
        final var e = assertThrows(ConfigurationException.class, () -> withProviders(
                () -> discovering(new FakeDb()).plan(), Unsupporting.class));
        assertTrue(e.getMessage().contains("no dialect supports"), e.getMessage());
        assertTrue(e.getMessage().contains("FakeDB"), e.getMessage());
        assertTrue(e.getMessage().contains("unsupporting"), e.getMessage());
    }

    @Test
    void aDialectBuiltForAnotherCoreMajorVersionIsRefused() throws Exception {
        final var discovered = assertThrows(ConfigurationException.class, () -> withProviders(
                () -> discovering(new FakeDb()).plan(), FutureCore.class));
        assertTrue(discovered.getMessage().contains("core major version"),
                discovered.getMessage());

        final var db = new FakeDb();
        final var real = new FakeDialect(db);
        final Dialect explicit = (Dialect) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Dialect.class},
                (proxy, method, args) -> method.getName().equals("coreMajorVersion")
                        ? Integer.valueOf(2) : method.invoke(real, args));
        final var e = assertThrows(ConfigurationException.class,
                () -> SchemaReconciler.builder().dataSource(db.dataSource()).namespace(NS)
                        .desiredState(DesiredState.ofScript("CREATE TABLE t DEF v"))
                        .dialect(explicit).build().plan());
        assertTrue(e.getMessage().contains("core major version"), e.getMessage());
    }

    /** A DataSource whose connections report a different product or major version. */
    private static DataSource disguised(DataSource real, String product, int major) {
        return (DataSource) Proxy.newProxyInstance(DialectSelectionTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (p, m, a) -> {
                    final var result = m.invoke(real, a);
                    if (!m.getName().equals("getConnection")) {
                        return result;
                    }
                    final var connection = (Connection) result;
                    return Proxy.newProxyInstance(DialectSelectionTest.class.getClassLoader(),
                            new Class<?>[] {Connection.class}, (cp, cm, ca) -> {
                                final var value = cm.invoke(connection, ca);
                                if (!cm.getName().equals("getMetaData")) {
                                    return value;
                                }
                                final var meta = (DatabaseMetaData) value;
                                return Proxy.newProxyInstance(
                                        DialectSelectionTest.class.getClassLoader(),
                                        new Class<?>[] {DatabaseMetaData.class}, (mp, mm, ma) ->
                                                switch (mm.getName()) {
                                                    case "getDatabaseProductName" -> product;
                                                    case "getDatabaseMajorVersion" -> major;
                                                    default -> mm.invoke(meta, ma);
                                                });
                            });
                });
    }

    @Test
    void scratchDataSourceMustBeTheSameEngineAndMajorVersion() {
        for (final var scratch : List.of(
                new Object[] {"FakeDB", 2}, new Object[] {"OtherDB", 1})) {
            final var db = new FakeDb();
            final var reconciler = SchemaReconciler.builder().dataSource(db.dataSource())
                    .scratchDataSource(disguised(db.dataSource(), (String) scratch[0],
                            (Integer) scratch[1]))
                    .namespace(NS).desiredState(DesiredState.ofScript("CREATE TABLE t DEF v"))
                    .dialect(new FakeDialect(db)).build();
            final var e = assertThrows(ConfigurationException.class, reconciler::plan);
            assertTrue(e.getMessage().contains("not the same engine"), e.getMessage());
            assertTrue(e.getMessage().contains("namespace '" + NS + "'"), e.getMessage());
        }
    }

    /** Spec 6.1: checkAccess runs per connection before the lock; executeScript gets the scratch name. */
    @Test
    void spiContractCallsMatchTheJavadoc() {
        final var db = new FakeDb();
        final var real = new FakeDialect(db);
        final var calls = new java.util.ArrayList<String>();
        final var scratchNames = new java.util.ArrayList<String>();
        final Dialect recording = (Dialect) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Dialect.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "checkAccess" -> calls.add("checkAccess(" + args[1] + "," + args[2] + ")");
                        case "acquireLock" -> calls.add("acquireLock");
                        case "executeScript" -> calls.add("executeScript(" + args[2] + ")");
                        default -> { }
                    }
                    final var result = method.invoke(real, args);
                    if (method.getName().equals("createScratchNamespace")) {
                        scratchNames.add((String) result);
                    }
                    return result;
                });
        SchemaReconciler.builder().dataSource(db.dataSource())
                .scratchDataSource(db.dataSource()).namespace(NS)
                .desiredState(DesiredState.ofScript("CREATE TABLE t DEF v"))
                .dialect(recording).build().plan();
        // target connection first (namespace named, scratch not created there), then the
        // scratch connection (no namespace, scratch creation needed), all before the lock
        assertEquals(List.of("checkAccess(" + NS + ",false)", "acquireLock",
                "checkAccess(null,true)", "executeScript(" + scratchNames.get(0) + ")"), calls);
    }
}
