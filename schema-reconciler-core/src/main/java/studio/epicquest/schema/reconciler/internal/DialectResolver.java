package studio.epicquest.schema.reconciler.internal;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import studio.epicquest.schema.reconciler.ConfigurationException;
import studio.epicquest.schema.reconciler.spi.Dialect;

/**
 * Dialect discovery and deferred selection (spec 3.4, 10.3). Discovery runs at
 * {@code build()} time for presence only; the {@code supports} selection runs
 * on first use, when a connection exists.
 */
public final class DialectResolver {

    /** The core major version this implementation is (10.3). */
    public static final int CORE_MAJOR_VERSION = 1;

    private final Dialect explicit;
    private volatile Dialect resolved;

    public DialectResolver(Dialect explicit) {
        this.explicit = explicit;
    }

    public static String quoteOf(Dialect dialect, String identifier) {
        try {
            return dialect.quoteIdentifier(identifier);
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(
                    "namespace '" + identifier + "' is not a legal identifier for dialect '"
                            + dialect.id() + "'", e);
        }
    }

    /** The dialect when already known — explicit or previously resolved. */
    public Dialect peek() {
        return explicit != null ? explicit : resolved;
    }

    public Dialect resolve(Connection connection) {
        final var d = resolved;
        if (d != null) {
            return d;
        }
        synchronized (this) {
            if (resolved == null) {
                resolved = select(connection);
            }
            return resolved;
        }
    }

    private Dialect select(Connection connection) {
        DatabaseMetaData metaData;
        try {
            metaData = connection.getMetaData();
        } catch (SQLException e) {
            throw new ConfigurationException("could not read database metadata", e);
        }
        Dialect selected;
        if (explicit != null) {
            checkSupports(explicit, metaData);
            selected = explicit;
        } else {
            final var matching = new ArrayList<Dialect>();
            for (final var candidate : ServiceLoader.load(Dialect.class)) {
                boolean supports;
                try {
                    supports = candidate.supports(metaData);
                } catch (SQLException e) {
                    throw new ConfigurationException(
                            "dialect '" + candidate.id() + "' failed its supports check", e);
                }
                if (supports) {
                    matching.add(candidate);
                }
            }
            selected = switch (matching.size()) {
                case 0 -> throw new ConfigurationException(
                        "no dialect supports this database: " + productName(metaData));
                case 1 -> matching.get(0);
                default -> throw new ConfigurationException(
                        "several dialects support " + productName(metaData) + ": "
                                + matching.stream().map(Dialect::id).sorted()
                                        .collect(Collectors.joining(", ")));
            };
        }
        if (selected.coreMajorVersion() != CORE_MAJOR_VERSION) {
            throw new ConfigurationException(
                    "dialect '" + selected.id() + "' was built against core major version "
                            + selected.coreMajorVersion() + "; this core is " + CORE_MAJOR_VERSION);
        }
        return selected;
    }

    private static void checkSupports(Dialect dialect, DatabaseMetaData metaData) {
        try {
            if (!dialect.supports(metaData)) {
                throw new ConfigurationException(
                        "explicitly configured dialect '" + dialect.id()
                                + "' does not support this database: " + productName(metaData));
            }
        } catch (SQLException e) {
            throw new ConfigurationException(
                    "dialect '" + dialect.id() + "' failed its supports check", e);
        }
    }

    private static String productName(DatabaseMetaData metaData) {
        try {
            return metaData.getDatabaseProductName() + " " + metaData.getDatabaseMajorVersion();
        } catch (SQLException e) {
            return "unknown";
        }
    }
}
