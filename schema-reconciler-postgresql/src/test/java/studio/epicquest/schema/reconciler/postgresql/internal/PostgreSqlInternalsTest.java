package studio.epicquest.schema.reconciler.postgresql.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.DesiredStateException;
import studio.epicquest.schema.reconciler.postgresql.PostgreSqlDialect;
import studio.epicquest.schema.reconciler.spi.Dialect;
import studio.epicquest.schema.reconciler.model.ChangeAction;
import studio.epicquest.schema.reconciler.model.ChangeSafety;
import studio.epicquest.schema.reconciler.model.Definition;
import studio.epicquest.schema.reconciler.model.Difference;
import studio.epicquest.schema.reconciler.model.DifferenceType;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.SchemaObject;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;

/** Unit coverage of the PostgreSQL internals that do not need a database. */
class PostgreSqlInternalsTest {

    private static SchemaObject obj(ObjectId id, String def, Map<String, String> attrs) {
        return new SchemaObject(id, new Definition(def), Set.of(), attrs);
    }

    private static SchemaSnapshot snap(SchemaObject... objects) {
        final var map = new LinkedHashMap<ObjectId, SchemaObject>();
        for (final var o : objects) {
            map.put(o.id(), o);
        }
        return SchemaSnapshot.of(map);
    }

    // ---- PgIdentifiers -----------------------------------------------------

    @Test
    void quoteDoublesEmbeddedQuotes() {
        assertEquals("\"plain\"", PgIdentifiers.quote("plain"));
        assertEquals("\"has\"\"quote\"", PgIdentifiers.quote("has\"quote"));
        assertEquals("\" space \"", PgIdentifiers.quote(" space "));
    }

    @Test
    void quoteRejectsIllegalIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> PgIdentifiers.quote(""));
        assertThrows(IllegalArgumentException.class, () -> PgIdentifiers.quote("a\0b"));
        assertThrows(IllegalArgumentException.class,
                () -> PgIdentifiers.quote("x".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> PgIdentifiers.quote(null));
    }

    @Test
    void scratchNameFormatAndLimit() {
        final var name = PgIdentifiers.scratchName("app", 0x1A2B3CL);
        assertTrue(name.startsWith("app__rcl_"), name);
        assertEquals("app", name.substring(0, name.indexOf("__rcl_")));
        final var longNs = "n".repeat(100);
        assertTrue(PgIdentifiers.scratchName(longNs, 1).getBytes().length <= 63);
    }

    @Test
    void constantDefaultLexicalRule() {
        assertTrue(PgIdentifiers.isConstantDefault("42"));
        assertTrue(PgIdentifiers.isConstantDefault("-3.14"));
        assertTrue(PgIdentifiers.isConstantDefault("'text'"));
        assertTrue(PgIdentifiers.isConstantDefault("'it''s'"));
        assertTrue(PgIdentifiers.isConstantDefault("true"));
        assertTrue(PgIdentifiers.isConstantDefault("NULL"));
        assertTrue(PgIdentifiers.isConstantDefault("42::bigint"));
        assertTrue(PgIdentifiers.isConstantDefault("'x'::text"));

        assertFalse(PgIdentifiers.isConstantDefault("now()"));
        assertFalse(PgIdentifiers.isConstantDefault("nextval('s'::regclass)"));
        assertFalse(PgIdentifiers.isConstantDefault("a + b"));
        assertFalse(PgIdentifiers.isConstantDefault("gen_random_uuid()"));
        assertFalse(PgIdentifiers.isConstantDefault("1::int::bigint"));
        assertFalse(PgIdentifiers.isConstantDefault("'unterminated"));
        assertFalse(PgIdentifiers.isConstantDefault(null));

        // anything past the cast's type name is an expression, not a plain cast
        assertFalse(PgIdentifiers.isConstantDefault("1::int + 2"));
        assertFalse(PgIdentifiers.isConstantDefault("1::int + nextval('s')"));
        assertFalse(PgIdentifiers.isConstantDefault("'x'::text || 'y'"));
        assertFalse(PgIdentifiers.isConstantDefault("1::int/*evil*/"));
        assertFalse(PgIdentifiers.isConstantDefault("0::int; DROP TABLE t"));
        assertFalse(PgIdentifiers.isConstantDefault("1::unknown-func()"));
        assertFalse(PgIdentifiers.isConstantDefault("now()::timestamptz"));

        // legitimate single casts still qualify
        assertTrue(PgIdentifiers.isConstantDefault("42::numeric(10,2)"));
        assertTrue(PgIdentifiers.isConstantDefault("1::int[]"));
        assertTrue(PgIdentifiers.isConstantDefault("'x'::pg_catalog.text"));
        assertTrue(PgIdentifiers.isConstantDefault("7::\"Weird Type\""));
        assertTrue(PgIdentifiers.isConstantDefault("1 :: int"));
    }

    // ---- registration ------------------------------------------------------

    @Test
    void dialectIsDiscoverableViaServiceLoader() {
        // spec 2.2: META-INF/services for class-path use, module-info for JPMS
        assertTrue(ServiceLoader.load(Dialect.class).stream()
                .anyMatch(p -> p.type() == PostgreSqlDialect.class));
    }

    // ---- PgSql -------------------------------------------------------------

    @Test
    void statementsSplitOnSemicolonsNotInsideLiterals() {
        final var statements = PgSql.statements(
                "CREATE TABLE t (a text DEFAULT ';');"
                        + " CREATE FUNCTION f() RETURNS void LANGUAGE plpgsql AS $$"
                        + " BEGIN PERFORM 1; PERFORM 2; END $$;"
                        + " CREATE TABLE u (b int)");
        assertEquals(3, statements.size());
        assertEquals("CREATE", statements.get(1).tokens().get(0));
        assertTrue(statements.get(1).text().contains("PERFORM"));
    }

    @Test
    void dollarQuotedTagsRespected() {
        final var statements = PgSql.statements(
                "SELECT $body$a;b$c$body$; SELECT 2");
        assertEquals(2, statements.size());
    }

    @Test
    void commentsAreSkipped() {
        final var statements = PgSql.statements(
                "-- a comment ; with semicolon\nCREATE TABLE t (a int) /* block ; */");
        assertEquals(1, statements.size());
    }

    @Test
    void namespaceReferenceNormalisation() {
        assertEquals("SELECT * FROM ${namespace}.t",
                PgSql.replaceNamespaceRefs("SELECT * FROM \"app\".t", "app"));
        assertEquals("SELECT * FROM ${namespace}.t",
                PgSql.replaceNamespaceRefs("SELECT * FROM app.t", "app"));
        assertEquals("SELECT * FROM ${namespace}.t",
                PgSql.replaceNamespaceRefs("SELECT * FROM APP.t", "app"));
        // no false positives on prefixes or different schemas
        assertEquals("SELECT * FROM apple.t",
                PgSql.replaceNamespaceRefs("SELECT * FROM apple.t", "app"));
        assertEquals("SELECT * FROM other.t",
                PgSql.replaceNamespaceRefs("SELECT * FROM other.t", "app"));
    }

    @Test
    void quotedNamespaceMatchIsCaseSensitive() {
        // "APP" is a different schema from app — quoting makes it case-sensitive
        assertEquals("SELECT * FROM \"APP\".t",
                PgSql.replaceNamespaceRefs("SELECT * FROM \"APP\".t", "app"));
        assertEquals("SELECT * FROM \"Mixed\".t",
                PgSql.replaceNamespaceRefs("SELECT * FROM \"Mixed\".t", "MIXED"));
        // the exact quoted spelling still matches
        assertEquals("SELECT * FROM ${namespace}.t",
                PgSql.replaceNamespaceRefs("SELECT * FROM \"MIXED\".t", "MIXED"));
    }

    @Test
    void collapseWhitespaceLeavesLiteralsAlone() {
        assertEquals("SELECT 'a  b' FROM t",
                PgSql.collapseWhitespace("  SELECT   'a  b'\n  FROM   t  "));
        assertEquals("CREATE FUNCTION f() RETURNS void AS $$ x  y $$",
                PgSql.collapseWhitespace("CREATE FUNCTION f() RETURNS void AS $$ x  y $$"));
    }

    // ---- PgScriptCheck ------------------------------------------------------

    @Test
    void transactionControlRejected() {
        for (final var sql : new String[] {"COMMIT", "BEGIN", "START TRANSACTION",
                "ROLLBACK", "SAVEPOINT s", "RELEASE s", "END", "ABORT"}) {
            assertThrows(DesiredStateException.class, () -> PgScriptCheck.check(sql),
                    sql);
        }
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("CREATE TABLE t (a int); COMMIT"));
    }

    @Test
    void nonTransactionalStatementsRejected() {
        assertThrows(DesiredStateException.class, () -> PgScriptCheck.check("VACUUM t"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("CREATE INDEX i ON t (a) CONCURRENTLY"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("REINDEX SCHEMA app"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("REINDEX (TABLESPACE ts) DATABASE db"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("REINDEX SYSTEM db"));
        PgScriptCheck.check("REINDEX TABLE t");
        PgScriptCheck.check("REINDEX (TABLESPACE ts) INDEX i");
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("CREATE SCHEMA foo"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("DROP SCHEMA foo"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("ALTER SYSTEM SET x = 1"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("SELECT pg_advisory_lock(1)"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("SELECT pg_advisory_lock_shared(1)"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("SELECT pg_try_advisory_lock(1)"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("SELECT pg_try_advisory_lock_shared(1)"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("SELECT pg_catalog.pg_advisory_lock(1)"));
        // REPACK without a table and the CONCURRENTLY form cannot run inside
        // a transaction block; REPACK t is transactional and confined
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("REPACK"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("REPACK USING INDEX"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("REPACK (CONCURRENTLY) t USING INDEX"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("WAIT FOR LSN '0/0'"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("WAIT FOR LSN '1/ABC' WITH (MODE 'standby_replay')"));
        // transaction-scoped variants and unlock functions take no session
        // lock; LISTEN/NOTIFY are transactional, so their effect is confined
        // to the scratch connection (3.2 rule 4)
        PgScriptCheck.check("SELECT pg_advisory_xact_lock(1)");
        PgScriptCheck.check("SELECT pg_advisory_unlock_all()");
        PgScriptCheck.check("LISTEN chan");
        PgScriptCheck.check("NOTIFY chan");
        PgScriptCheck.check("UNLISTEN chan");
    }

    @Test
    void databaseWideDdlRejected() {
        // objects with no namespace anchor can never appear in a snapshot (5.4)
        for (final var sql : new String[] {
                "CREATE SERVER s FOREIGN DATA WRAPPER w",
                "DROP SERVER s",
                "CREATE PUBLICATION p FOR TABLE t",
                "CREATE FOREIGN DATA WRAPPER w",
                "CREATE USER MAPPING FOR u SERVER s",
                "CREATE EVENT TRIGGER e ON ddl_command_start EXECUTE FUNCTION f()",
                "CREATE ACCESS METHOD m TYPE INDEX HANDLER h",
                "CREATE ROLE r",
                "DROP ROLE r",
                "ALTER USER u PASSWORD 'x'",
                "CREATE GROUP g",
                "ALTER GROUP g ADD USER u",
                "CREATE LANGUAGE l",
                "CREATE TRUSTED PROCEDURAL LANGUAGE l HANDLER h",
                "ALTER DEFAULT PRIVILEGES GRANT SELECT ON TABLES TO r",
                "DROP OWNED BY r",
                "REASSIGN OWNED BY a TO b",
                "GRANT admin_role TO member_role",
                "REVOKE admin_role FROM member_role",
                "ALTER TABLE t SET SCHEMA other",
                "ALTER EXTENSION e SET SCHEMA other"}) {
            assertThrows(DesiredStateException.class, () -> PgScriptCheck.check(sql),
                    sql);
        }
        // namespace-anchored globals stay declarable: they are detected as OTHER
        PgScriptCheck.check("CREATE CAST (int AS text) WITH FUNCTION f(int)");
        PgScriptCheck.check("CREATE TRANSFORM FOR t LANGUAGE plpgsql (FROM SQL WITH FUNCTION f)");
        PgScriptCheck.check("GRANT SELECT ON t TO r");
        PgScriptCheck.check("GRANT USAGE ON SCHEMA s TO r");
        PgScriptCheck.check("CREATE FOREIGN TABLE t (a int) SERVER s OPTIONS (x '1')");
        PgScriptCheck.check("IMPORT FOREIGN SCHEMA s FROM SERVER srv INTO ns");
    }

    @Test
    void legitimateScriptsPass() {
        PgScriptCheck.check("""
                CREATE TABLE t (a bigint PRIMARY KEY, b text);
                CREATE INDEX i ON t (b);
                CREATE OR REPLACE FUNCTION f() RETURNS trigger LANGUAGE plpgsql
                    AS $$ BEGIN RETURN NEW; END $$;
                CREATE TRIGGER tr BEFORE INSERT ON t FOR EACH ROW EXECUTE FUNCTION f()
                """);
    }

    @Test
    void banAppliesInsideSubstitutedScript() {
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("CREATE SCHEMA \"some_scratch_ns\""));
    }

    @Test
    void crossNamespaceCreationRejected() {
        final var scratch = "app__rcl_deadbeef";
        for (final var sql : new String[] {
                "CREATE TABLE other.t (a int)",
                "CREATE TABLE \"Other\".t (a int)",
                "DROP TABLE other.t",
                "ALTER TABLE other.t ADD COLUMN a int",
                "CREATE INDEX i ON other.t (a)",
                "CREATE TRIGGER g BEFORE INSERT ON other.t FOR EACH ROW EXECUTE FUNCTION f()",
                "CREATE POLICY p ON other.t",
                "GRANT SELECT ON TABLE other.t TO r",
                "GRANT USAGE ON SCHEMA other TO r",
                "GRANT SELECT ON ALL TABLES IN SCHEMA other TO r",
                "COMMENT ON TABLE other.t IS 'x'",
                "COMMENT ON COLUMN other.t.c IS 'x'",
                "GRANT SELECT ON DATABASE d TO r"}) {
            assertThrows(DesiredStateException.class,
                    () -> PgScriptCheck.check(sql, scratch), sql);
        }
    }

    @Test
    void scratchQualifiedAndReferenceNamesAllowed() {
        final var scratch = "app__rcl_deadbeef";
        // qualified to the scratch namespace — the post-substitution ${namespace} form
        PgScriptCheck.check("CREATE TABLE " + scratch + ".t (a int)", scratch);
        PgScriptCheck.check("CREATE OR REPLACE FUNCTION " + scratch
                + ".f() RETURNS int LANGUAGE sql AS $$ SELECT 1 $$", scratch);
        PgScriptCheck.check("GRANT USAGE ON SCHEMA " + scratch + " TO r", scratch);
        PgScriptCheck.check("GRANT SELECT ON TABLE " + scratch + ".t TO r", scratch);
        PgScriptCheck.check("COMMENT ON TABLE " + scratch + ".t IS 'x'", scratch);
        PgScriptCheck.check("COMMENT ON COLUMN t.c IS 'x'", scratch);
        // unqualified names resolve inside the scratch search_path
        PgScriptCheck.check("CREATE TABLE t (a int)", scratch);
        // references to other namespaces are not creation positions:
        // a cross-schema foreign key is a legitimate external dependency (5.8.3)
        PgScriptCheck.check("ALTER TABLE " + scratch
                + ".t ADD CONSTRAINT fk FOREIGN KEY (a) REFERENCES ext.pt (id)", scratch);
        // temporary objects are session-confined like the scratch namespace
        PgScriptCheck.check("CREATE TABLE pg_temp.t (a int)", scratch);
    }

    @Test
    void sessionSetRejected() {
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("SET search_path = x"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("SET SESSION statement_timeout = 0"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("RESET search_path"));
        assertThrows(DesiredStateException.class,
                () -> PgScriptCheck.check("LOAD 'mod'"));
        // transaction-scoped settings are confined to the run
        PgScriptCheck.check("SET LOCAL search_path = x");
        PgScriptCheck.check("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE");
        PgScriptCheck.check("SET CONSTRAINTS ALL DEFERRED");
    }

    // ---- PgRender ------------------------------------------------------------

    private static Difference missing(ObjectId id) {
        return new Difference(id, DifferenceType.MISSING,
                Optional.of(new Definition("d")), Optional.empty());
    }

    private static Difference drifted(ObjectId id) {
        return new Difference(id, DifferenceType.DRIFTED,
                Optional.of(new Definition("want")), Optional.of(new Definition("have")));
    }

    @Test
    void nameScopesFollowTheATable() {
        assertEquals(Set.of("relation:t"),
                PgRender.nameScopes(new ObjectId(ObjectKind.TABLE, "t")));
        assertEquals(Set.of("relation:i"),
                PgRender.nameScopes(new ObjectId(ObjectKind.INDEX, "i", "t")));
        assertEquals(Set.of("relation:pk", "constraint:t.pk"),
                PgRender.nameScopes(new ObjectId(ObjectKind.PRIMARY_KEY, "pk", "t")));
        assertEquals(Set.of("constraint:t.fk"),
                PgRender.nameScopes(new ObjectId(ObjectKind.FOREIGN_KEY, "fk", "t")));
        assertTrue(PgRender.nameScopes(new ObjectId(ObjectKind.COLUMN, "c", "t")).isEmpty());
        assertTrue(PgRender.nameScopes(new ObjectId(ObjectKind.TRIGGER, "g", "t")).isEmpty());
    }

    @Test
    void coveredChildrenForTable() {
        final var table = new ObjectId(ObjectKind.TABLE, "t");
        final var seq = new SchemaObject(new ObjectId(ObjectKind.SEQUENCE, "t_id_seq"),
                new Definition("seq"), Set.of(table),
                Map.of(PgCatalog.CREATE_SQL, "CREATE SEQUENCE ${namespace}.\"t_id_seq\""));
        final var desired = snap(
                obj(table, "t", Map.of()),
                obj(new ObjectId(ObjectKind.COLUMN, "id", "t"), "c",
                        Map.of(PgCatalog.COLUMN_SQL, "\"id\" bigint", PgCatalog.ATTNUM, "1")),
                seq);
        final var covered = PgRender.coveredChildren(missing(table), desired);
        assertEquals(Set.of(new ObjectId(ObjectKind.COLUMN, "id", "t"),
                new ObjectId(ObjectKind.SEQUENCE, "t_id_seq")), covered);
        assertTrue(PgRender.coveredChildren(
                missing(new ObjectId(ObjectKind.VIEW, "v")), desired).isEmpty());
    }

    @Test
    void missingTableRendersOneCreate() {
        final var table = new ObjectId(ObjectKind.TABLE, "t");
        final var desired = snap(
                obj(table, "relkind=r;relpersistence=p", Map.of()),
                obj(new ObjectId(ObjectKind.COLUMN, "id", "t"), "c",
                        Map.of(PgCatalog.COLUMN_SQL, "\"id\" bigint NOT NULL",
                                PgCatalog.ATTNUM, "1")),
                obj(new ObjectId(ObjectKind.COLUMN, "nm", "t"), "c",
                        Map.of(PgCatalog.COLUMN_SQL, "\"nm\" text", PgCatalog.ATTNUM, "2")));
        final var changes = PgRender.render(missing(table), desired, "core");
        assertEquals(1, changes.size());
        final var change = changes.get(0);
        assertEquals(ChangeAction.CREATE, change.action());
        assertEquals(ChangeSafety.ADDITIVE, change.safety());
        final var sql = change.statements().get(0);
        assertTrue(sql.contains("CREATE TABLE \"core\".\"t\""), sql);
        assertTrue(sql.contains("\"id\" bigint NOT NULL"), sql);
        assertTrue(sql.indexOf("\"id\"") < sql.indexOf("\"nm\""),
                "columns must render in attnum order: " + sql);
    }

    @Test
    void missingTableFoldsSerialSequence() {
        final var table = new ObjectId(ObjectKind.TABLE, "t");
        final var seq = new ObjectId(ObjectKind.SEQUENCE, "t_id_seq");
        final var seqObj = new SchemaObject(seq, new Definition("seq"), Set.of(table),
                Map.of(PgCatalog.CREATE_SQL, "CREATE SEQUENCE ${namespace}.\"t_id_seq\" AS bigint",
                        PgCatalog.OWNED_SQL,
                        "ALTER SEQUENCE ${namespace}.\"t_id_seq\" OWNED BY ${namespace}.t.id"));
        final var desired = snap(
                obj(table, "relkind=r;relpersistence=p", Map.of()),
                obj(new ObjectId(ObjectKind.COLUMN, "id", "t"), "c",
                        Map.of(PgCatalog.COLUMN_SQL,
                                "\"id\" bigint DEFAULT nextval('${namespace}.\"t_id_seq\"'::regclass) NOT NULL",
                                PgCatalog.ATTNUM, "1")),
                seqObj);
        final var changes = PgRender.render(missing(table), desired, "core");
        final var statements = changes.get(0).statements();
        assertEquals(3, statements.size(), statements.toString());
        assertTrue(statements.get(0).startsWith("CREATE SEQUENCE \"core\""));
        assertTrue(statements.get(1).startsWith("CREATE TABLE"));
        assertTrue(statements.get(2).contains("OWNED BY"));
    }

    @Test
    void missingColumnVariants() {
        final var col = new ObjectId(ObjectKind.COLUMN, "c", "t");
        // nullable → ADDITIVE
        final var nullable = snap(obj(col, "c",
                Map.of(PgCatalog.COLUMN_SQL, "\"c\" text", PgCatalog.NOTNULL, "false")));
        final var n = PgRender.render(missing(col), nullable, "core").get(0);
        assertEquals(ChangeSafety.ADDITIVE, n.safety());
        assertTrue(n.statements().get(0).contains("ADD COLUMN"), n.statements().toString());

        // NOT NULL + constant default → ADDITIVE
        final var constant = snap(obj(col, "c",
                Map.of(PgCatalog.COLUMN_SQL, "\"c\" bigint DEFAULT 0 NOT NULL",
                        PgCatalog.NOTNULL, "true", PgCatalog.DEFAULT, "0")));
        assertEquals(ChangeSafety.ADDITIVE,
                PgRender.render(missing(col), constant, "core").get(0).safety());

        // NOT NULL + volatile default → manual DESTRUCTIVE
        final var volatileDefault = snap(obj(col, "c",
                Map.of(PgCatalog.COLUMN_SQL, "\"c\" timestamptz DEFAULT now() NOT NULL",
                        PgCatalog.NOTNULL, "true", PgCatalog.DEFAULT, "now()")));
        final var v = PgRender.render(missing(col), volatileDefault, "core").get(0);
        assertEquals(ChangeSafety.DESTRUCTIVE, v.safety());
        assertTrue(v.isManual());

        // NOT NULL without default → manual DESTRUCTIVE
        final var noDefault = snap(obj(col, "c",
                Map.of(PgCatalog.COLUMN_SQL, "\"c\" bigint NOT NULL",
                        PgCatalog.NOTNULL, "true")));
        final var m = PgRender.render(missing(col), noDefault, "core").get(0);
        assertEquals(ChangeSafety.DESTRUCTIVE, m.safety());
        assertTrue(m.isManual());
    }

    @Test
    void driftedViewIsDropCreatePair() {
        final var view = new ObjectId(ObjectKind.VIEW, "v");
        final var desired = snap(obj(view, "viewdef",
                Map.of(PgCatalog.CREATE_SQL, "CREATE VIEW ${namespace}.\"v\" AS SELECT 1",
                        PgCatalog.DROP_SQL, "DROP VIEW ${namespace}.\"v\"")));
        final var changes = PgRender.render(drifted(view), desired, "core");
        assertEquals(2, changes.size());
        assertEquals(ChangeAction.DROP, changes.get(0).action());
        assertEquals(ChangeAction.CREATE, changes.get(1).action());
        assertEquals("DROP VIEW \"core\".\"v\"", changes.get(0).statements().get(0));
        assertFalse(changes.get(0).statements().get(0).contains("CASCADE"),
                "no CASCADE anywhere (spec)");
    }

    @Test
    void driftedTriggerWithPostSqlRendersWithoutCrashing() {
        final var trg = new ObjectId(ObjectKind.TRIGGER, "g", "t");
        final var desired = snap(obj(trg, "tgdef;enabled=D",
                Map.of(PgCatalog.CREATE_SQL,
                        "CREATE TRIGGER \"g\" BEFORE INSERT ON ${namespace}.\"t\""
                                + " FOR EACH ROW EXECUTE FUNCTION ${namespace}.\"f\"()",
                        PgCatalog.DROP_SQL, "DROP TRIGGER \"g\" ON ${namespace}.\"t\"",
                        PgCatalog.POST_SQL, "ALTER TABLE ${namespace}.\"t\" DISABLE TRIGGER \"g\"")));
        final var pair = PgRender.render(drifted(trg), desired, "core");
        assertEquals(List.of(ChangeAction.DROP, ChangeAction.CREATE),
                pair.stream().map(PlannedChange::action).toList());
        assertTrue(pair.get(1).statements().get(1).contains("DISABLE TRIGGER"),
                pair.get(1).statements().toString());
    }

    @Test
    void driftedRoutineSameReturnTypeIsAlter() {
        final var fn = new ObjectId(ObjectKind.ROUTINE, "f()");
        final var desired = snap(obj(fn, "def",
                Map.of(PgCatalog.CREATE_SQL,
                        "CREATE OR REPLACE FUNCTION ${namespace}.\"f\"() RETURNS void LANGUAGE sql AS 'SELECT 1'",
                        PgCatalog.RETURN_TYPE, "void",
                        PgCatalog.DROP_SQL, "DROP FUNCTION ${namespace}.\"f\"()")));
        final var d = new Difference(fn, DifferenceType.DRIFTED,
                Optional.of(new Definition("w")),
                Optional.of(new Definition("RETURNS void LANGUAGE x")));
        final var changes = PgRender.render(d, desired, "core");
        assertEquals(1, changes.size());
        assertEquals(ChangeAction.ALTER, changes.get(0).action());
        assertEquals(ChangeSafety.REBUILD, changes.get(0).safety());

        // return type changed → DROP+CREATE
        final var d2 = new Difference(fn, DifferenceType.DRIFTED,
                Optional.of(new Definition("w")),
                Optional.of(new Definition("RETURNS integer LANGUAGE x")));
        final var pair = PgRender.render(d2, desired, "core");
        assertEquals(List.of(ChangeAction.DROP, ChangeAction.CREATE),
                pair.stream().map(PlannedChange::action).toList());
    }

    @Test
    void driftedSetofRoutineSameReturnTypeIsAlter() {
        final var fn = new ObjectId(ObjectKind.ROUTINE, "f()");
        final var desired = snap(obj(fn, "def",
                Map.of(PgCatalog.CREATE_SQL,
                        "CREATE OR REPLACE FUNCTION ${namespace}.\"f\"() RETURNS SETOF integer LANGUAGE sql AS 'SELECT 1'",
                        PgCatalog.RETURN_TYPE, "SETOF integer",
                        PgCatalog.DROP_SQL, "DROP FUNCTION ${namespace}.\"f\"()")));
        final var d = new Difference(fn, DifferenceType.DRIFTED,
                Optional.of(new Definition("w")),
                Optional.of(new Definition("RETURNS SETOF integer LANGUAGE x")));
        final var changes = PgRender.render(d, desired, "core");
        assertEquals(1, changes.size());
        assertEquals(ChangeAction.ALTER, changes.get(0).action());
    }

    @Test
    void driftedColumnIsManualDestructive() {
        final var col = new ObjectId(ObjectKind.COLUMN, "c", "t");
        final var desired = snap(obj(col, "c", Map.of()));
        final var change = PgRender.render(drifted(col), desired, "core").get(0);
        assertEquals(ChangeAction.ALTER, change.action());
        assertEquals(ChangeSafety.DESTRUCTIVE, change.safety());
        assertTrue(change.isManual());
    }

    private static Difference driftedColumn(String want, String have) {
        return new Difference(new ObjectId(ObjectKind.COLUMN, "c", "t"),
                DifferenceType.DRIFTED,
                Optional.of(new Definition(want)), Optional.of(new Definition(have)));
    }

    private static SchemaSnapshot columnSnap(String canonical) {
        return snap(obj(new ObjectId(ObjectKind.COLUMN, "c", "t"), canonical, Map.of()));
    }

    @Test
    void driftedColumnDefaultAltersInPlace() {
        final var d = driftedColumn("type=text;notnull=false;default='b'::text",
                "type=text;notnull=false;default='a'::text");
        final var change = PgRender.render(d, columnSnap(
                "type=text;notnull=false;default='b'::text"), "core").get(0);
        assertEquals(ChangeAction.ALTER, change.action());
        assertEquals(ChangeSafety.REBUILD, change.safety());
        assertEquals(List.of("ALTER TABLE \"core\".\"t\" ALTER COLUMN \"c\""
                + " SET DEFAULT 'b'::text"), change.statements());
    }

    @Test
    void driftedColumnNotNullSplitsByDataRisk() {
        // DROP NOT NULL is a metadata change (REBUILD); SET NOT NULL validates
        // existing rows and can fail on them (rendered DESTRUCTIVE, 5.6 rule 1)
        var d = driftedColumn("type=text;notnull=true",
                "type=text;notnull=false");
        var changes = PgRender.render(d, columnSnap("type=text;notnull=true"), "core");
        assertEquals(1, changes.size());
        assertEquals(ChangeSafety.DESTRUCTIVE, changes.get(0).safety());
        assertEquals(List.of("ALTER TABLE \"core\".\"t\" ALTER COLUMN \"c\" SET NOT NULL"),
                changes.get(0).statements());
        d = driftedColumn("type=text;notnull=false;default='a'::text",
                "type=text;notnull=true");
        var change = PgRender.render(d, columnSnap(
                "type=text;notnull=false;default='a'::text"), "core").get(0);
        assertEquals(List.of(
                "ALTER TABLE \"core\".\"t\" ALTER COLUMN \"c\" SET DEFAULT 'a'::text",
                "ALTER TABLE \"core\".\"t\" ALTER COLUMN \"c\" DROP NOT NULL"),
                change.statements());
        assertEquals(ChangeSafety.REBUILD, change.safety());
    }

    @Test
    void driftedColumnSetNotNullDefersToDestructiveChange() {
        // safe parts converge under the default policy; SET NOT NULL stays
        // gated — and the REBUILD half's expected omits the deferred notnull
        final var d = driftedColumn("type=text;notnull=true;default='a'::text",
                "type=text;notnull=false");
        final var changes = PgRender.render(d, columnSnap(
                "type=text;notnull=true;default='a'::text"), "core");
        assertEquals(2, changes.size());
        assertEquals(ChangeSafety.REBUILD, changes.get(0).safety());
        assertEquals(List.of("ALTER TABLE \"core\".\"t\" ALTER COLUMN \"c\""
                + " SET DEFAULT 'a'::text"), changes.get(0).statements());
        assertEquals("type=text;notnull=false;default='a'::text",
                changes.get(0).expected().orElseThrow().canonical());
        assertEquals(ChangeSafety.DESTRUCTIVE, changes.get(1).safety());
        assertEquals(List.of("ALTER TABLE \"core\".\"t\" ALTER COLUMN \"c\" SET NOT NULL"),
                changes.get(1).statements());
        assertEquals("type=text;notnull=true;default='a'::text",
                changes.get(1).expected().orElseThrow().canonical());
    }

    @Test
    void driftedColumnWideningTypeAltersInPlace() {
        for (final var pair : new String[][] {
                {"character varying(20)", "character varying(10)"},
                {"text", "character varying(10)"},
                {"character varying", "character varying(10)"},
                {"character(8)", "character(5)"},
                {"bigint", "integer"},
                {"integer", "smallint"},
                {"numeric(12,3)", "numeric(10,2)"},
                {"numeric(12,4)", "numeric(10,2)"},
                {"numeric", "numeric(10,2)"}}) {
            final var want = "type=" + pair[0] + ";notnull=false";
            final var have = "type=" + pair[1] + ";notnull=false";
            final var change = PgRender.render(driftedColumn(want, have),
                    columnSnap(want), "core").get(0);
            assertEquals(ChangeSafety.REBUILD, change.safety(), pair[0] + " <- " + pair[1]);
            assertEquals(List.of("ALTER TABLE \"core\".\"t\" ALTER COLUMN \"c\" TYPE "
                    + pair[0]), change.statements());
        }
    }

    @Test
    void driftedColumnUnsafeTypeStaysManual() {
        for (final var pair : new String[][] {
                {"character varying(10)", "character varying(20)"},   // narrowing
                {"character varying(10)", "text"},                    // bounded
                {"integer", "bigint"},                                // narrowing
                {"boolean", "text"},                                  // unrelated
                {"text", "character(5)"},                             // bpchar strips padding
                {"character varying(10)", "character(5)"},            // bpchar strips padding
                {"character(5)", "character varying(10)"},            // adds padding semantics
                {"numeric(10,4)", "numeric(10,2)"},                   // fewer integer digits
                {"numeric(10,2)", "numeric(10,4)"},                   // scale shrinks
                {"text", "integer"},
                {"timestamp with time zone", "timestamp without time zone"}}) {
            final var want = "type=" + pair[0] + ";notnull=false";
            final var have = "type=" + pair[1] + ";notnull=false";
            final var change = PgRender.render(driftedColumn(want, have),
                    columnSnap(want), "core").get(0);
            assertEquals(ChangeSafety.DESTRUCTIVE, change.safety(), pair[0] + " <- " + pair[1]);
            assertTrue(change.isManual());
        }
    }

    @Test
    void driftedColumnGeneratedOrIdentityStaysManual() {
        var change = PgRender.render(driftedColumn(
                "type=int;notnull=false;generated=s:(a + 1)",
                "type=int;notnull=false"),
                columnSnap("type=int;notnull=false;generated=s:(a + 1)"), "core").get(0);
        assertEquals(ChangeSafety.DESTRUCTIVE, change.safety());
        assertTrue(change.isManual());
        change = PgRender.render(driftedColumn(
                "type=bigint;notnull=false;identity=a;identityOptions=x",
                "type=bigint;notnull=false"),
                columnSnap("type=bigint;notnull=false;identity=a;identityOptions=x"),
                "core").get(0);
        assertEquals(ChangeSafety.DESTRUCTIVE, change.safety());
    }
}
