// Package reconciler is the Go binding of the schema-reconciler API
// (SPEC.md v0.10, studio.epicquest.schema.reconciler): a declarative,
// data-safe reconciler that drives a database schema toward a desired state
// declared as a SQL script.
//
// Packages:
//
//   - model — the snapshot and plan model (spec 4): ObjectId, SchemaObject,
//     SchemaSnapshot, Difference, PlannedChange, ReconcilePlan with its
//     ToSQL/ToJSON serialisations.
//   - spi — the Service Provider Interface (spec 6) implemented once per
//     database product, over database/sql: Dialect, DialectCapabilities,
//     RenderOptions and the DB/Conn/Executor connection contract.
//   - reconciler — the application API (spec 3): SchemaReconciler and its
//     Builder, DesiredState, ReconcilePolicy, ReconcileReport, the
//     spec-7 error hierarchy, and the dialect registry (the Go analog of
//     ServiceLoader discovery).
package reconciler
