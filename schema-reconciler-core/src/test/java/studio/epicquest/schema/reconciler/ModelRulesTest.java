package studio.epicquest.schema.reconciler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.lang.module.ModuleFinder;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.fake.FakeDb;
import studio.epicquest.schema.reconciler.fake.FakeDialect;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.spi.Dialect;

/** Spec 2.2 (JPMS), 3.1 (build opens no connection), 3.3, 4.1, 10.2 and 10.5. */
class ModelRulesTest {

    @Test
    void coreModuleDescriptorMatchesSpec22() throws Exception {
        final var location = Path.of(Dialect.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        final var descriptor = ModuleFinder.of(location).findAll().iterator().next().descriptor();
        assertEquals("studio.epicquest.schema.reconciler", descriptor.name());
        final var exported = descriptor.exports().stream()
                .map(e -> e.source()).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(Set.of("studio.epicquest.schema.reconciler",
                "studio.epicquest.schema.reconciler.model",
                "studio.epicquest.schema.reconciler.spi"), exported);
        assertTrue(descriptor.uses().contains(Dialect.class.getName()), descriptor.uses().toString());
    }

    @Test
    void spiOriginalMethodsAreAbstractAndLaterAdditionsAreDefault() {
        final var abstractMethods = Arrays.stream(Dialect.class.getDeclaredMethods())
                .filter(m -> Modifier.isAbstract(m.getModifiers()))
                .map(Method::getName).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(Set.of("id", "supports", "capabilities", "acquireLock", "releaseLock",
                "createScratchNamespace", "dropScratchNamespace", "executeScript",
                "readSnapshot", "render", "quoteIdentifier"), abstractMethods);
    }

    @Test
    void compiledClassesTargetJava25WithoutPreviewFeatures() throws Exception {
        final var resource = Dialect.class.getResourceAsStream("Dialect.class");
        try (var in = new DataInputStream(resource)) {
            in.readInt();
            final int minor = in.readUnsignedShort();
            final int major = in.readUnsignedShort();
            assertEquals(69, major, "class file major version of Java 25");
            assertEquals(0, minor, "a preview class file has minor version 65535");
        }
    }

    @Test
    void buildOpensNoConnection() {
        final var db = new FakeDb();
        final DataSource failing = (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (p, m, a) -> {
                    if (m.getName().equals("getConnection")) {
                        throw new SQLException("build() must not connect");
                    }
                    return null;
                });
        SchemaReconciler.builder().dataSource(failing).namespace("corex")
                .desiredState(DesiredState.ofScript("")).dialect(new FakeDialect(db)).build();
    }

    @Test
    void policyRejectsApplyForUnsupported() {
        assertThrows(IllegalArgumentException.class,
                () -> ReconcilePolicy.defaults().withUnsupported(Decision.APPLY));
        assertThrows(IllegalArgumentException.class, () -> ReconcilePolicy.defaults()
                .withUnsupportedCategory("extension", Decision.APPLY));
    }

    @Test
    void objectIdEnforcesParentAndNameRules() {
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectId(ObjectKind.COLUMN, "c", Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectId(ObjectKind.TABLE, "t", Optional.of("p")));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectId(ObjectKind.TABLE, "", Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectId(ObjectKind.COLUMN, "c", Optional.of("")));
        new ObjectId(ObjectKind.INDEX, "i", "t");
        new ObjectId(ObjectKind.TRIGGER, "tr", "t");
        new ObjectId(ObjectKind.CHECK, "ck", "t");
    }
}
