/**
 * Vendor-neutral schema reconciler: API, model, dialect SPI and core algorithm.
 */
module studio.epicquest.schema.reconciler {
    requires transitive java.sql;

    exports studio.epicquest.schema.reconciler;
    exports studio.epicquest.schema.reconciler.model;
    exports studio.epicquest.schema.reconciler.spi;

    uses studio.epicquest.schema.reconciler.spi.Dialect;
}
