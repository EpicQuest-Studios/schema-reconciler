package studio.epicquest.schema.reconciler.fake;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import studio.epicquest.schema.reconciler.model.Definition;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectIds;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.SchemaObject;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;

/**
 * An in-memory database exposing JDBC proxies, so the whole engine can run in
 * tests without a real database. The fake understands a minimal DDL language:
 *
 * <pre>
 *   CREATE &lt;KIND&gt; &lt;parent.&gt;?&lt;name&gt; [REQUIRES &lt;KIND&gt;:&lt;name&gt;,...] [DEF &lt;canonical&gt;]
 *   DROP   &lt;KIND&gt; &lt;parent.&gt;?&lt;name&gt;
 *   ALTER  &lt;KIND&gt; &lt;parent.&gt;?&lt;name&gt; DEF &lt;canonical&gt;
 * </pre>
 *
 * A {@code CREATE} stores a {@link SchemaObject} whose canonical definition is
 * the {@code DEF} token (empty when absent); {@code ALTER} replaces it.
 * Transactions copy the committed state on first write and merge on commit.
 */
public final class FakeDb {

    /** A statement executed on a connection. */
    public record ExecutedStatement(String namespace, String sql) { }

    private final Map<String, Map<ObjectId, SchemaObject>> committed = new HashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final List<ExecutedStatement> statementLog = new ArrayList<>();
    private int scratchSequence;

    public synchronized DataSource dataSource() {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> newConnection();
                    case "toString" -> "FakeDataSource";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Every statement ever executed through {@code Statement.execute}. */
    public synchronized List<ExecutedStatement> statementLog() {
        return List.copyOf(statementLog);
    }

    /** Statements executed against a namespace, excluding scratch work. */
    public synchronized List<ExecutedStatement> statementsOn(String namespace) {
        return statementLog.stream().filter(s -> s.namespace().equals(namespace)).toList();
    }

    /** Reads the committed objects of a namespace, as the catalog sees them. */
    public synchronized Map<ObjectId, SchemaObject> objectsIn(String namespace) {
        return Map.copyOf(committed.getOrDefault(namespace, Map.of()));
    }

    /** Executes DDL directly, bypassing JDBC — test fixture setup. */
    public synchronized void executeDirect(String namespace, String script) {
        Map<ObjectId, SchemaObject> objects =
                committed.computeIfAbsent(namespace, k -> new LinkedHashMap<>());
        for (final var chunk : script.split(";")) {
            final var statement = chunk.strip();
            if (!statement.isEmpty()) {
                try {
                    apply(objects, statement);
                } catch (SQLException e) {
                    throw new IllegalStateException("fixture DDL failed: " + e.getMessage(), e);
                }
            }
        }
    }

    /** Whether the namespace lock is currently held by any connection. */
    public boolean lockHeld(String namespace) {
        final var lock = locks.get(namespace);
        return lock != null && lock.isLocked();
    }

    /** Inserts an object directly — fixture setup for objects the DDL cannot express. */
    public synchronized void putObject(String namespace, SchemaObject object) {
        committed.computeIfAbsent(namespace, k -> new LinkedHashMap<>())
                .put(object.id(), object);
    }

    /** Builds a snapshot object, for fixture setup with {@link #putObject}. */
    public static SchemaObject object(ObjectId id, String def, ObjectId... requires) {
        return new SchemaObject(id, new Definition(def), Set.of(requires), Map.of());
    }

    /** Builds an external-dependent marker object (spec 5.8.3). */
    public static SchemaObject externalDependent(ObjectId id, ObjectId... requires) {
        return new SchemaObject(id, new Definition(""), Set.of(requires),
                Map.of(studio.epicquest.schema.reconciler.spi.Dialect.EXTERNAL_DEPENDENT_ATTRIBUTE,
                        "true"));
    }

    synchronized boolean tryLock(Handle handle, String namespace, Duration timeout) {
        final var lock = locks.computeIfAbsent(namespace, k -> new ReentrantLock());
        try {
            if (!lock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        handle.heldLocks.add(lock);
        return true;
    }

    synchronized void unlock(Handle handle, String namespace) {
        final var lock = locks.get(namespace);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
            handle.heldLocks.remove(lock);
        }
    }

    private synchronized void apply(Map<ObjectId, SchemaObject> objects, String sql)
            throws SQLException {
        final var tokens = sql.strip().split("\\s+");
        if (tokens.length < 3) {
            throw new SQLException("syntax error: " + sql);
        }
        switch (tokens[0]) {
            case "CREATE" -> {
                final var id = parseObjectId(tokens[1], tokens[2]);
                if (objects.containsKey(id)) {
                    throw new SQLException("object already exists: " + id);
                }
                final var parsed = parseTail(tokens, 3);
                objects.put(id, new SchemaObject(id,
                        new Definition(parsed.def.orElse("")), parsed.requires, Map.of()));
            }
            case "DROP" -> {
                final var id = parseObjectId(tokens[1], tokens[2]);
                if (objects.remove(id) == null) {
                    throw new SQLException("object does not exist: " + id);
                }
            }
            case "ALTER" -> {
                final var id = parseObjectId(tokens[1], tokens[2]);
                final var existing = objects.get(id);
                if (existing == null) {
                    throw new SQLException("object does not exist: " + id);
                }
                final var parsed = parseTail(tokens, 3);
                if (parsed.def.isEmpty()) {
                    throw new SQLException("ALTER requires DEF: " + sql);
                }
                objects.put(id, new SchemaObject(id, new Definition(parsed.def.get()),
                        parsed.requires.isEmpty() ? existing.dependsOn() : parsed.requires,
                        existing.attributes()));
            }
            default -> throw new SQLException("syntax error: " + sql);
        }
    }

    private record Parsed(Set<ObjectId> requires, Optional<String> def) { }

    private static Parsed parseTail(String[] tokens, int from) {
        final var requires = new LinkedHashSet<ObjectId>();
        var def = Optional.<String>empty();
        for (var i = from; i < tokens.length; i++) {
            if (tokens[i].equals("REQUIRES") && i + 1 < tokens.length) {
                for (final var ref : tokens[i + 1].split(",")) {
                    requires.add(parseReference(ref));
                }
                i++;
            } else if (tokens[i].equals("DEF") && i + 1 < tokens.length) {
                final var text = new StringBuilder(tokens[++i]);
                while (i + 1 < tokens.length) {
                    text.append(' ').append(tokens[++i]);
                }
                def = Optional.of(text.toString());
            }
        }
        return new Parsed(requires, def);
    }

    /** Parses {@code KIND:parent.name} or {@code KIND:name} (last dot splits parent). */
    public static ObjectId parseReference(String text) {
        final var colon = text.indexOf(':');
        final var kind = ObjectKind.valueOf(text.substring(0, colon));
        return parseObjectId(kind.name(), text.substring(colon + 1));
    }

    static ObjectId parseObjectId(String kindToken, String nameToken) {
        final var kind = ObjectKind.valueOf(kindToken);
        final var dot = nameToken.lastIndexOf('.');
        final var parented = Set.of(ObjectKind.COLUMN, ObjectKind.PRIMARY_KEY, ObjectKind.UNIQUE,
                ObjectKind.FOREIGN_KEY, ObjectKind.CHECK, ObjectKind.INDEX, ObjectKind.TRIGGER)
                .contains(kind);
        if (parented) {
            if (dot < 0) {
                throw new IllegalArgumentException(kind + " requires parent.name: " + nameToken);
            }
            return new ObjectId(kind, nameToken.substring(dot + 1), nameToken.substring(0, dot));
        }
        return new ObjectId(kind, nameToken);
    }

    /** Renders a {@code CREATE} statement for an object of the fake language. */
    static String createStatement(SchemaObject object) {
        final var sql = new StringBuilder("CREATE ").append(object.id().kind())
                .append(' ').append(qualified(object.id()));
        if (!object.dependsOn().isEmpty()) {
            final var deps = new ArrayList<ObjectId>(object.dependsOn());
            deps.sort(ObjectIds.ORDER);
            sql.append(" REQUIRES ");
            for (var i = 0; i < deps.size(); i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append(reference(deps.get(i)));
            }
        }
        if (!object.definition().canonical().isEmpty()) {
            sql.append(" DEF ").append(object.definition().canonical());
        }
        return sql.toString();
    }

    static String dropStatement(ObjectId id) {
        return "DROP " + id.kind() + " " + qualified(id);
    }

    static String alterStatement(SchemaObject object) {
        return "ALTER " + object.id().kind() + " " + qualified(object.id())
                + " DEF " + object.definition().canonical();
    }

    static String qualified(ObjectId id) {
        return id.parent().map(p -> p + "." + id.name()).orElse(id.name());
    }

    static String reference(ObjectId id) {
        return id.kind() + ":" + qualified(id);
    }

    Connection newConnection() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, new Handle());
    }

    /** Connection state and JDBC behaviour. */
    final class Handle implements InvocationHandler {

        boolean autoCommit = true;
        boolean closed;
        String namespace = "main";
        Map<String, Map<ObjectId, SchemaObject>> overlay;
        final List<ReentrantLock> heldLocks = new ArrayList<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "getAutoCommit" -> autoCommit;
                case "setAutoCommit" -> {
                    autoCommit = (Boolean) args[0];
                    if (autoCommit && overlay != null) {
                        commitOverlay();
                    }
                    yield null;
                }
                case "commit" -> {
                    commitOverlay();
                    yield null;
                }
                case "rollback" -> {
                    overlay = null;
                    yield null;
                }
                case "close" -> {
                    if (!closed) {
                        closed = true;
                        overlay = null;
                        heldLocks.forEach(ReentrantLock::unlock);
                        heldLocks.clear();
                    }
                    yield null;
                }
                case "isClosed" -> closed;
                case "getMetaData" -> metaData();
                case "createStatement" -> statement(this);
                case "unwrap" -> this;
                case "isWrapperFor" -> true;
                case "toString" -> "FakeConnection[" + namespace + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new SQLException("unsupported: " + method.getName());
            };
        }

        private void commitOverlay() {
            if (overlay != null) {
                synchronized (FakeDb.this) {
                    overlay.forEach((ns, objects) -> committed.put(ns, objects));
                    overlay = null;
                }
            }
        }

        /** The namespace state this connection sees and mutates. */
        Map<ObjectId, SchemaObject> state(String ns) {
            synchronized (FakeDb.this) {
                if (!autoCommit && overlay == null) {
                    overlay = new HashMap<>();
                }
                if (overlay != null) {
                    return overlay.computeIfAbsent(ns,
                            k -> copyOf(committed.getOrDefault(ns, Map.of())));
                }
                return committed.computeIfAbsent(ns, k -> new LinkedHashMap<>());
            }
        }

        void execute(String sql) throws SQLException {
            final var trimmed = sql.strip();
            if (trimmed.isEmpty()) {
                return;
            }
            synchronized (FakeDb.this) {
                statementLog.add(new ExecutedStatement(namespace, trimmed));
            }
            apply(state(namespace), trimmed);
        }

        private Map<ObjectId, SchemaObject> copyOf(Map<ObjectId, SchemaObject> objects) {
            return new LinkedHashMap<>(objects);
        }
    }

    private static Statement statement(Handle handle) {
        return (Statement) Proxy.newProxyInstance(FakeDb.class.getClassLoader(),
                new Class<?>[] {Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "execute" -> {
                        handle.execute((String) args[0]);
                        yield true;
                    }
                    case "close" -> null;
                    case "isClosed" -> false;
                    case "toString" -> "FakeStatement";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new SQLException("unsupported: " + method.getName());
                });
    }

    private static DatabaseMetaData metaData() {
        return (DatabaseMetaData) Proxy.newProxyInstance(FakeDb.class.getClassLoader(),
                new Class<?>[] {DatabaseMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getDatabaseProductName" -> "FakeDB";
                    case "getDatabaseMajorVersion" -> 1;
                    case "getDatabaseProductVersion" -> "1";
                    case "getUserName" -> "fake";
                    default -> throw new SQLException("unsupported: " + method.getName());
                });
    }
}
