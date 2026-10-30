package studio.epicquest.schema.reconciler.model;

/** Kind of a schema object. */
public enum ObjectKind {
    TABLE, COLUMN, PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK,
    INDEX, VIEW, SEQUENCE, ROUTINE, TRIGGER, OTHER,
    MATERIALIZED_VIEW, TYPE, DOMAIN, POLICY, EXTENSION, COMMENT
}
