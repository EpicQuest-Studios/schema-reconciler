package studio.epicquest.schema.reconciler;

import java.util.List;
import java.util.Objects;
import studio.epicquest.schema.reconciler.model.Difference;

/**
 * Applied objects did not converge (spec 7). Carries the remaining differences.
 */
public class VerificationException extends SchemaReconcilerException {

    private final List<Difference> remaining;

    public VerificationException(String message, List<Difference> remaining) {
        super(message);
        this.remaining = List.copyOf(Objects.requireNonNull(remaining, "remaining"));
    }

    public List<Difference> remaining() {
        return remaining;
    }
}
