package studio.epicquest.schema.reconciler.spi;

import studio.epicquest.schema.reconciler.model.ObjectKind;
import java.util.Set;

/** What a dialect supports. */
public record DialectCapabilities(
        boolean transactionalDdl,
        boolean sessionDefaultNamespace,
        Set<ObjectKind> supportedKinds) {

    public DialectCapabilities {
        supportedKinds = Set.copyOf(supportedKinds);
    }
}
