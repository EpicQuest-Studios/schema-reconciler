package studio.epicquest.schema.reconciler.internal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.fake.FakeDb;
import studio.epicquest.schema.reconciler.fake.FakeDialect;
import studio.epicquest.schema.reconciler.model.Definition;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.SchemaObject;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;

/** Spec 4.2: comparison uses the definition only, never the attributes. */
class DefinitionComparisonTest {

    private static SchemaSnapshot snapshot(String attribute) {
        final var id = new ObjectId(ObjectKind.TABLE, "t");
        return SchemaSnapshot.of(Map.of(id, new SchemaObject(id, new Definition("same"),
                Set.of(), Map.of("note", attribute))));
    }

    @Test
    void differentAttributesWithEqualDefinitionsAreNotADifference() {
        final var diff = Differ.diff(snapshot("a"), snapshot("b"), new FakeDialect(new FakeDb()));
        assertTrue(diff.isEmpty(), diff.toString());
    }
}
