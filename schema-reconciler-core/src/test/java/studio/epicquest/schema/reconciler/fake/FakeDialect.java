package studio.epicquest.schema.reconciler.fake;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import studio.epicquest.schema.reconciler.LockTimeoutException;
import studio.epicquest.schema.reconciler.model.ChangeAction;
import studio.epicquest.schema.reconciler.model.ChangeSafety;
import studio.epicquest.schema.reconciler.model.Difference;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectIds;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.SchemaObject;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;
import studio.epicquest.schema.reconciler.spi.Dialect;
import studio.epicquest.schema.reconciler.spi.DialectCapabilities;

/**
 * A {@link Dialect} over {@link FakeDb}, used to exercise the whole engine in
 * core unit tests. Mirroring the PostgreSQL profile: a missing table's create
 * covers its columns and owned sequences; drifted views/routines/etc. are
 * recreate pairs ({@code REBUILD}), drifted tables recreate {@code DESTRUCTIVE},
 * drifted sequences are a destructive {@code ALTER}, and drifted columns are
 * manual destructive changes.
 */
public final class FakeDialect implements Dialect {

    private static final AtomicInteger SCRATCH = new AtomicInteger();

    private final FakeDb db;
    private final Set<ObjectKind> supportedKinds;

    public FakeDialect(FakeDb db) {
        this(db, Set.of(ObjectKind.values()));
    }

    public FakeDialect(FakeDb db, Set<ObjectKind> supportedKinds) {
        this.db = db;
        this.supportedKinds = supportedKinds;
    }

    @Override
    public String id() {
        return "fake";
    }

    @Override
    public boolean supports(DatabaseMetaData metaData) throws SQLException {
        return "FakeDB".equals(metaData.getDatabaseProductName());
    }

    @Override
    public DialectCapabilities capabilities() {
        return new DialectCapabilities(true, true, supportedKinds);
    }

    @Override
    public void acquireLock(Connection connection, String namespace, Duration timeout)
            throws SQLException {
        if (!db.tryLock(handle(connection), namespace, timeout)) {
            throw new LockTimeoutException("namespace '" + namespace
                    + "': lock not acquired within " + timeout);
        }
    }

    @Override
    public void releaseLock(Connection connection, String namespace) throws SQLException {
        db.unlock(handle(connection), namespace);
    }

    @Override
    public void bindSessionNamespace(Connection connection, String namespace) throws SQLException {
        handle(connection).namespace = namespace;
    }

    @Override
    public String createScratchNamespace(Connection connection, String targetNamespace)
            throws SQLException {
        final var scratch = "rcl_scratch_" + SCRATCH.incrementAndGet();
        handle(connection).state(scratch);
        return scratch;
    }

    @Override
    public void dropScratchNamespace(Connection connection, String scratchNamespace)
            throws SQLException {
        handle(connection).state(scratchNamespace).clear();
    }

    @Override
    public void executeScript(Connection connection, String script, String scratchNamespace)
            throws SQLException {
        for (final var chunk : script.split(";")) {
            final var statement = chunk.strip();
            if (!statement.isEmpty()) {
                try (var s = connection.createStatement()) {
                    s.execute(statement);
                }
            }
        }
    }

    @Override
    public SchemaSnapshot readSnapshot(Connection connection, String namespace) throws SQLException {
        return SchemaSnapshot.of(Map.copyOf(handle(connection).state(namespace)));
    }

    @Override
    public List<PlannedChange> render(Difference difference, SchemaSnapshot desired,
                                    String targetNamespace) {
        final var id = difference.id();
        return switch (difference.type()) {
            case MISSING -> {
                final var want = desired.objects().get(id);
                final var statements = new ArrayList<String>();
                statements.add(FakeDb.createStatement(want));
                final var children = new ArrayList<ObjectId>(coveredChildren(difference, desired));
                children.sort(ObjectIds.ORDER);
                for (final var child : children) {
                    statements.add(FakeDb.createStatement(desired.objects().get(child)));
                }
                yield List.of(new PlannedChange(0, id, ChangeAction.CREATE,
                        ChangeSafety.ADDITIVE, statements, Optional.of(want.definition()),
                        "missing " + id));
            }
            case DRIFTED -> switch (id.kind()) {
                case TABLE -> recreate(desired, id, ChangeSafety.DESTRUCTIVE, "drifted table");
                case SEQUENCE -> {
                    final var want = desired.objects().get(id);
                    yield List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                            ChangeSafety.DESTRUCTIVE, List.of(FakeDb.alterStatement(want)),
                            Optional.of(want.definition()), "drifted sequence"));
                }
                case COLUMN -> List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                        ChangeSafety.DESTRUCTIVE, List.of(), Optional.empty(),
                        "drifted column requires manual change"));
                default -> recreate(desired, id, ChangeSafety.REBUILD, "drifted " + id.kind());
            };
            default -> List.of();
        };
    }

    private List<PlannedChange> recreate(SchemaSnapshot desired, ObjectId id, ChangeSafety safety,
                                         String reason) {
        final var want = desired.objects().get(id);
        return List.of(
                new PlannedChange(0, id, ChangeAction.DROP, safety,
                        List.of(FakeDb.dropStatement(id)), Optional.empty(), reason),
                new PlannedChange(0, id, ChangeAction.CREATE, safety,
                        List.of(FakeDb.createStatement(want)),
                        Optional.of(want.definition()), reason));
    }

    @Override
    public String quoteIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty() || identifier.length() > 63
                || identifier.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("illegal identifier: " + identifier);
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    @Override
    public Set<ObjectId> coveredChildren(Difference created, SchemaSnapshot desired) {
        if (created.id().kind() != ObjectKind.TABLE) {
            return Set.of();
        }
        final var table = created.id();
        final var children = new LinkedHashSet<ObjectId>();
        for (final var object : desired.objects().values()) {
            final var id = object.id();
            if (id.kind() == ObjectKind.COLUMN && id.parent().orElse("").equals(table.name())) {
                children.add(id);
            }
            if (id.kind() == ObjectKind.SEQUENCE && object.dependsOn().contains(table)) {
                children.add(id);
            }
        }
        return children;
    }

    @Override
    public Set<String> nameScopes(ObjectId id) {
        return switch (id.kind()) {
            case TABLE, VIEW, SEQUENCE, INDEX, OTHER, MATERIALIZED_VIEW, TYPE, DOMAIN ->
                    Set.of("relation:" + id.name());
            case PRIMARY_KEY, UNIQUE -> Set.of(
                    "constraint:" + id.parent().orElse("") + "." + id.name(),
                    "relation:" + id.name());
            case FOREIGN_KEY, CHECK -> Set.of("constraint:" + id.parent().orElse("") + "." + id.name());
            case COLUMN -> Set.of("column:" + id.parent().orElse("") + "." + id.name());
            case TRIGGER -> Set.of("trigger:" + id.parent().orElse("") + "." + id.name());
            case ROUTINE -> Set.of("routine:" + id.name());
            case POLICY, EXTENSION, COMMENT -> Set.of();
        };
    }

    /**
     * The fake language round-trips its own {@code CREATE} text, so every
     * managed object dumps as a creatable statement; {@code OTHER} objects
     * and external-dependent markers have no create fragment (spec 6.1).
     */
    @Override
    public List<String> dumpStatements(SchemaObject object) {
        if (object.id().kind() == ObjectKind.OTHER
                || object.attributes().containsKey(Dialect.EXTERNAL_DEPENDENT_ATTRIBUTE)) {
            return List.of();
        }
        return List.of(FakeDb.createStatement(object));
    }

    private static FakeDb.Handle handle(Connection connection) throws SQLException {
        return connection.unwrap(FakeDb.Handle.class);
    }
}
