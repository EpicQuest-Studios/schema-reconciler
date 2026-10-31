package studio.epicquest.schema.reconciler.internal;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import javax.sql.DataSource;
import studio.epicquest.schema.reconciler.ApplyException;
import studio.epicquest.schema.reconciler.ConfigurationException;
import studio.epicquest.schema.reconciler.Decision;
import studio.epicquest.schema.reconciler.DesiredState;
import studio.epicquest.schema.reconciler.DesiredStateException;
import studio.epicquest.schema.reconciler.LockTimeoutException;
import studio.epicquest.schema.reconciler.PlanningException;
import studio.epicquest.schema.reconciler.ReconcileListener;
import studio.epicquest.schema.reconciler.ReconcileOutcome;
import studio.epicquest.schema.reconciler.ReconcilePolicy;
import studio.epicquest.schema.reconciler.ReconcileReport;
import studio.epicquest.schema.reconciler.RunTimings;
import studio.epicquest.schema.reconciler.SchemaReconcilerException;
import studio.epicquest.schema.reconciler.StalePlanException;
import studio.epicquest.schema.reconciler.TimedPlan;
import studio.epicquest.schema.reconciler.VerificationException;
import studio.epicquest.schema.reconciler.model.Definition;
import studio.epicquest.schema.reconciler.model.Difference;
import studio.epicquest.schema.reconciler.model.DifferenceType;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;
import studio.epicquest.schema.reconciler.spi.Dialect;

/**
 * The shared step machine of spec 5.1 behind {@code plan()}, {@code reconcile()}
 * and {@code apply(plan)}.
 */
public final class Engine {

    private static final System.Logger LOG =
            System.getLogger("studio.epicquest.schema.reconciler");

    /**
     * Weak, identity-compared key: a plan is hashed and compared by reference
     * (never by its record contents) and its metadata is dropped once the
     * plan is garbage collected.
     */
    private static final class IdentityKey extends WeakReference<ReconcilePlan> {
        private final int hash;

        IdentityKey(ReconcilePlan plan, ReferenceQueue<ReconcilePlan> queue) {
            super(plan, queue);
            this.hash = System.identityHashCode(plan);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || (other instanceof IdentityKey key
                    && get() != null && get() == key.get());
        }
    }

    private void putMeta(ReconcilePlan plan, PlanMeta meta) {
        synchronized (planMeta) {
            purgeCollected();
            planMeta.put(new IdentityKey(plan, collectedPlans), meta);
        }
    }

    private PlanMeta metaOf(ReconcilePlan plan) {
        synchronized (planMeta) {
            purgeCollected();
            return planMeta.get(new IdentityKey(plan, null));
        }
    }

    private void purgeCollected() {
        for (var ref = collectedPlans.poll(); ref != null; ref = collectedPlans.poll()) {
            planMeta.remove(ref);
        }
    }

    /** Metadata retained per issued plan for {@code apply()}. */
    private record PlanMeta(SchemaSnapshot desired,
                            Map<ObjectId, Map<ObjectId, Definition>> coveredByParent) { }

    private final DataSource dataSource;
    private final DataSource scratchDataSource;
    private final String namespace;
    private final DesiredState desiredState;
    private final ReconcilePolicy policy;
    private final Duration lockTimeout;
    private final Duration statementTimeout;
    private final ReconcileListener listener;
    private final Predicate<ObjectId> objectFilter;
    private final DialectResolver resolver;
    private final Map<IdentityKey, PlanMeta> planMeta = new HashMap<>();
    private final ReferenceQueue<ReconcilePlan> collectedPlans = new ReferenceQueue<>();

    public Engine(DataSource dataSource, DataSource scratchDataSource, String namespace,
                  DesiredState desiredState, Dialect dialect, ReconcilePolicy policy,
                  Duration lockTimeout, Duration statementTimeout,
                  ReconcileListener listener, Predicate<ObjectId> objectFilter) {
        this.dataSource = dataSource;
        this.scratchDataSource = scratchDataSource;
        this.namespace = namespace;
        this.desiredState = desiredState;
        this.policy = policy;
        this.lockTimeout = lockTimeout;
        this.statementTimeout = statementTimeout;
        this.listener = listener;
        this.objectFilter = objectFilter;
        this.resolver = new DialectResolver(dialect);
    }

    /** Spec 5.1 plan(): steps 1–5, returning the plan without evaluating policy or applying. */
    public ReconcilePlan plan() {
        requireDesiredState("plan");
        return planTimed().plan();
    }

    /** {@link #plan()} with the phase timings of the planning run. */
    public TimedPlan planTimed() {
        requireDesiredState("planTimed");
        final var clock = new PhaseClock();
        try (Session session = open()) {
            var t = clock.now();
            try {
                session.lock();
            } finally {
                clock.lockWait = clock.since(t);
            }
            t = clock.now();
            final SchemaSnapshot actual;
            try {
                actual = session.readActual();
            } finally {
                clock.readActual = clock.since(t);
            }
            t = clock.now();
            final SchemaSnapshot desired;
            try {
                desired = loadDesired(session);
            } finally {
                clock.loadDesired = clock.since(t);
            }
            t = clock.now();
            final PlanBuilder.Built built;
            try {
                built = buildPlan(session, actual, desired);
            } finally {
                clock.plan = clock.since(t);
            }
            // nothing is applied by plan(), so a listener failure is a planning failure
            try {
                listener.planned(built.plan());
            } catch (RuntimeException e) {
                throw new PlanningException(
                        "namespace '" + namespace + "': listener aborted planning", e);
            }
            putMeta(built.plan(), new PlanMeta(desired, built.coveredByParent()));
            return new TimedPlan(built.plan(), clock.snapshot());
        } catch (SchemaReconcilerException e) {
            throw e.withTimings(clock.snapshot());
        }
    }

    /** Spec 5.1 reconcile(): the full sequence. */
    public ReconcileReport reconcile() {
        requireDesiredState("reconcile");
        final var start = System.nanoTime();
        final var clock = new PhaseClock();
        try (Session session = open()) {
            var t = clock.now();
            try {
                session.lock();
            } finally {
                clock.lockWait = clock.since(t);
            }
            t = clock.now();
            final SchemaSnapshot actual;
            try {
                actual = session.readActual();
            } finally {
                clock.readActual = clock.since(t);
            }
            t = clock.now();
            final SchemaSnapshot desired;
            try {
                desired = loadDesired(session);
            } finally {
                clock.loadDesired = clock.since(t);
            }
            t = clock.now();
            final PlanBuilder.Built built;
            try {
                built = buildPlan(session, actual, desired);
            } finally {
                clock.plan = clock.since(t);
            }
            notifyPlanned(built.plan(), List.of());
            PolicyEvaluator.Evaluation evaluation =
                    new PolicyEvaluator(session.dialect, policy)
                            .evaluate(built.plan(), actual, desired);
            final var outcome = applyAndVerify(session, built.plan(), evaluation, desired,
                    built.coveredByParent(), clock);
            session.commit();
            final var unexpected = unexpected(built.plan());
            final var unsupported = unsupported(built.plan());
            logUnexpectedAndUnsupported(unexpected, unsupported);
            return new ReconcileReport(outcome.outcome(), built.plan(), outcome.applied(),
                    outcome.reported(), unexpected, unsupported,
                    Duration.ofNanos(System.nanoTime() - start), clock.snapshot());
        } catch (SchemaReconcilerException e) {
            throw e.withTimings(clock.snapshot());
        }
    }

    /** Spec 5.1 apply(): the same sequence, driven by a previously issued plan. */
    public ReconcileReport apply(ReconcilePlan plan) {
        requireDesiredState("apply");
        final var start = System.nanoTime();
        final var clock = new PhaseClock();
        // reject a foreign plan before touching the database: a namespace or
        // dialect mismatch needs no connection to diagnose (5.7)
        final var fixed = resolver.peek();
        if (!namespace.equals(plan.targetNamespace())
                || (fixed != null && !fixed.id().equals(plan.dialectId()))) {
            throw new IllegalArgumentException("plan targets namespace '"
                    + plan.targetNamespace() + "' on dialect '" + plan.dialectId()
                    + "', this reconciler is bound to '" + namespace
                    + (fixed != null ? "' on '" + fixed.id() + "'" : "'"));
        }
        try (Session session = open()) {
            var t = clock.now();
            try {
                session.lock();
            } finally {
                clock.lockWait = clock.since(t);
            }
            t = clock.now();
            final SchemaSnapshot actual;
            try {
                actual = session.readActual();
            } finally {
                clock.readActual = clock.since(t);
            }
            verifyPlan(plan, session.dialect, actual);
            t = clock.now();
            final SchemaSnapshot desired;
            try {
                desired = desiredFor(plan, session);
            } finally {
                clock.loadDesired = clock.since(t);
            }
            notifyPlanned(plan, List.of());
            PolicyEvaluator.Evaluation evaluation =
                    new PolicyEvaluator(session.dialect, policy)
                            .evaluate(plan, actual, desired);
            Map<ObjectId, Map<ObjectId, Definition>> covered =
                    Optional.ofNullable(metaOf(plan))
                            .map(PlanMeta::coveredByParent)
                            .orElseGet(() -> PlanBuilder.coverageOf(
                                    session.dialect, namespace, plan.differences(),
                                    plan.changes(), desired));
            final var outcome = applyAndVerify(session, plan, evaluation, desired, covered, clock);
            session.commit();
            final var unexpected = unexpected(plan);
            final var unsupported = unsupported(plan);
            logUnexpectedAndUnsupported(unexpected, unsupported);
            return new ReconcileReport(outcome.outcome(), plan, outcome.applied(),
                    outcome.reported(), unexpected, unsupported,
                    Duration.ofNanos(System.nanoTime() - start), clock.snapshot());
        } catch (SchemaReconcilerException e) {
            throw e.withTimings(clock.snapshot());
        }
    }

    /**
     * Spec 3.1 {@code dump()}: steps 1 to 3 of 5.1 — connect, lock, read the
     * actual snapshot — rendered as a namespace-neutral desired-state script.
     * Requires no {@code desiredState}; the target is never mutated.
     */
    public String dump() {
        try (Session session = open(false)) {
            session.lock();
            return DumpRenderer.render(session.dialect, namespace, session.readActual());
        }
    }

    private void requireDesiredState(String method) {
        if (desiredState == null) {
            throw new ConfigurationException(
                    "desiredState is required for " + method + "()");
        }
    }

    /** Accumulates per-phase durations for {@link RunTimings}; monotonic (nanoTime). */
    private static final class PhaseClock {
        Duration lockWait = Duration.ZERO;
        Duration readActual = Duration.ZERO;
        Duration loadDesired = Duration.ZERO;
        Duration plan = Duration.ZERO;
        Duration apply = Duration.ZERO;
        Duration verify = Duration.ZERO;

        long now() {
            return System.nanoTime();
        }

        Duration since(long startNanos) {
            return Duration.ofNanos(System.nanoTime() - startNanos);
        }

        RunTimings snapshot() {
            return new RunTimings(lockWait, readActual, loadDesired, plan, apply, verify);
        }
    }

    private record RunOutcome(ReconcileOutcome outcome, List<PlannedChange> applied,
                              List<PlannedChange> reported) { }

    private RunOutcome applyAndVerify(Session session, ReconcilePlan plan,
                                      PolicyEvaluator.Evaluation evaluation,
                                      SchemaSnapshot desired,
                                      Map<ObjectId, Map<ObjectId, Definition>> coveredByParent,
                                      PhaseClock clock) {
        final var applied = new ArrayList<PlannedChange>();
        final var reported = new ArrayList<PlannedChange>();
        session.bindNamespace();
        final var applyStart = clock.now();
        try {
            for (var change : plan.changes()) {
                if (evaluation.decisions().get(change) == Decision.APPLY) {
                    notifyApplying(change, applied);
                    final var t0 = System.nanoTime();
                    for (final var sql : change.statements()) {
                        try {
                            session.execute(sql);
                        } catch (SQLException e) {
                            throw new ApplyException("namespace '" + namespace + "': change "
                                    + change.order() + " failed: " + e.getMessage(), e,
                                    List.copyOf(applied), change);
                        }
                    }
                    applied.add(change);
                    notifyApplied(change, Duration.ofNanos(System.nanoTime() - t0), applied);
                } else {
                    final var blocker = evaluation.demotedBy().get(change);
                    if (blocker != null) {
                        change = new PlannedChange(change.order(), change.target(), change.action(),
                                change.safety(), change.statements(), change.expected(),
                                change.reason() + " (blocked by " + blocker + ")");
                    }
                    reported.add(change);
                    LOG.log(System.Logger.Level.WARNING,
                            "namespace ''{0}'': change {1} ({2} {3}) not applied: {4}",
                            namespace, change.order(), change.action(), change.target(),
                            change.reason());
                    notifyReported(change, applied);
                }
            }
        } finally {
            clock.apply = clock.since(applyStart);
        }
        final var verifyStart = clock.now();
        try {
            verify(session, applied, coveredByParent);
        } finally {
            clock.verify = clock.since(verifyStart);
        }
        final var outcome = applied.isEmpty()
                ? (plan.changes().isEmpty() ? ReconcileOutcome.UP_TO_DATE
                        : ReconcileOutcome.REPORTED_ONLY)
                : ReconcileOutcome.RECONCILED;
        return new RunOutcome(outcome, List.copyOf(applied), List.copyOf(reported));
    }

    private void verify(Session session, List<PlannedChange> applied,
                        Map<ObjectId, Map<ObjectId, Definition>> coveredByParent) {
        final var expectations = new LinkedHashMap<ObjectId, Definition>();
        for (final var change : applied) {
            change.expected().ifPresent(d -> expectations.put(change.target(), d));
            final var covered = coveredByParent.get(change.target());
            if (covered != null) {
                expectations.putAll(covered);
            }
        }
        if (expectations.isEmpty()) {
            return;
        }
        final var after = session.readActual();
        final var remaining = new ArrayList<Difference>();
        expectations.forEach((id, expected) -> {
            final var object = after.objects().get(id);
            if (object == null) {
                remaining.add(new Difference(id, DifferenceType.MISSING,
                        Optional.of(expected), Optional.empty()));
            } else if (!object.definition().equals(expected)) {
                remaining.add(new Difference(id, DifferenceType.DRIFTED,
                        Optional.of(expected), Optional.of(object.definition())));
            }
        });
        if (!remaining.isEmpty()) {
            throw new VerificationException("namespace '" + namespace + "': " + remaining.size()
                    + " object(s) did not converge after apply: " + remaining.stream()
                            .map(d -> d.id().toString()).toList(), List.copyOf(remaining));
        }
    }

    private void verifyPlan(ReconcilePlan plan, Dialect dialect, SchemaSnapshot actual) {
        if (!namespace.equals(plan.targetNamespace()) || !dialect.id().equals(plan.dialectId())) {
            throw new IllegalArgumentException("plan targets namespace '"
                    + plan.targetNamespace() + "' on dialect '" + plan.dialectId()
                    + "', this reconciler is bound to '" + namespace + "' on '" + dialect.id() + "'");
        }
        if (!actual.fingerprint().equals(plan.actualFingerprint())) {
            throw new StalePlanException("namespace '" + namespace
                    + "': the target changed since this plan was issued", plan);
        }
    }

    private SchemaSnapshot desiredFor(ReconcilePlan plan, Session session) {
        final var meta = metaOf(plan);
        if (meta != null) {
            return meta.desired();
        }
        return loadDesired(session);
    }

    private List<Difference> unexpected(ReconcilePlan plan) {
        return plan.differences().stream()
                .filter(d -> d.type() == DifferenceType.UNEXPECTED).toList();
    }

    private List<Difference> unsupported(ReconcilePlan plan) {
        return plan.differences().stream()
                .filter(d -> d.type() == DifferenceType.UNSUPPORTED
                        && policy.unsupportedDecision(d) == Decision.REPORT)
                .toList();
    }

    /**
     * The WARNING logs of spec 3.3 and 5.5: unexpected objects and, under a
     * REPORT unsupported policy, each UNSUPPORTED difference.
     */
    private void logUnexpectedAndUnsupported(List<Difference> unexpected,
                                             List<Difference> unsupported) {
        for (final var d : unexpected) {
            LOG.log(System.Logger.Level.WARNING,
                    "namespace ''{0}'': unexpected object {1} is not in the desired state"
                            + " and was left unchanged",
                    namespace, d.id());
        }
        for (final var d : unsupported) {
            LOG.log(System.Logger.Level.WARNING,
                    "namespace ''{0}'': unsupported object {1} requires manual handling",
                    namespace, d.id());
        }
    }

    private PlanBuilder.Built buildPlan(Session session, SchemaSnapshot actual, SchemaSnapshot desired) {
        final var differences = Differ.diff(actual, desired, session.dialect);
        return new PlanBuilder(session.dialect, namespace, actual, desired).build(differences);
    }

    private SchemaSnapshot loadDesired(Session session) {
        final var separate = scratchDataSource != null;
        Connection scratch;
        try {
            scratch = separate ? scratchDataSource.getConnection() : session.conn;
        } catch (SQLException e) {
            throw new SchemaReconcilerException(
                    "namespace '" + namespace + "': could not obtain scratch connection", e);
        }
        var restoreAutoCommit = false;
        try {
            if (separate) {
                checkSameEngine(session.conn, scratch, session.dialect);
                try {
                    session.dialect.checkAccess(scratch, null, true);
                } catch (ConfigurationException e) {
                    throw names(e) ? e : new ConfigurationException(named(e.getMessage()), e);
                }
                if (session.dialect.capabilities().transactionalDdl()) {
                    scratch.setAutoCommit(false);
                    restoreAutoCommit = true;
                }
                if (statementTimeout != null) {
                    // the separate connection never sees the target session's
                    // SET LOCAL — the bound must be applied here too or the
                    // desired script and scratch snapshot read run unbounded
                    session.dialect.statementTimeout(scratch, statementTimeout);
                }
            }
            String scratchNamespace;
            try {
                scratchNamespace = session.dialect.createScratchNamespace(scratch, namespace);
            } catch (SQLException e) {
                throw new DesiredStateException("namespace '" + namespace
                        + "': could not create the scratch namespace", e);
            }
            try {
                ScriptProcessor processor =
                        new ScriptProcessor(desiredState.script(), namespace, scratchNamespace, session.dialect);
                if (!processor.hasPlaceholder()
                        && !session.dialect.capabilities().sessionDefaultNamespace()) {
                    throw new DesiredStateException("namespace '" + namespace
                            + "': the script contains no ${namespace} placeholder and dialect '"
                            + session.dialect.id() + "' has no session default namespace");
                }
                if (session.dialect.capabilities().sessionDefaultNamespace()) {
                    session.dialect.bindSessionNamespace(scratch, scratchNamespace);
                }
                try {
                    session.dialect.executeScript(scratch, processor.substituted(), scratchNamespace);
                } catch (DesiredStateException e) {
                    throw names(e) ? e : new DesiredStateException(named(e.getMessage()), e);
                } catch (SQLException e) {
                    throw new DesiredStateException("namespace '" + namespace
                            + "': the desired-state script failed: " + e.getMessage(), e);
                }
                return ObjectFilter.apply(
                        session.dialect.readSnapshot(scratch, scratchNamespace), objectFilter);
            } finally {
                try {
                    session.dialect.dropScratchNamespace(scratch, scratchNamespace);
                } catch (SQLException e) {
                    throw new SchemaReconcilerException("namespace '" + namespace
                            + "': could not drop scratch namespace '" + scratchNamespace + "'", e);
                }
            }
        } catch (SQLException e) {
            throw new SchemaReconcilerException(
                    "namespace '" + namespace + "': scratch processing failed", e);
        } finally {
            if (separate) {
                try {
                    scratch.rollback();
                } catch (SQLException ignored) {
                    // already closed or committed inside dropScratchNamespace
                }
                try {
                    if (restoreAutoCommit) {
                        scratch.setAutoCommit(true);
                    }
                } catch (SQLException e) {
                    LOG.log(System.Logger.Level.WARNING,
                            "could not restore auto-commit on the scratch connection", e);
                } finally {
                    try {
                        scratch.close();
                    } catch (SQLException e) {
                        throw new SchemaReconcilerException(
                                "namespace '" + namespace + "': could not close the scratch connection", e);
                    }
                }
            }
        }
    }

    private void checkSameEngine(Connection target, Connection scratch, Dialect dialect)
            throws SQLException {
        final var tm = target.getMetaData();
        final var sm = scratch.getMetaData();
        final var sameEngine = tm.getDatabaseProductName().equals(sm.getDatabaseProductName())
                && tm.getDatabaseMajorVersion() == sm.getDatabaseMajorVersion();
        if (!sameEngine || !dialect.supports(sm)) {
            throw new ConfigurationException("namespace '" + namespace
                    + "': the scratchDataSource is not the same engine"
                    + " and major version as the target dataSource");
        }
    }

    private boolean names(Exception e) {
        return e.getMessage() != null && e.getMessage().contains("namespace '" + namespace + "'");
    }

    /** Prefixes a message raised below the engine (resolver, dialect) with the target namespace (spec 7). */
    private String named(String message) {
        return "namespace '" + namespace + "': " + message;
    }

    private Session open() {
        return open(true);
    }

    private Session open(boolean needsScratch) {
        return new Session(needsScratch);
    }

    /**
     * Spec 3.6: a listener failure during {@code reconcile()} or {@code apply()},
     * from {@code planned} too, aborts the run as {@link ApplyException}.
     */
    private void notifyPlanned(ReconcilePlan plan, List<PlannedChange> applied) {
        try {
            listener.planned(plan);
        } catch (RuntimeException e) {
            throw new ApplyException("namespace '" + namespace + "': listener aborted the run", e,
                    List.copyOf(applied), null);
        }
    }

    private void notifyApplying(PlannedChange change, List<PlannedChange> applied) {
        try {
            listener.applying(change);
        } catch (RuntimeException e) {
            throw new ApplyException("namespace '" + namespace + "': listener aborted the run", e,
                    List.copyOf(applied), change);
        }
    }

    private void notifyApplied(PlannedChange change, Duration elapsed, List<PlannedChange> applied) {
        try {
            listener.applied(change, elapsed);
        } catch (RuntimeException e) {
            throw new ApplyException("namespace '" + namespace + "': listener aborted the run", e,
                    List.copyOf(applied), change);
        }
    }

    private void notifyReported(PlannedChange change, List<PlannedChange> applied) {
        try {
            listener.reported(change);
        } catch (RuntimeException e) {
            throw new ApplyException("namespace '" + namespace + "': listener aborted the run", e,
                    List.copyOf(applied), null);
        }
    }

    /** One reconcile run's connection, dialect, lock and transaction lifecycle. */
    private final class Session implements AutoCloseable {

        private final Dialect dialect;
        private final Connection conn;
        private boolean restoreAutoCommit;
        private boolean closed;

        Session(boolean needsScratch) {
            try {
                conn = dataSource.getConnection();
            } catch (SQLException e) {
                throw new SchemaReconcilerException(
                        "namespace '" + namespace + "': could not obtain a connection", e);
            }
            try {
                this.dialect = resolver.resolve(conn);
                DialectResolver.quoteOf(dialect, namespace);
                dialect.checkAccess(conn, namespace,
                        needsScratch && scratchDataSource == null);
                if (dialect.capabilities().transactionalDdl() && conn.getAutoCommit()) {
                    conn.setAutoCommit(false);
                    restoreAutoCommit = true;
                }
            } catch (RuntimeException | SQLException e) {
                closeQuietly();
                if (e instanceof ConfigurationException ce && !names(ce)) {
                    throw new ConfigurationException(named(ce.getMessage()), ce);
                }
                if (e instanceof SchemaReconcilerException sre) {
                    throw sre;
                }
                throw new SchemaReconcilerException(
                        "namespace '" + namespace + "': could not prepare the connection", e);
            }
        }

        void lock() {
            try {
                dialect.acquireLock(conn, namespace, lockTimeout);
                if (statementTimeout != null) {
                    dialect.statementTimeout(conn, statementTimeout);
                }
            } catch (LockTimeoutException e) {
                throw e;
            } catch (SQLException e) {
                throw new SchemaReconcilerException(
                        "namespace '" + namespace + "': could not acquire the reconcile lock", e);
            }
        }

        SchemaSnapshot readActual() {
            try {
                return ObjectFilter.apply(dialect.readSnapshot(conn, namespace),
                        objectFilter);
            } catch (SQLException e) {
                throw new SchemaReconcilerException(
                        "namespace '" + namespace + "': could not read the catalog snapshot", e);
            }
        }

        void bindNamespace() {
            if (dialect.capabilities().sessionDefaultNamespace()) {
                try {
                    dialect.bindSessionNamespace(conn, namespace);
                } catch (SQLException e) {
                    throw new SchemaReconcilerException(
                            "namespace '" + namespace + "': could not bind the session namespace", e);
                }
            }
        }

        void execute(String sql) throws SQLException {
            try (Statement statement = conn.createStatement()) {
                statement.execute(sql);
            }
        }

        void commit() {
            try {
                conn.commit();
            } catch (SQLException e) {
                throw new SchemaReconcilerException(
                        "namespace '" + namespace + "': commit failed", e);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                // roll back first: it clears an aborted transaction and
                // releases transaction-scoped locks, so explicit lock
                // release afterwards runs on a clean session
                try {
                    conn.rollback();
                } catch (SQLException e) {
                    LOG.log(System.Logger.Level.WARNING, "rollback failed", e);
                }
                try {
                    dialect.releaseLock(conn, namespace);
                } catch (SQLException | RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "lock release failed", e);
                }
                try {
                    if (restoreAutoCommit) {
                        conn.setAutoCommit(true);
                    }
                } catch (SQLException e) {
                    // close() runs while an exception may be propagating: a throw
                    // here would replace it, so the failure is only logged
                    LOG.log(System.Logger.Level.WARNING,
                            "namespace ''" + namespace + "'': could not restore auto-commit", e);
                }
            } finally {
                try {
                    conn.close();
                } catch (SQLException e) {
                    LOG.log(System.Logger.Level.WARNING,
                            "namespace ''" + namespace + "'': could not close the connection", e);
                }
            }
        }

        private void closeQuietly() {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // best effort
            }
        }
    }
}
