package studio.epicquest.schema.reconciler.model;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One schema object in a snapshot (spec 4.2).
 *
 * @param id identity
 * @param definition the canonical definition; comparison uses this only
 * @param dependsOn objects this one requires
 * @param attributes dialect-specific details, informational only, never compared
 */
public record SchemaObject(
        ObjectId id,
        Definition definition,
        Set<ObjectId> dependsOn,
        Map<String, String> attributes) {

    public SchemaObject {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definition, "definition");
        dependsOn = Set.copyOf(Objects.requireNonNull(dependsOn, "dependsOn"));
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }
}
