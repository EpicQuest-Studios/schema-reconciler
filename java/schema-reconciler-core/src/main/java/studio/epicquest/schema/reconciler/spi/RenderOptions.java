package studio.epicquest.schema.reconciler.spi;

import java.util.Map;
import java.util.Objects;

/**
 * Optional render-time flags passed to {@link Dialect#render} as its
 * fourth parameter (spec 6.1). All fields default to {@code false}/absent.
 *
 * @param concurrentIndexes when true, index creates and drops render
 *        their {@code CONCURRENTLY} forms where the dialect supports them
 *        (PostgreSQL: {@code CREATE INDEX CONCURRENTLY} — non-transactional,
 *        so the engine runs those changes after the transactional phase
 *        commits)
 * @param renamedParents parent names rewritten by an active rename hint,
 *        old name to new — set on UNEXPECTED renders only. A pruned child
 *        of a renamed parent (a {@code COLUMN}, {@code TRIGGER} and the
 *        like) must drop through the new parent name: the old one no
 *        longer exists once the rename ran
 */
public record RenderOptions(boolean concurrentIndexes,
                            Map<String, String> renamedParents) {

    public static final RenderOptions DEFAULT = new RenderOptions(false, Map.of());

    public RenderOptions {
        renamedParents = Map.copyOf(
                Objects.requireNonNull(renamedParents, "renamedParents"));
    }

    public RenderOptions(boolean concurrentIndexes) {
        this(concurrentIndexes, Map.of());
    }
}
