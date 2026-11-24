/**
 * Optional render-time flags passed to {@link Dialect.render} as its
 * fourth parameter (spec 6.1). All fields default to `false`/absent.
 */
export interface RenderOptions {
    /**
     * When true, index creates and drops render their `CONCURRENTLY`
     * forms where the dialect supports them (PostgreSQL:
     * `CREATE INDEX CONCURRENTLY` — non-transactional, so the engine
     * runs those changes after the transactional phase commits).
     */
    readonly concurrentIndexes?: boolean;

    /**
     * Parent names rewritten by an active rename hint, old name to new —
     * set on UNEXPECTED renders only. A pruned child of a renamed parent
     * (a `COLUMN`, `TRIGGER` and the like) must drop through the new
     * parent name: the old one no longer exists once the rename ran.
     */
    readonly renamedParents?: ReadonlyMap<string, string>;
}
