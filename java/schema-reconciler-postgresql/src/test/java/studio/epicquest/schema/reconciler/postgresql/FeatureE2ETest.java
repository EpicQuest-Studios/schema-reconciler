package studio.epicquest.schema.reconciler.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.ApplyException;
import studio.epicquest.schema.reconciler.Decision;
import studio.epicquest.schema.reconciler.DesiredState;
import studio.epicquest.schema.reconciler.MultiNamespaceReconciler;
import studio.epicquest.schema.reconciler.HookPhase;
import studio.epicquest.schema.reconciler.PlanningException;
import studio.epicquest.schema.reconciler.PolicyViolationException;
import studio.epicquest.schema.reconciler.ReconcileOutcome;
import studio.epicquest.schema.reconciler.ReconcilePolicy;
import studio.epicquest.schema.reconciler.SchemaHook;
import studio.epicquest.schema.reconciler.SchemaReconciler;
import studio.epicquest.schema.reconciler.StalePlanException;
import studio.epicquest.schema.reconciler.model.ChangeAction;
import studio.epicquest.schema.reconciler.model.ChangeSafety;
import studio.epicquest.schema.reconciler.model.Definition;
import studio.epicquest.schema.reconciler.model.Difference;
import studio.epicquest.schema.reconciler.model.DifferenceType;
import studio.epicquest.schema.reconciler.model.LockLevel;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;
import studio.epicquest.schema.reconciler.model.SchemaObject;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;
import studio.epicquest.schema.reconciler.spi.RenderOptions;

/**
 * End-to-end coverage of the spec 5.5/5.6 features on a live PostgreSQL:
 * the prune policy, JSON plans and applyJson, hazard metadata, concurrent
 * index builds and rename hints — plus 3.7 multi-namespace reconcile and
 * the namespace's own nspacl/nspowner objects (A.5).
 */
class FeatureE2ETest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final String RUN_ID = Long.toHexString(System.nanoTime());

    private final DataSource ds = PgTestDatabase.dataSource();
    private final PostgreSqlDialect dialect = new PostgreSqlDialect();

    @AfterAll
    static void stopDatabase() {
        PgTestDatabase.stop();
    }

    private String namespace() throws SQLException {
        final var name = "feat_" + RUN_ID + "_" + SEQUENCE.incrementAndGet();
        PgTestDatabase.execute(ds, "CREATE SCHEMA " + dialect.quoteIdentifier(name));
        return name;
    }

    private void drop(String ns) {
        try {
            PgTestDatabase.execute(ds, "DROP SCHEMA " + dialect.quoteIdentifier(ns)
                    + " CASCADE");
        } catch (SQLException ignored) {
            // cleanup best effort
        }
    }

    private void execute(String sql) throws SQLException {
        PgTestDatabase.execute(ds, sql);
    }

    private String q(String ns, String name) {
        return dialect.quoteIdentifier(ns) + "." + dialect.quoteIdentifier(name);
    }

    private SchemaReconciler.Builder reconciler(String ns, String script) {
        return reconciler(ns, script, ReconcilePolicy.defaults());
    }

    private SchemaReconciler.Builder reconciler(String ns, String script,
                                                ReconcilePolicy policy) {
        return SchemaReconciler.builder().dataSource(ds).namespace(ns)
                .desiredState(DesiredState.ofScript(script)).dialect(dialect)
                .policy(policy);
    }

    private boolean tableExists(String ns, String name) throws SQLException {
        try (Connection c = ds.getConnection();
                var s = c.createStatement();
                var rs = s.executeQuery("SELECT to_regclass('"
                        + dialect.quoteIdentifier(ns) + "."
                        + dialect.quoteIdentifier(name) + "') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private boolean columnExists(String ns, String table, String column)
            throws SQLException {
        try (Connection c = ds.getConnection();
                var s = c.createStatement();
                var rs = s.executeQuery("SELECT EXISTS (SELECT 1 FROM pg_attribute a"
                        + " JOIN pg_class r ON r.oid = a.attrelid"
                        + " JOIN pg_namespace n ON n.oid = r.relnamespace"
                        + " WHERE n.nspname = '" + ns + "' AND r.relname = '" + table + "'"
                        + " AND a.attname = '" + column + "' AND NOT a.attisdropped)")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }


    private boolean indexValid(String ns, String name) throws SQLException {
        try (Connection c = ds.getConnection();
                var ps = c.prepareStatement("SELECT i.indisvalid FROM pg_index i"
                        + " JOIN pg_class x ON x.oid = i.indexrelid"
                        + " JOIN pg_namespace n ON n.oid = x.relnamespace"
                        + " WHERE n.nspname = ? AND x.relname = ?")) {
            ps.setString(1, ns);
            ps.setString(2, name);
            try (var rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** True when this run's session advisory lock is free; a successful try is
     *  released before returning so the probe itself leaves no lock behind. */
    private boolean namespaceLockAvailable(String ns) throws SQLException {
        final var keySql = "hashtextextended('studio.epicquest.schema.reconciler:'"
                + " || current_database() || ':' || ?, 0)";
        try (Connection c = ds.getConnection()) {
            final boolean available;
            try (var ps = c.prepareStatement(
                    "SELECT pg_try_advisory_lock(" + keySql + ")")) {
                ps.setString(1, ns);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    available = rs.getBoolean(1);
                }
            }
            if (available) {
                try (var ps = c.prepareStatement(
                        "SELECT pg_advisory_unlock(" + keySql + ")")) {
                    ps.setString(1, ns);
                    ps.execute();
                }
            }
            return available;
        }
    }

    // ---- 5.5 prune --------------------------------------------------------

    @Test
    void pruneDropsAnUnexpectedTable() throws Exception {
        final var ns = namespace();
        try {
            PgTestDatabase.execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".keep (id int); CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".stray (id int)");
            final var script = "CREATE TABLE ${namespace}.keep (id int)";
            final var policy = ReconcilePolicy.defaults().withPrune(true)
                    .withDestructive(Decision.APPLY);
            final var report = reconciler(ns, script, policy).build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertFalse(tableExists(ns, "stray"));
            assertTrue(report.applied().stream().anyMatch(c ->
                    c.action() == ChangeAction.DROP
                            && c.target().kind() == ObjectKind.TABLE
                            && c.target().name().equals("stray")
                            && c.safety() == ChangeSafety.DESTRUCTIVE));
        } finally {
            drop(ns);
        }
    }

    @Test
    void pruneRendersDropColumnForAnUnexpectedColumnOnAnExpectedTable()
            throws Exception {
        final var ns = namespace();
        try {
            PgTestDatabase.execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".t (a int, b text)");
            final var script = "CREATE TABLE ${namespace}.t (a int)";
            final var policy = ReconcilePolicy.defaults().withPrune(true)
                    .withDestructive(Decision.APPLY);
            final var report = reconciler(ns, script, policy).build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertFalse(columnExists(ns, "t", "b"));
            final var drop = report.applied().stream()
                    .filter(c -> c.action() == ChangeAction.DROP
                            && c.target().kind() == ObjectKind.COLUMN)
                    .findFirst().orElseThrow();
            assertTrue(drop.statements().get(0).contains("DROP COLUMN"),
                    drop.statements().get(0));
        } finally {
            drop(ns);
        }
    }

    @Test
    void withoutPruneAnUnexpectedTableIsOnlyReported() throws Exception {
        final var ns = namespace();
        try {
            PgTestDatabase.execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".stray (id int)");
            final var script = "CREATE TABLE ${namespace}.keep (id int)";
            final var report = reconciler(ns, script).build().reconcile();
            assertTrue(tableExists(ns, "stray"));
            assertTrue(report.unexpected().stream()
                    .anyMatch(d -> d.id().name().equals("stray")));
        } finally {
            drop(ns);
        }
    }

    // ---- machine-readable plans ---------------------------------------------

    @Test
    void toJsonRoundTripsAndApplyJsonApplies() throws Exception {
        final var ns = namespace();
        try {
            final var script = "CREATE TABLE ${namespace}.t (id int)";
            final var plan = reconciler(ns, script).build().plan();
            final var json = plan.toJson();
            final var restored = ReconcilePlan.fromJson(json);
            assertEquals(json, restored.toJson());
            final var report = reconciler(ns, script).build().applyJson(json);
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertTrue(tableExists(ns, "t"));
        } finally {
            drop(ns);
        }
    }

    @Test
    void applyJsonRejectsAStalePlan() throws Exception {
        final var ns = namespace();
        try {
            final var script = "CREATE TABLE ${namespace}.t (id int)";
            final var plan = reconciler(ns, script).build().plan();
            PgTestDatabase.execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".later (id int)");
            assertThrows(StalePlanException.class,
                    () -> reconciler(ns, script).build().applyJson(plan.toJson()));
        } finally {
            drop(ns);
        }
    }

    @Test
    void fromJsonRejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class,
                () -> ReconcilePlan.fromJson("not json"));
        assertThrows(IllegalArgumentException.class,
                () -> ReconcilePlan.fromJson("{}"));
    }

    // ---- 5.6 concurrent indexes ----------------------------------------------

    @Test
    void concurrentIndexesEmitsCreateIndexConcurrentlyNonTransactionally()
            throws Exception {
        final var ns = namespace();
        try {
            final var script = "CREATE TABLE ${namespace}.t (id int);"
                    + " CREATE INDEX i ON ${namespace}.t (id)";
            final var report = reconciler(ns, script)
                    .concurrentIndexes(true).build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            final var index = report.applied().stream()
                    .filter(c -> c.target().kind() == ObjectKind.INDEX)
                    .findFirst().orElseThrow();
            assertTrue(index.nonTransactional());
            assertEquals(LockLevel.SHARE_UPDATE_EXCLUSIVE,
                    index.hazard().lockLevel());
            assertTrue(index.statements().get(0).contains("INDEX CONCURRENTLY"),
                    index.statements().get(0));
            assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(ns, script)
                    .concurrentIndexes(true).build().reconcile().outcome());
        } finally {
            drop(ns);
        }
    }

    @Test
    void anIndexOnAPartitionedParentStaysTransactional() throws Exception {
        final var ns = namespace();
        try {
            final var script = "CREATE TABLE p (id int) PARTITION BY RANGE (id);"
                    + " CREATE TABLE p1 PARTITION OF p FOR VALUES FROM (1) TO (10);"
                    + " CREATE INDEX i ON p (id)";
            final var report = reconciler(ns, script).concurrentIndexes(true)
                    .build().reconcile();
            final var index = report.applied().stream()
                    .filter(c -> c.target().kind() == ObjectKind.INDEX)
                    .findFirst().orElseThrow();
            assertFalse(index.nonTransactional());
            assertFalse(index.statements().get(0).contains("CONCURRENTLY"));
            assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(ns, script)
                    .concurrentIndexes(true).build().reconcile().outcome());
        } finally {
            drop(ns);
        }
    }

    @Test
    void concurrentIndexesOffKeepsPlainCreateIndex() throws Exception {
        final var ns = namespace();
        try {
            final var script = "CREATE TABLE ${namespace}.t (id int);"
                    + " CREATE INDEX i ON ${namespace}.t (id)";
            final var report = reconciler(ns, script).build().reconcile();
            final var index = report.applied().stream()
                    .filter(c -> c.target().kind() == ObjectKind.INDEX)
                    .findFirst().orElseThrow();
            assertFalse(index.nonTransactional());
            assertFalse(index.statements().get(0).contains("CONCURRENTLY"));
        } finally {
            drop(ns);
        }
    }


    @Test
    void anInvalidIndexIsDetectedAsDriftedAndRebuiltConcurrently() throws Exception {
        final var ns = namespace();
        try {
            final var qNs = dialect.quoteIdentifier(ns);
            PgTestDatabase.execute(ds, "CREATE TABLE " + qNs + ".t (x int);"
                    + " INSERT INTO " + qNs + ".t VALUES (1), (1)");
            assertThrows(SQLException.class, () -> PgTestDatabase.execute(ds,
                    "CREATE UNIQUE INDEX CONCURRENTLY idx ON " + qNs + ".t (x)"));
            assertFalse(indexValid(ns, "idx"),
                    "the failed concurrent build must leave an invalid index");
            // make the unique build possible again; the invalid index itself
            // does not enforce anything
            PgTestDatabase.execute(ds, "DELETE FROM " + qNs + ".t WHERE ctid IN"
                    + " (SELECT ctid FROM " + qNs + ".t LIMIT 1)");

            final var script = "CREATE TABLE ${namespace}.t (x int);"
                    + " CREATE UNIQUE INDEX idx ON ${namespace}.t (x)";
            final var plan = reconciler(ns, script).concurrentIndexes(true)
                    .build().plan();
            final var difference = plan.differences().stream()
                    .filter(d -> d.id().kind() == ObjectKind.INDEX
                            && d.id().name().equals("idx"))
                    .findFirst().orElseThrow();
            assertEquals(DifferenceType.DRIFTED, difference.type());

            final var report = reconciler(ns, script).concurrentIndexes(true)
                    .build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertTrue(report.applied().stream().anyMatch(c ->
                    c.target().kind() == ObjectKind.INDEX
                            && c.action() == ChangeAction.DROP
                            && c.nonTransactional()));
            assertTrue(report.applied().stream().anyMatch(c ->
                    c.target().kind() == ObjectKind.INDEX
                            && c.action() == ChangeAction.CREATE
                            && c.nonTransactional()));
            assertTrue(indexValid(ns, "idx"));
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(ns, script).concurrentIndexes(true)
                            .build().reconcile().outcome());
        } finally {
            drop(ns);
        }
    }

    @Test
    void theSessionAdvisoryLockSurvivesTheConcurrentIndexCommit() throws Exception {
        final var ns = namespace();
        try {
            final var availableDuringConcurrentPhase = new AtomicBoolean(true);
            final var observed = new AtomicBoolean();
            final var script = "CREATE TABLE ${namespace}.t (id int);"
                    + " CREATE INDEX i ON ${namespace}.t (id)";
            final var report = reconciler(ns, script)
                    .concurrentIndexes(true)
                    .listener(new studio.epicquest.schema.reconciler.ReconcileListener() {
                        @Override
                        public void applying(PlannedChange change) {
                            if (change.target().kind() == ObjectKind.INDEX
                                    && change.action() == ChangeAction.CREATE
                                    && change.nonTransactional()) {
                                try {
                                    availableDuringConcurrentPhase.set(
                                            namespaceLockAvailable(ns));
                                    observed.set(true);
                                } catch (SQLException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    })
                    .build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertTrue(observed.get(), "the deferred index create was not observed");
            assertFalse(availableDuringConcurrentPhase.get(),
                    "the namespace lock must still be held after the intermediate commit");
            assertTrue(namespaceLockAvailable(ns),
                    "the namespace lock must be released when the run ends");
        } finally {
            drop(ns);
        }
    }

    // ---- 5.5.1 rename hints ---------------------------------------------------

    @Test
    void builderRenameTurnsADropCreateIntoAlterRename() throws Exception {
        final var ns = namespace();
        try {
            PgTestDatabase.execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".old (id int)");
            final var script = "CREATE TABLE ${namespace}.new (id int)";
            final var report = reconciler(ns, script)
                    .rename(new ObjectId(ObjectKind.TABLE, "old"),
                            new ObjectId(ObjectKind.TABLE, "new"))
                    .build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertTrue(tableExists(ns, "new"));
            assertFalse(tableExists(ns, "old"));
            assertTrue(report.applied().stream().anyMatch(c ->
                    c.action() == ChangeAction.ALTER
                            && c.statements().get(0).contains("RENAME TO")));
            assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(ns, script)
                    .rename(new ObjectId(ObjectKind.TABLE, "old"),
                            new ObjectId(ObjectKind.TABLE, "new"))
                    .build().reconcile().outcome());
        } finally {
            drop(ns);
        }
    }

    @Test
    void aTableRenameCarriesItsIndexAlong() throws Exception {
        final var ns = namespace();
        try {
            PgTestDatabase.execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".old (id int); CREATE INDEX i ON "
                    + dialect.quoteIdentifier(ns) + ".old (id)");
            final var script = "CREATE TABLE ${namespace}.new (id int);"
                    + " CREATE INDEX i ON ${namespace}.new (id)";
            final var report = reconciler(ns, script)
                    .rename(new ObjectId(ObjectKind.TABLE, "old"),
                            new ObjectId(ObjectKind.TABLE, "new"))
                    .build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            // the carried index needed no create of its own — a
            // CREATE INDEX i would collide with the renamed-away index
            assertTrue(report.applied().stream().noneMatch(c ->
                    c.action() == ChangeAction.CREATE
                            && c.target().kind() == ObjectKind.INDEX));
        } finally {
            drop(ns);
        }
    }

    @Test
    void theScriptRenameDirectiveIsPickedUp() throws Exception {
        final var ns = namespace();
        try {
            PgTestDatabase.execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".old (id int)");
            final var script = "-- @reconciler rename TABLE old -> new\n"
                    + "CREATE TABLE ${namespace}.new (id int)";
            final var report = reconciler(ns, script).build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertTrue(tableExists(ns, "new"));
        } finally {
            drop(ns);
        }
    }

    @Test
    void aRenameWithDriftConvergesThroughTheFollowUpChange() throws Exception {
        final var ns = namespace();
        try {
            PgTestDatabase.execute(ds, "CREATE VIEW " + dialect.quoteIdentifier(ns)
                    + ".old AS SELECT 1 AS one");
            final var script = "CREATE VIEW ${namespace}.new AS SELECT 2 AS two";
            final var report = reconciler(ns, script)
                    .rename(new ObjectId(ObjectKind.VIEW, "old"),
                            new ObjectId(ObjectKind.VIEW, "new"))
                    .build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(ns, script)
                    .rename(new ObjectId(ObjectKind.VIEW, "old"),
                            new ObjectId(ObjectKind.VIEW, "new"))
                    .build().reconcile().outcome());
        } finally {
            drop(ns);
        }
    }

    @Test
    void bothSidesPresentIsAPlanningException() throws Exception {
        final var ns = namespace();
        try {
            PgTestDatabase.execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".old (id int); CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".new (id int, x int)");
            final var script = "CREATE TABLE ${namespace}.new (id int, x int)";
            assertThrows(PlanningException.class,
                    () -> reconciler(ns, script)
                            .rename(new ObjectId(ObjectKind.TABLE, "old"),
                                    new ObjectId(ObjectKind.TABLE, "new"))
                            .build().reconcile());
        } finally {
            drop(ns);
        }
    }

    // ---- 3.7 multi-namespace --------------------------------------------------

    @Test
    void multiNamespaceReconcilesTwoSchemasInOrderWithACrossSchemaFk()
            throws Exception {
        final var nsB = namespace();
        final var nsA = namespace();
        try {
            final var scriptB = "CREATE TABLE ${namespace}.customers"
                    + " (id int PRIMARY KEY)";
            // nsA.orders references nsB.customers — a foreign-schema
            // REFERENCES is a legitimate external dependency (5.8.3)
            final var scriptA = "CREATE TABLE ${namespace}.orders"
                    + " (id int PRIMARY KEY, cid int REFERENCES "
                    + dialect.quoteIdentifier(nsB) + ".customers(id))";
            final var multi = MultiNamespaceReconciler.builder()
                    .dataSource(ds).dialect(dialect)
                    .namespace(nsB, DesiredState.ofScript(scriptB))
                    .namespace(nsA, DesiredState.ofScript(scriptA))
                    .build();
            final var report = multi.reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertEquals(List.of(nsB, nsA),
                    List.copyOf(report.reports().keySet()));
            assertEquals(ReconcileOutcome.RECONCILED,
                    report.reports().get(nsB).outcome());
            assertEquals(ReconcileOutcome.RECONCILED,
                    report.reports().get(nsA).outcome());
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    multi.reconcile().outcome());

            final var plans = multi.plan();
            assertEquals(List.of(nsB, nsA), List.copyOf(plans.keySet()));
            assertTrue(plans.get(nsB).changes().isEmpty());
            assertTrue(plans.get(nsA).changes().isEmpty());

            final var dumps = multi.dump();
            assertEquals(List.of(nsB, nsA), List.copyOf(dumps.keySet()));
            assertTrue(dumps.get(nsB).contains(
                    "CREATE TABLE ${namespace}.\"customers\""),
                    dumps.get(nsB));
            assertTrue(dumps.get(nsA).contains(
                    "CREATE TABLE ${namespace}.\"orders\""),
                    dumps.get(nsA));
            // the foreign-schema reference stays literal — only the
            // reconcile unit's own namespace is neutralised
            assertTrue(dumps.get(nsA).contains(nsB + "."), dumps.get(nsA));
        } finally {
            drop(nsA);
            drop(nsB);
        }
    }

    // ---- A.5: the namespace's own acl and owner --------------------------------

    @Test
    void schemaGrantsRoundTripThroughNspacl() throws Exception {
        final var ns = namespace();
        try {
            // the default acl already grants PUBLIC USAGE, so the script
            // also declares CREATE to land a real acl row
            final var script = "GRANT USAGE, CREATE ON SCHEMA ${namespace}"
                    + " TO PUBLIC";
            final var report = reconciler(ns, script).build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(ns, script).build().reconcile().outcome());

            final var dump = SchemaReconciler.builder().dataSource(ds)
                    .namespace(ns).dialect(dialect).build().dump();
            assertTrue(dump.contains(
                    "GRANT CREATE ON SCHEMA ${namespace} TO PUBLIC;"),
                    dump);
            assertTrue(dump.contains(
                    "GRANT USAGE ON SCHEMA ${namespace} TO PUBLIC;"),
                    dump);
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(ns, dump).build().reconcile().outcome());
        } finally {
            drop(ns);
        }
    }

    @Test
    void aSchemaGrantToANamedRoleLoadsAsTheNamespacePrivilegesObject()
            throws Exception {
        final var role = "feat_grant_" + RUN_ID;
        try (Connection c = ds.getConnection(); final var s = c.createStatement()) {
            final var rs = s.executeQuery(
                    "SELECT rolsuper OR rolcreaterole FROM pg_roles"
                            + " WHERE rolname = current_user");
            assumeTrue(rs.next() && rs.getBoolean(1),
                    "connecting role cannot create a grantee role");
            s.execute("DROP ROLE IF EXISTS " + dialect.quoteIdentifier(role));
            s.execute("CREATE ROLE " + dialect.quoteIdentifier(role));
        }
        final var ns = namespace();
        try {
            final var script = "GRANT USAGE ON SCHEMA ${namespace} TO "
                    + dialect.quoteIdentifier(role);
            final var report = reconciler(ns, script).build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertTrue(report.applied().stream().anyMatch(ch ->
                    ch.target().kind() == ObjectKind.PRIVILEGES
                            && ch.target().name().equals("<namespace>")));
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(ns, script).build().reconcile().outcome());
        } finally {
            drop(ns);
            PgTestDatabase.execute(ds,
                    "DROP ROLE " + dialect.quoteIdentifier(role));
        }
    }

    @Test
    void alterSchemaOwnerToRoundTripsThroughNspowner() throws Exception {
        final var role = "feat_owner_" + RUN_ID;
        try (Connection c = ds.getConnection(); final var s = c.createStatement()) {
            final var rs = s.executeQuery(
                    "SELECT rolsuper OR rolcreaterole FROM pg_roles"
                            + " WHERE rolname = current_user");
            assumeTrue(rs.next() && rs.getBoolean(1),
                    "connecting role cannot create a target owner role");
            String user;
            try (var u = s.executeQuery("SELECT current_user")) {
                u.next();
                user = u.getString(1);
            }
            s.execute("DROP ROLE IF EXISTS " + dialect.quoteIdentifier(role));
            s.execute("CREATE ROLE " + dialect.quoteIdentifier(role));
            // ALTER ... OWNER TO needs membership in the target role
            s.execute("GRANT " + dialect.quoteIdentifier(role) + " TO "
                    + dialect.quoteIdentifier(user));
        }
        final var ns = namespace();
        try {
            final var script = "ALTER SCHEMA ${namespace} OWNER TO "
                    + dialect.quoteIdentifier(role);
            final var report = reconciler(ns, script).build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertTrue(report.applied().stream().anyMatch(ch ->
                    ch.target().kind() == ObjectKind.OWNERSHIP
                            && ch.target().name().equals("<namespace>")
                            && ch.statements().get(0).contains("OWNER TO")));
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(ns, script).build().reconcile().outcome());
        } finally {
            drop(ns);
            PgTestDatabase.execute(ds,
                    "DROP OWNED BY " + dialect.quoteIdentifier(role)
                            + "; DROP ROLE " + dialect.quoteIdentifier(role));
        }
    }

    @Test
    void aTableGrantToAQuotedCommaContainingRoleRoundTrips() throws Exception {
        final var role = "rcl,comma_feat_" + RUN_ID;
        var created = false;
        try (Connection c = ds.getConnection(); final var s = c.createStatement()) {
            final var rs = s.executeQuery(
                    "SELECT rolsuper OR rolcreaterole FROM pg_roles"
                            + " WHERE rolname = current_user");
            assumeTrue(rs.next() && rs.getBoolean(1),
                    "connecting role cannot create the grantee role");
            s.execute("DROP ROLE IF EXISTS " + dialect.quoteIdentifier(role));
            s.execute("CREATE ROLE " + dialect.quoteIdentifier(role));
            created = true;
        }
        final var ns = namespace();
        try {
            final var script = "CREATE TABLE ${namespace}.acl_t (id int);"
                    + " GRANT SELECT ON ${namespace}.acl_t TO "
                    + dialect.quoteIdentifier(role);
            assertEquals(ReconcileOutcome.RECONCILED,
                    reconciler(ns, script).build().reconcile().outcome());
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(ns, script).build().reconcile().outcome());

            final var dump = SchemaReconciler.builder().dataSource(ds)
                    .namespace(ns).dialect(dialect).build().dump();
            assertTrue(dump.contains("GRANT SELECT"), dump);
            assertTrue(dump.contains(dialect.quoteIdentifier(role)), dump);
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(ns, dump).build().reconcile().outcome());
        } finally {
            drop(ns);
            if (created) {
                PgTestDatabase.execute(ds,
                        "DROP ROLE " + dialect.quoteIdentifier(role));
            }
        }
    }

    @Test
    void managedPartitionedIndexesReconcileStayTransactionalDumpAndRename() throws Exception {
        final var ns = namespace();
        try {
            final var script = "CREATE FUNCTION usage_day(timestamptz) RETURNS date"
                    + " LANGUAGE sql IMMUTABLE AS $$ SELECT $1::date $$;"
                    + " CREATE TABLE usage_records (tenant_account_id bigint,"
                    + " created_at timestamptz NOT NULL,"
                    + " CONSTRAINT usage_records_pkey PRIMARY KEY (created_at, tenant_account_id),"
                    + " CONSTRAINT usage_records_tenant_key UNIQUE (tenant_account_id, created_at))"
                    + " PARTITION BY RANGE (created_at);"
                    + " CREATE TABLE usage_records_p2026_11 PARTITION OF usage_records"
                    + " FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');"
                    + " CREATE INDEX usage_records_tenant_idx ON usage_records (tenant_account_id)"
                    + " WHERE tenant_account_id IS NOT NULL;"
                    + " CREATE UNIQUE INDEX usage_records_tenant_created_uq"
                    + " ON usage_records (tenant_account_id, created_at);"
                    + " CREATE INDEX usage_records_day_idx ON usage_records (usage_day(created_at));"
                    + " COMMENT ON INDEX usage_records_tenant_idx IS 'tenant index'";
            final var first = reconciler(ns, script).concurrentIndexes(true)
                    .build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, first.outcome());
            final var indexCreates = first.applied().stream()
                    .filter(c -> c.target().kind() == ObjectKind.INDEX).toList();
            assertTrue(indexCreates.size() >= 2, first.applied().toString());
            assertTrue(indexCreates.stream().allMatch(c -> !c.nonTransactional()
                    && c.statements().stream().noneMatch(sql -> sql.contains("CONCURRENTLY"))));
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(ns, script).build().reconcile().outcome());
            final var dump = SchemaReconciler.builder().dataSource(ds).namespace(ns)
                    .dialect(dialect).build().dump();
            assertTrue(dump.contains("CREATE INDEX usage_records_tenant_idx ON ${namespace}.usage_records"), dump);
            assertFalse(dump.contains("ON ONLY"), dump);

            final var renamed = script.replace("usage_records_tenant_idx",
                    "usage_records_tenant_new_idx")
                    + "\n-- @reconciler rename INDEX usage_records.usage_records_tenant_idx -> usage_records_tenant_new_idx";
            final var rename = reconciler(ns, renamed).build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, rename.outcome());
            assertTrue(rename.applied().stream().anyMatch(c ->
                    c.action() == ChangeAction.ALTER
                            && c.target().kind() == ObjectKind.INDEX
                            && c.target().name().equals("usage_records_tenant_new_idx")),
                    rename.applied().toString());
            assertTrue(tableExists(ns, "usage_records_tenant_new_idx"));
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(ns, renamed).build().reconcile().outcome());
        } finally {
            drop(ns);
        }
    }

    @Test
    void anInvalidOnOnlyPartitionedIndexRecoversToValid() throws Exception {
        final var ns = namespace();
        try {
            final var base = "CREATE TABLE usage_records (tenant_account_id bigint,"
                    + " created_at timestamptz NOT NULL) PARTITION BY RANGE (created_at);"
                    + " CREATE TABLE usage_records_p2026_11 PARTITION OF usage_records"
                    + " FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');";
            final var invalid = base + " CREATE INDEX usage_records_tenant_idx"
                    + " ON ONLY usage_records (tenant_account_id)";
            assertEquals(ReconcileOutcome.RECONCILED,
                    reconciler(ns, invalid).build().reconcile().outcome());
            assertFalse(indexValid(ns, "usage_records_tenant_idx"));
            final var valid = base + " CREATE INDEX usage_records_tenant_idx"
                    + " ON usage_records (tenant_account_id)";
            assertEquals(ReconcileOutcome.RECONCILED,
                    reconciler(ns, valid).build().reconcile().outcome());
            assertTrue(indexValid(ns, "usage_records_tenant_idx"));
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(ns, valid).build().reconcile().outcome());
        } finally {
            drop(ns);
        }
    }

    @Test
    void externalPartitionsCoexistThroughReconcilePlanApplyDumpAndPrune() throws Exception {
        final var ns = namespace();
        try {
            final var base = "CREATE TABLE usage_records (tenant_account_id bigint,"
                    + " created_at timestamptz NOT NULL) PARTITION BY RANGE (created_at);"
                    + " CREATE TABLE usage_records_default PARTITION OF usage_records DEFAULT";
            final var policy = ReconcilePolicy.defaults()
                    .withDestructive(Decision.APPLY).withPrune(true);
            final var builder = reconciler(ns, base, policy)
                    .externalPartitions("usage_records", "usage_records_p");
            assertEquals(ReconcileOutcome.RECONCILED, builder.build().reconcile().outcome());
            final var qNs = dialect.quoteIdentifier(ns);
            PgTestDatabase.execute(ds, "CREATE TABLE " + qNs
                    + ".usage_records_p2026_11 PARTITION OF " + qNs
                    + ".usage_records FOR VALUES FROM ('2026-11-01') TO ('2026-12-01')");
            final var upToDate = reconciler(ns, base, policy)
                    .externalPartitions("usage_records", "usage_records_p").build().reconcile();
            assertEquals(ReconcileOutcome.UP_TO_DATE, upToDate.outcome());
            assertTrue(upToDate.unexpected().isEmpty(), upToDate.unexpected().toString());

            final var withIndex = base + "; CREATE INDEX usage_records_tenant_idx"
                    + " ON usage_records (tenant_account_id)";
            final var plan = reconciler(ns, withIndex, policy)
                    .externalPartitions("usage_records", "usage_records_p").build().plan();
            PgTestDatabase.execute(ds, "CREATE TABLE " + qNs
                    + ".usage_records_p2026_12 PARTITION OF " + qNs
                    + ".usage_records FOR VALUES FROM ('2026-12-01') TO ('2027-01-01')");
            assertEquals(ReconcileOutcome.RECONCILED, reconciler(ns, withIndex, policy)
                    .externalPartitions("usage_records", "usage_records_p")
                    .build().apply(plan).outcome());
            final var dump = SchemaReconciler.builder().dataSource(ds).namespace(ns)
                    .dialect(dialect).externalPartitions("usage_records", "usage_records_p")
                    .build().dump();
            assertFalse(dump.contains("usage_records_p2026_"), dump);
            assertTrue(dump.contains("usage_records_default"), dump);
        } finally {
            drop(ns);
        }
    }

    @Test
    void retireDropsTargetedConstraintAndPruneFilterProtectsMigrationTableTree() throws Exception {
        final var ns = namespace();
        try {
            final var qNs = dialect.quoteIdentifier(ns);
            PgTestDatabase.execute(ds, "CREATE TABLE " + qNs + ".router_models"
                    + " (id bigint PRIMARY KEY, slug text NOT NULL, tenant_id bigint NOT NULL);"
                    + " ALTER TABLE " + qNs + ".router_models"
                    + " ADD CONSTRAINT router_models_slug_key UNIQUE (slug);"
                    + " CREATE TABLE " + qNs + ".goose_db_version"
                    + " (id bigserial PRIMARY KEY, version bigint NOT NULL)");
            final var script = "CREATE TABLE router_models (id bigint PRIMARY KEY,"
                    + " slug text NOT NULL, tenant_id bigint NOT NULL,"
                    + " CONSTRAINT router_models_global_slug_uq UNIQUE (slug),"
                    + " CONSTRAINT router_models_tenant_slug_uq UNIQUE (tenant_id, slug));"
                    + "\n-- @reconciler retire UNIQUE router_models.router_models_slug_key";
            final var report = reconciler(ns, script).build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertTrue(report.applied().stream().anyMatch(c ->
                    c.action() == ChangeAction.DROP
                            && c.target().kind() == ObjectKind.UNIQUE
                            && c.target().name().equals("router_models_slug_key")
                            && c.reason().contains("retired")), report.applied().toString());
            assertTrue(tableExists(ns, "goose_db_version"));
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(ns, script).build().reconcile().outcome());

            PgTestDatabase.execute(ds, "ALTER TABLE " + qNs + ".router_models"
                    + " ADD CONSTRAINT router_models_obsolete_uq UNIQUE (id, slug)");
            final var prunePolicy = ReconcilePolicy.defaults()
                    .withDestructive(Decision.APPLY).withPrune(true);
            final var prune = reconciler(ns, script, prunePolicy)
                    .pruneFilter(id -> !id.name().equals("goose_db_version"))
                    .build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, prune.outcome());
            assertTrue(tableExists(ns, "goose_db_version"));
            assertTrue(tableExists(ns, "goose_db_version_id_seq"));
            assertTrue(prune.unexpected().stream().anyMatch(d ->
                    d.id().name().equals("goose_db_version")),
                    "protected objects remain visible as unexpected");
        } finally {
            drop(ns);
        }
    }

    @Test
    void schemaHooksOrderBackfillAtomicallyRollBackAndRunOnce() throws Exception {
        final var ns = namespace();
        try {
            final var qNs = dialect.quoteIdentifier(ns);
            PgTestDatabase.execute(ds, "CREATE TABLE " + qNs + ".accounts"
                    + " (id bigint PRIMARY KEY, slug text);"
                    + " INSERT INTO " + qNs + ".accounts (id, slug) VALUES (1, NULL)");
            final var events = new java.util.ArrayList<String>();
            final var store = PgHookStore.inSchema(ns);
            final var script = "CREATE TABLE accounts (id bigint PRIMARY KEY, slug text NOT NULL);"
                    + " CREATE TABLE hook_audit (id int)";
            final var hooked = reconciler(ns, script,
                    ReconcilePolicy.defaults().withDestructive(Decision.APPLY))
                    .hookStore(store)
                    .hook(SchemaHook.of("z-post", HookPhase.POST_COMMIT, 2,
                            ctx -> events.add("z:" + ctx.applied().size())))
                    .hook(SchemaHook.of("a-pre", HookPhase.PRE_APPLY, 1, ctx -> {
                        events.add("a");
                        ctx.connection().createStatement().execute(
                                "UPDATE accounts SET slug = 'x' WHERE slug IS NULL");
                    }))
                    .hook(SchemaHook.runOnce("m-once", HookPhase.POST_APPLY, 0, "v1", ctx -> {
                        events.add("m");
                        ctx.connection().createStatement().execute(
                                "INSERT INTO hook_audit (id) VALUES (1)");
                    }))
                    .build();
            assertEquals(ReconcileOutcome.RECONCILED, hooked.reconcile().outcome());
            assertEquals(List.of("a", "m", "z"), events.stream().map(e -> e.substring(0, 1)).toList());
            final var eventCount = events.size();
            assertEquals(ReconcileOutcome.UP_TO_DATE, hooked.reconcile().outcome());
            assertEquals(eventCount + 2, events.size(),
                    "run-once hook skips but always-run hooks still run on an up-to-date apply");
            assertEquals(1, events.stream().filter(e -> e.startsWith("m")).count());
            assertTrue(tableExists(ns, "schema_reconciler_hooks"));

            final var changed = reconciler(ns, script).hookStore(store)
                    .hook(SchemaHook.runOnce("m-once", HookPhase.POST_APPLY, "v2",
                            ctx -> { }))
                    .build();
            assertThrows(ApplyException.class, changed::reconcile);

            final var failing = reconciler(ns, "CREATE TABLE rollback_probe (id int)")
                    .hook(SchemaHook.of("fail", HookPhase.POST_APPLY, ctx -> {
                        ctx.connection().createStatement().execute(
                                "INSERT INTO rollback_probe (id) VALUES (1)");
                        throw new SQLException("boom");
                    }))
                    .build();
            assertThrows(ApplyException.class, failing::reconcile);
            assertFalse(tableExists(ns, "rollback_probe"),
                    "POST_APPLY failure rolls back the DDL transaction");
        } finally {
            drop(ns);
        }
    }

    @Test
    void externalPartitionsRemainVisibleWhenTheirParentIsPruned() throws Exception {
        final var ns = namespace();
        try {
            execute("CREATE TABLE " + q(ns, "usage_records")
                    + " (id bigint, created_at timestamptz) PARTITION BY RANGE (created_at);"
                    + " CREATE TABLE " + q(ns, "usage_records_p2026_11") + " PARTITION OF "
                    + q(ns, "usage_records") + " FOR VALUES FROM ('2026-11-01') TO ('2026-12-01')");
            final var report = reconciler(ns, "", ReconcilePolicy.defaults()
                    .withPrune(true).withDestructive(Decision.APPLY))
                    .externalPartitions("usage_records", "usage_records_p").build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            final var drops = report.applied().stream()
                    .filter(c -> c.action() == ChangeAction.DROP).toList();
            assertTrue(drops.stream().anyMatch(c -> c.target().kind() == ObjectKind.PARTITION
                    && c.target().name().equals("usage_records_p2026_11")), drops.toString());
            assertTrue(drops.stream().anyMatch(c -> c.target().kind() == ObjectKind.TABLE
                    && c.target().name().equals("usage_records")));
        } finally {
            drop(ns);
        }
    }

    @Test
    void retireEdgeCasesCoverColumnsDestructivePolicyAndDependents() throws Exception {
        final var ns = namespace();
        try {
            execute("CREATE TABLE " + q(ns, "t") + " (id int, old text)");
            final var column = reconciler(ns, "CREATE TABLE t (id int)",
                    ReconcilePolicy.defaults().withDestructive(Decision.APPLY))
                    .retire(new ObjectId(ObjectKind.COLUMN, "old", "t")).build().reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, column.outcome());
            assertTrue(column.applied().stream().anyMatch(c ->
                    c.target().kind() == ObjectKind.COLUMN && c.safety() == ChangeSafety.DESTRUCTIVE
                            && c.statements().stream().anyMatch(sql -> sql.contains("DROP COLUMN"))));
            assertFalse(columnExists(ns, "t", "old"));

            execute("CREATE TABLE " + q(ns, "victim") + " (id int)");
            assertThrows(PolicyViolationException.class, () -> reconciler(ns, "CREATE TABLE t (id int)")
                    .retire(new ObjectId(ObjectKind.TABLE, "victim")).build().reconcile());
            execute("CREATE VIEW " + q(ns, "dependent") + " AS SELECT id FROM " + q(ns, "victim"));
            assertThrows(PlanningException.class, () -> reconciler(ns, "CREATE TABLE t (id int)",
                    ReconcilePolicy.defaults().withDestructive(Decision.APPLY))
                    .retire(new ObjectId(ObjectKind.TABLE, "victim")).build().reconcile());
        } finally {
            drop(ns);
        }
    }

    @Test
    void hooksSkipReportedOnlyAndPostCommitFailuresCarryAppliedChanges() throws Exception {
        final var ns = namespace();
        try {
            final var events = new java.util.ArrayList<String>();
            final var reported = SchemaReconciler.builder().dataSource(ds).namespace(ns)
                    .desiredState(DesiredState.ofScript("CREATE TABLE later (id int)"))
                    .dialect(dialect).policy(ReconcilePolicy.reportOnly())
                    .hook(SchemaHook.of("pre", HookPhase.PRE_APPLY, ctx -> events.add("pre")))
                    .hook(SchemaHook.of("post", HookPhase.POST_APPLY, ctx -> events.add("post")))
                    .hook(SchemaHook.of("commit", HookPhase.POST_COMMIT, ctx -> events.add("commit")))
                    .build();
            assertEquals(ReconcileOutcome.REPORTED_ONLY, reported.reconcile().outcome());
            assertEquals(List.of(), events);

            final var failing = SchemaReconciler.builder().dataSource(ds).namespace(ns)
                    .desiredState(DesiredState.ofScript("CREATE TABLE later (id int)"))
                    .dialect(dialect)
                    .hook(SchemaHook.of("boom", HookPhase.POST_COMMIT,
                            ctx -> { throw new SQLException("boom"); }))
                    .build();
            final var error = assertThrows(ApplyException.class, failing::reconcile);
            assertTrue(error.applied().stream().anyMatch(c -> c.target().name().equals("later")),
                    "post-commit failure carries prior applied changes");
        } finally {
            drop(ns);
        }
    }

    @Test
    void pgHookStoreDedicatedSchemaAndSubPartitionIndexTransactional() throws Exception {
        final var ns = namespace();
        final var internal = "_reconciler_internal_" + RUN_ID + "_" + SEQUENCE.incrementAndGet();
        execute("CREATE SCHEMA " + dialect.quoteIdentifier(internal));
        try {
            final var runs = new AtomicLong();
            final var r = reconciler(ns, "CREATE TABLE hooked (id int)")
                    .hookStore(PgHookStore.inSchema(internal))
                    .hook(SchemaHook.runOnce("once", HookPhase.POST_COMMIT, "v1",
                            ctx -> runs.incrementAndGet()))
                    .build();
            assertEquals(ReconcileOutcome.RECONCILED, r.reconcile().outcome());
            assertEquals(ReconcileOutcome.UP_TO_DATE, r.reconcile().outcome());
            assertEquals(1, runs.get());

            final var sub = "CREATE TABLE measurement (city_id int, logdate date) PARTITION BY RANGE (logdate);"
                    + " CREATE TABLE measurement_2026 PARTITION OF measurement"
                    + " FOR VALUES FROM ('2026-01-01') TO ('2027-01-01') PARTITION BY LIST (city_id);"
                    + " CREATE TABLE measurement_2026_x PARTITION OF measurement_2026 FOR VALUES IN (1);"
                    + " CREATE INDEX measurement_city_idx ON measurement_2026 (city_id)";
            final var applied = reconciler(ns, sub).concurrentIndexes(true).build().reconcile();
            final var indexes = applied.applied().stream().filter(c ->
                    c.target().kind() == ObjectKind.INDEX
                            && c.target().name().equals("measurement_city_idx")).toList();
            assertFalse(indexes.isEmpty());
            assertTrue(indexes.stream().noneMatch(PlannedChange::nonTransactional));
        } finally {
            drop(ns);
            drop(internal);
        }
    }

    @Test
    void multiNamespaceForwards011Options() throws Exception {
        final var ns = namespace();
        try {
            execute("CREATE TABLE " + q(ns, "usage_records")
                    + " (id bigint, created_at timestamptz) PARTITION BY RANGE (created_at);"
                    + " CREATE TABLE " + q(ns, "usage_records_p2026_11") + " PARTITION OF "
                    + q(ns, "usage_records") + " FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');"
                    + " CREATE TABLE " + q(ns, "goose_db_version") + " (id int)");
            final var runs = new AtomicLong();
            final var multi = MultiNamespaceReconciler.builder().dataSource(ds).dialect(dialect)
                    .policy(ReconcilePolicy.defaults().withPrune(true).withDestructive(Decision.APPLY))
                    .externalPartitions("usage_records", "usage_records_p")
                    .pruneFilter(id -> !id.name().equals("goose_db_version"))
                    .hookStore(PgHookStore.inSchema(ns))
                    .namespace(ns, DesiredState.ofScript(
                            "CREATE TABLE usage_records (id bigint, created_at timestamptz)"
                                    + " PARTITION BY RANGE (created_at)"),
                            b -> b.hook(SchemaHook.runOnce("once", HookPhase.POST_COMMIT, "v1",
                                    ctx -> runs.incrementAndGet())))
                    .build();
            final var report = multi.reconcile();
            assertEquals(ReconcileOutcome.UP_TO_DATE, report.reports().get(ns).outcome());
            assertTrue(tableExists(ns, "goose_db_version"));
            assertEquals(1, runs.get());
            assertTrue(tableExists(ns, "schema_reconciler_hooks"));
        } finally {
            drop(ns);
        }
    }



}
