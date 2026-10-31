package studio.epicquest.schema.reconciler.model;

import java.util.Objects;

/**
 * An engine-rendered canonical definition of a schema object (spec 4.2, 5.3).
 */
public record Definition(String canonical) {

    public Definition {
        Objects.requireNonNull(canonical, "canonical");
    }
}
