package studio.epicquest.schema.reconciler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.DesiredStateException;
import studio.epicquest.schema.reconciler.fake.FakeDb;
import studio.epicquest.schema.reconciler.fake.FakeDialect;

/** Spec 3.2: placeholder substitution, escaping and the literal-target scan. */
class ScriptProcessorTest {

    private final FakeDialect dialect = new FakeDialect(new FakeDb());

    private ScriptProcessor process(String script) {
        return new ScriptProcessor(script, "corex", "rcl_scratch_1", dialect);
    }

    @Test
    void substitutesQuotedScratchNamespace() {
        final var p = process("CREATE TABLE ${namespace}.t");
        assertEquals("CREATE TABLE \"rcl_scratch_1\".t", p.substituted());
        assertTrue(p.hasPlaceholder());
    }

    @Test
    void unqualifiedScriptHasNoPlaceholder() {
        final var p = process("CREATE TABLE t");
        assertFalse(p.hasPlaceholder());
        assertEquals("CREATE TABLE t", p.substituted());
    }

    @Test
    void escapedPlaceholderIsLiteralText() {
        final var p = process("SELECT '$${namespace}'");
        assertEquals("SELECT '${namespace}'", p.substituted());
        assertFalse(p.hasPlaceholder());
    }

    @Test
    void quotedPlaceholderIsRejected() {
        assertThrows(DesiredStateException.class,
                () -> process("CREATE TABLE \"${namespace}\".t"));
    }

    @Test
    void literalTargetReferenceIsRejected() {
        final var e = assertThrows(DesiredStateException.class,
                () -> process("CREATE TABLE corex.t"));
        assertTrue(e.getMessage().contains("literally"));
        assertThrows(DesiredStateException.class, () -> process("SELECT * FROM corex.t"));
        assertThrows(DesiredStateException.class, () -> process("SELECT * FROM \"corex\".t"));
        assertThrows(DesiredStateException.class, () -> process("SELECT * FROM COREX.t"));
    }

    @Test
    void similarNamesAreNotTheTarget() {
        // identifier prefix/suffix: not the namespace token
        assertEquals("SELECT * FROM corex2.t", process("SELECT * FROM corex2.t").substituted());
        assertEquals("SELECT * FROM x.corex", process("SELECT * FROM x.corex").substituted());
        // target name without a dot following it is a column/table, not a schema ref
        assertEquals("CREATE TABLE corex", process("CREATE TABLE corex").substituted());
    }

    @Test
    void placeholderTargetNameDoesNotFalsePositive() {
        // literal namespace named 'namespace': the placeholder must not trip the scan
        final var p = new ScriptProcessor(
                "CREATE TABLE ${namespace}.t", "namespace", "rcl_scratch_1", dialect);
        assertEquals("CREATE TABLE \"rcl_scratch_1\".t", p.substituted());
    }

    @Test
    void qualifiedChainDoesNotFalsePositive() {
        // 'corex' preceded by '.' is a mid-qualifier, not a namespace reference (3.2 rule 5)
        assertEquals("SELECT a.corex.b", process("SELECT a.corex.b").substituted());
    }
}
