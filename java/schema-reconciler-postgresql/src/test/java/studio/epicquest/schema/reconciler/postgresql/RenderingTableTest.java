package studio.epicquest.schema.reconciler.postgresql;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.DesiredState;
import studio.epicquest.schema.reconciler.SchemaReconciler;
import studio.epicquest.schema.reconciler.model.ChangeAction;
import studio.epicquest.schema.reconciler.model.ChangeSafety;
import studio.epicquest.schema.reconciler.model.PlannedChange;

/** Each row of spec A.6 (rendering) against the plan the PostgreSQL dialect really produces. */
class RenderingTableTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    @AfterAll
    static void stopDatabase() {
        PgTestDatabase.stop();
    }

    private record Row(String name, String target, String desired,
                       Predicate<List<PlannedChange>> expected) { }

    private static boolean has(List<PlannedChange> changes, ChangeAction action,
                               ChangeSafety safety, String... fragments) {
        return changes.stream().anyMatch(c -> c.action() == action && c.safety() == safety
                && java.util.Arrays.stream(fragments).allMatch(f ->
                        c.statements().stream().anyMatch(s -> s.contains(f))));
    }

    private static boolean manual(List<PlannedChange> changes, ChangeSafety safety) {
        return changes.stream().anyMatch(c -> c.isManual() && c.safety() == safety);
    }

    private static final String T = "CREATE TABLE t (id bigint PRIMARY KEY, name text)";

    private static final Row[] ROWS = {
        new Row("MISSING table: options, replica identity, one ADDITIVE change", "",
                "CREATE TABLE t (id bigint NOT NULL, note text) WITH (fillfactor = 70);"
                        + " ALTER TABLE t REPLICA IDENTITY FULL",
                ch -> ch.size() == 1 && has(ch, ChangeAction.CREATE, ChangeSafety.ADDITIVE,
                        "CREATE TABLE", "WITH (fillfactor=70)", "REPLICA IDENTITY FULL")),
        new Row("MISSING nullable column", "CREATE TABLE ${ns}.t (id bigint)",
                "CREATE TABLE t (id bigint, extra text)",
                ch -> has(ch, ChangeAction.CREATE, ChangeSafety.ADDITIVE, "ADD COLUMN")),
        new Row("MISSING NOT NULL column with constant default", "CREATE TABLE ${ns}.t (id bigint)",
                "CREATE TABLE t (id bigint, flag int NOT NULL DEFAULT 0)",
                ch -> has(ch, ChangeAction.CREATE, ChangeSafety.ADDITIVE, "ADD COLUMN", "NOT NULL")),
        new Row("MISSING NOT NULL column, non-constant default: manual DESTRUCTIVE",
                "CREATE TABLE ${ns}.t (id bigint)",
                "CREATE TABLE t (id bigint, at timestamptz NOT NULL DEFAULT now())",
                ch -> manual(ch, ChangeSafety.DESTRUCTIVE)),
        new Row("MISSING NOT NULL column without default: manual DESTRUCTIVE",
                "CREATE TABLE ${ns}.t (id bigint)",
                "CREATE TABLE t (id bigint, must int NOT NULL)",
                ch -> manual(ch, ChangeSafety.DESTRUCTIVE)),
        new Row("MISSING constraint", "CREATE TABLE ${ns}.t (id bigint, n int)",
                "CREATE TABLE t (id bigint, n int, CONSTRAINT ck CHECK (n > 0))",
                ch -> has(ch, ChangeAction.CREATE, ChangeSafety.ADDITIVE, "ADD CONSTRAINT")),
        new Row("DRIFTED constraint: DROP then CREATE, REBUILD",
                "CREATE TABLE ${ns}.t (id bigint, n int, CONSTRAINT ck CHECK (n > 1))",
                "CREATE TABLE t (id bigint, n int, CONSTRAINT ck CHECK (n > 0))",
                ch -> has(ch, ChangeAction.DROP, ChangeSafety.REBUILD, "DROP CONSTRAINT")
                        && has(ch, ChangeAction.CREATE, ChangeSafety.REBUILD, "ADD CONSTRAINT")),
        new Row("MISSING index", "CREATE TABLE ${ns}.t (id bigint, n int)",
                "CREATE TABLE t (id bigint, n int); CREATE INDEX ix ON t (n)",
                ch -> has(ch, ChangeAction.CREATE, ChangeSafety.ADDITIVE, "CREATE INDEX")),
        new Row("DRIFTED index: DROP INDEX then CREATE",
                "CREATE TABLE ${ns}.t (id bigint, n int); CREATE INDEX ix ON ${ns}.t (id)",
                "CREATE TABLE t (id bigint, n int); CREATE INDEX ix ON t (n)",
                ch -> has(ch, ChangeAction.DROP, ChangeSafety.REBUILD, "DROP INDEX")
                        && has(ch, ChangeAction.CREATE, ChangeSafety.REBUILD, "CREATE INDEX")),
        new Row("MISSING view", "CREATE TABLE ${ns}.t (id bigint)",
                "CREATE TABLE t (id bigint); CREATE VIEW v AS SELECT id FROM t",
                ch -> has(ch, ChangeAction.CREATE, ChangeSafety.ADDITIVE, "CREATE VIEW")),
        new Row("DRIFTED view: DROP VIEW then CREATE",
                "CREATE TABLE ${ns}.t (id bigint, n int); CREATE VIEW ${ns}.v AS SELECT id FROM ${ns}.t",
                "CREATE TABLE t (id bigint, n int); CREATE VIEW v AS SELECT n FROM t",
                ch -> has(ch, ChangeAction.DROP, ChangeSafety.REBUILD, "DROP VIEW")
                        && has(ch, ChangeAction.CREATE, ChangeSafety.REBUILD, "CREATE VIEW")),
        new Row("MISSING owned sequence not covered by a table create",
                "CREATE TABLE ${ns}.t (id bigint)",
                "CREATE SEQUENCE sq; CREATE TABLE t (id bigint DEFAULT nextval('sq')); "
                        + "ALTER SEQUENCE sq OWNED BY t.id",
                ch -> has(ch, ChangeAction.CREATE, ChangeSafety.ADDITIVE, "CREATE SEQUENCE", "OWNED BY")),
        new Row("DRIFTED sequence: ALTER SEQUENCE, DESTRUCTIVE",
                "CREATE SEQUENCE ${ns}.sq INCREMENT BY 1",
                "CREATE SEQUENCE sq INCREMENT BY 5",
                ch -> has(ch, ChangeAction.ALTER, ChangeSafety.DESTRUCTIVE, "ALTER SEQUENCE")),
        new Row("MISSING routine", "",
                "CREATE FUNCTION f(a int) RETURNS int LANGUAGE sql AS $$ SELECT a $$",
                ch -> has(ch, ChangeAction.CREATE, ChangeSafety.ADDITIVE, "FUNCTION")),
        new Row("DRIFTED routine, same return type: CREATE OR REPLACE as ALTER/REBUILD",
                "CREATE FUNCTION ${ns}.f(a int) RETURNS int LANGUAGE sql AS $$ SELECT a $$",
                "CREATE FUNCTION f(a int) RETURNS int LANGUAGE sql AS $$ SELECT a + 1 $$",
                ch -> has(ch, ChangeAction.ALTER, ChangeSafety.REBUILD, "CREATE OR REPLACE")),
        new Row("DRIFTED routine, return type changed: DROP FUNCTION then CREATE",
                "CREATE FUNCTION ${ns}.f(a int) RETURNS int LANGUAGE sql AS $$ SELECT a $$",
                "CREATE FUNCTION f(a int) RETURNS bigint LANGUAGE sql AS $$ SELECT a::bigint $$",
                ch -> has(ch, ChangeAction.DROP, ChangeSafety.REBUILD, "DROP FUNCTION")
                        && has(ch, ChangeAction.CREATE, ChangeSafety.REBUILD, "FUNCTION")),
        new Row("MISSING trigger with a non-default enabled state",
                "CREATE TABLE ${ns}.t (id bigint); CREATE FUNCTION ${ns}.trg() RETURNS trigger"
                        + " LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$",
                "CREATE TABLE t (id bigint); CREATE FUNCTION trg() RETURNS trigger LANGUAGE plpgsql"
                        + " AS $$ BEGIN RETURN NEW; END $$; CREATE TRIGGER tr BEFORE INSERT ON t"
                        + " FOR EACH ROW EXECUTE FUNCTION trg(); ALTER TABLE t DISABLE TRIGGER tr",
                ch -> has(ch, ChangeAction.CREATE, ChangeSafety.ADDITIVE, "CREATE TRIGGER", "DISABLE TRIGGER")),
        new Row("DRIFTED column: default and DROP NOT NULL in one REBUILD ALTER",
                "CREATE TABLE ${ns}.t (id bigint, c text NOT NULL)",
                "CREATE TABLE t (id bigint, c text DEFAULT 'x')",
                ch -> has(ch, ChangeAction.ALTER, ChangeSafety.REBUILD, "SET DEFAULT", "DROP NOT NULL")),
        new Row("DRIFTED column: widening TYPE",
                "CREATE TABLE ${ns}.t (id bigint, c int)", "CREATE TABLE t (id bigint, c bigint)",
                ch -> has(ch, ChangeAction.ALTER, ChangeSafety.REBUILD, "TYPE bigint")),
        new Row("DRIFTED column also needing SET NOT NULL: second DESTRUCTIVE change",
                "CREATE TABLE ${ns}.t (id bigint, c text DEFAULT 'a')",
                "CREATE TABLE t (id bigint, c text NOT NULL DEFAULT 'b')",
                ch -> has(ch, ChangeAction.ALTER, ChangeSafety.REBUILD, "SET DEFAULT")
                        && has(ch, ChangeAction.ALTER, ChangeSafety.DESTRUCTIVE, "SET NOT NULL")),
        new Row("DRIFTED column: generated change is manual DESTRUCTIVE",
                "CREATE TABLE ${ns}.t (id bigint, g int)",
                "CREATE TABLE t (id bigint, g int GENERATED ALWAYS AS (id + 1) STORED)",
                ch -> manual(ch, ChangeSafety.DESTRUCTIVE)),
        new Row("DRIFTED column: non-widening type is manual DESTRUCTIVE",
                "CREATE TABLE ${ns}.t (id bigint, c bigint)", "CREATE TABLE t (id bigint, c int)",
                ch -> manual(ch, ChangeSafety.DESTRUCTIVE)),
        new Row("DRIFTED table: manual DESTRUCTIVE",
                "CREATE TABLE ${ns}.t (id bigint)", "CREATE UNLOGGED TABLE t (id bigint)",
                ch -> manual(ch, ChangeSafety.DESTRUCTIVE)),
        new Row("table-shape conflict: unexpected column is manual DESTRUCTIVE",
                "CREATE TABLE ${ns}.t (id bigint, foreign_col text)", "CREATE TABLE t (id bigint)",
                ch -> manual(ch, ChangeSafety.DESTRUCTIVE)),
    };

    @Test
    void everyRowOfTheRenderingTableHolds() throws Exception {
        final var ds = PgTestDatabase.dataSource();
        final var failures = new StringBuilder();
        for (final var row : ROWS) {
            final var ns = "rnd_" + Long.toHexString(System.nanoTime()) + "_" + SEQUENCE.incrementAndGet();
            PgTestDatabase.execute(ds, "CREATE SCHEMA " + ns);
            try {
                if (!row.target().isEmpty()) {
                    PgTestDatabase.execute(ds, row.target().replace("${ns}", ns));
                }
                final var plan = SchemaReconciler.builder().dataSource(ds).namespace(ns)
                        .desiredState(DesiredState.ofScript(row.desired()))
                        .dialect(new PostgreSqlDialect()).build().plan();
                if (!row.expected().test(plan.changes())) {
                    failures.append(row.name()).append(" -> ").append(plan.changes().stream()
                            .map(c -> c.action() + "/" + c.safety() + (c.isManual() ? "/MANUAL" : "")
                                    + " " + c.statements()).toList()).append('\n');
                }
            } finally {
                PgTestDatabase.execute(ds, "DROP SCHEMA " + ns + " CASCADE");
            }
        }
        assertTrue(failures.isEmpty(), failures.toString());
    }
}
