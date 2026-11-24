package studio.epicquest.schema.reconciler.model;

import java.util.List;
import java.util.Objects;

/**
 * Operational hazard metadata of a {@link PlannedChange} (spec 4.4):
 * the table lock the statements imply, whether the table is rewritten
 * (a full rewrite is I/O proportional to table size), whether existing
 * rows are validated (a {@code NOT VALID} skip or a validating scan), and
 * free-form notes. {@link #NONE} is the default — a change whose
 * statements carry no lock beyond statement scope.
 *
 * @param lockLevel the table lock the statements imply
 * @param rewritesTable whether the table is rewritten
 * @param validatesRows whether existing rows are validated
 * @param notes free-form notes
 */
public record ChangeHazard(
        LockLevel lockLevel,
        boolean rewritesTable,
        boolean validatesRows,
        List<String> notes) {

    public static final ChangeHazard NONE =
            new ChangeHazard(LockLevel.NONE, false, false, List.of());

    public ChangeHazard {
        Objects.requireNonNull(lockLevel, "lockLevel");
        notes = List.copyOf(Objects.requireNonNull(notes, "notes"));
    }

    public ChangeHazard(LockLevel lockLevel, boolean rewritesTable,
            boolean validatesRows) {
        this(lockLevel, rewritesTable, validatesRows, List.of());
    }

    /** Whether this hazard carries anything beyond {@link #NONE}. */
    public boolean isTrivial() {
        return lockLevel == LockLevel.NONE
                && !rewritesTable && !validatesRows && notes.isEmpty();
    }
}
