package studio.epicquest.schema.reconciler.tck;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A {@link DataSource} that records every SQL string passed to
 * {@code Statement.execute*} and {@code prepareStatement/prepareCall}. Used by
 * conformance scenarios that must observe which statements ran where
 * (for example C-02's "no statements against the target namespace").
 */
public final class TrackingDataSource implements DataSource {

    /** One recorded statement. */
    public record ExecutedSql(String sql, boolean prepared) { }

    private final DataSource delegate;
    private final List<ExecutedSql> log = new ArrayList<>();

    public TrackingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    /** All recorded SQL, in execution order. */
    public synchronized List<ExecutedSql> statements() {
        return List.copyOf(log);
    }

    /** SQL recorded through {@code Statement.execute} (not prepared channels). */
    public synchronized List<String> executedSql() {
        return log.stream().filter(s -> !s.prepared()).map(ExecutedSql::sql).toList();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(delegate.getConnection(username, password));
    }

    private synchronized void record(String sql, boolean prepared) {
        log.add(new ExecutedSql(sql, prepared));
    }

    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Connection.class},
                new RecordingHandler(connection));
    }

    private final class RecordingHandler implements InvocationHandler {
        private final Connection target;

        RecordingHandler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("prepareStatement") || method.getName().equals("prepareCall")) {
                record((String) args[0], true);
            }
            final var result = method.invoke(target, args);
            if (result instanceof Statement statement) {
                return wrapStatement(statement);
            }
            return result;
        }
    }

    private Statement wrapStatement(Statement statement) {
        return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {statement instanceof PreparedStatement
                        ? PreparedStatement.class
                        : statement instanceof CallableStatement
                        ? CallableStatement.class : Statement.class},
                (proxy, method, args) -> {
                    if ((method.getName().startsWith("execute")
                            || method.getName().equals("addBatch")) && args != null
                            && args.length > 0 && args[0] instanceof String sql) {
                        record(sql, false);
                    }
                    return method.invoke(statement, args);
                });
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }
}
