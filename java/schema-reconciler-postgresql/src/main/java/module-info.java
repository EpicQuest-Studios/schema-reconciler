/**
 * PostgreSQL dialect, the reference implementation.
 */
module studio.epicquest.schema.reconciler.postgresql {
    requires transitive studio.epicquest.schema.reconciler;
    requires java.sql;

    exports studio.epicquest.schema.reconciler.postgresql;

    provides studio.epicquest.schema.reconciler.spi.Dialect
            with studio.epicquest.schema.reconciler.postgresql.PostgreSqlDialect;
}
