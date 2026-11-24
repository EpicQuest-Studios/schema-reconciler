import { LockLevel } from "./LockLevel.ts";

/**
 * Operational hazard metadata of a {@link PlannedChange} (spec 4.4):
 * the table lock the statements imply, whether the table is rewritten
 * (a full rewrite is I/O proportional to table size), whether existing
 * rows are validated (a `NOT VALID` skip or a validating scan), and
 * free-form notes. {@link ChangeHazard.NONE} is the default — a change
 * whose statements carry no lock beyond statement scope.
 */
export class ChangeHazard {
    readonly lockLevel: LockLevel;
    readonly rewritesTable: boolean;
    readonly validatesRows: boolean;
    readonly notes: readonly string[];

    static readonly NONE = new ChangeHazard(LockLevel.NONE, false, false, []);

    constructor(lockLevel: LockLevel, rewritesTable: boolean,
                validatesRows: boolean, notes: readonly string[] = []) {
        if (lockLevel == null) {
            throw new TypeError("lockLevel is required");
        }
        this.lockLevel = lockLevel;
        this.rewritesTable = rewritesTable;
        this.validatesRows = validatesRows;
        this.notes = Object.freeze([...notes]);
        Object.freeze(this);
    }

    /** Whether this hazard carries anything beyond {@link ChangeHazard.NONE}. */
    get isTrivial(): boolean {
        return this.lockLevel === LockLevel.NONE
                && !this.rewritesTable && !this.validatesRows
                && this.notes.length === 0;
    }
}
