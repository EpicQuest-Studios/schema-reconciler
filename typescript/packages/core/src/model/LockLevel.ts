/**
 * The strongest lock a change takes on its target (spec 4.4 metadata).
 * Advisory — a dialect reports what its rendered statements imply so an
 * operator can gauge concurrency impact; it does not drive execution.
 */
export const LockLevel = {
    /** No table-level lock implication worth reporting. */
    NONE: "NONE",
    /** `CREATE INDEX CONCURRENTLY` / `DROP INDEX CONCURRENTLY`. */
    SHARE_UPDATE_EXCLUSIVE: "SHARE_UPDATE_EXCLUSIVE",
    /** `CREATE INDEX` (plain) — blocks writes, not reads. */
    SHARE: "SHARE",
    /** `ALTER TABLE` variants that block writers only. */
    SHARE_ROW_EXCLUSIVE: "SHARE_ROW_EXCLUSIVE",
    /** Full `DROP TABLE`, `TRUNCATE`, most `ALTER TABLE` forms. */
    ACCESS_EXCLUSIVE: "ACCESS_EXCLUSIVE",
} as const;

export type LockLevel = (typeof LockLevel)[keyof typeof LockLevel];
