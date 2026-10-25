package studio.epicquest.schema.reconciler.postgresql.internal;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.DesiredStateException;

/** The statement lists of spec A.5 and Appendix C ("rejected" and "legal") against {@link PgScriptCheck}. */
class ScriptCheckSpecListsTest {

    private static final String SCRATCH = "scr";

    @Test
    void everyStatementTheSpecCallsRejectedIsRejected() {
        for (final var sql : new String[] {
                // cannot run inside a transaction block (marked * in Appendix C)
                "CREATE DATABASE d", "CREATE TABLESPACE ts LOCATION '/x'",
                "CREATE SUBSCRIPTION s CONNECTION 'x' PUBLICATION p",
                "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT sp", "VACUUM", "CHECKPOINT",
                "CLUSTER t", "DISCARD ALL", "LOAD 'x'", "REPACK t",
                "CREATE INDEX CONCURRENTLY i ON t (a)", "REINDEX SCHEMA scr",
                // no namespace anchor
                "CREATE PUBLICATION p FOR TABLE t", "ALTER PUBLICATION p ADD TABLE t",
                "CREATE EVENT TRIGGER e ON ddl_command_start EXECUTE FUNCTION f()",
                "CREATE SERVER s FOREIGN DATA WRAPPER w", "CREATE FOREIGN DATA WRAPPER w",
                "CREATE USER MAPPING FOR u SERVER s", "CREATE ACCESS METHOD m TYPE INDEX HANDLER h",
                "CREATE LANGUAGE l", "CREATE ROLE r", "CREATE GROUP g", "CREATE USER u",
                "ALTER DEFAULT PRIVILEGES GRANT SELECT ON TABLES TO r",
                "REASSIGN OWNED BY a TO b", "DROP OWNED BY a", "ALTER SYSTEM SET work_mem = 1",
                "ALTER TABLE t SET SCHEMA other", "GRANT admin_role TO member_role",
                "REVOKE admin_role FROM member_role",
                // session and utility statements
                "LISTEN c", "NOTIFY c", "UNLISTEN c", "SELECT pg_advisory_lock(1)",
                "WAIT FOR LSN '0/0'", "SET search_path = x", "RESET search_path",
                // namespace handling belongs to the core
                "CREATE SCHEMA s", "DROP SCHEMA s",
                // qualified by another schema
                "CREATE TABLE other.t (a int)", "DROP TABLE other.t",
                "CREATE INDEX i ON other.t (a)", "COMMENT ON TABLE other.t IS 'x'",
                "GRANT SELECT ON other.t TO PUBLIC", "GRANT USAGE ON SCHEMA other TO PUBLIC"}) {
            assertThrows(DesiredStateException.class, () -> PgScriptCheck.check(sql, SCRATCH), sql);
        }
    }

    @Test
    void everyStatementTheSpecCallsLegalPasses() {
        for (final var sql : new String[] {
                "CREATE FOREIGN TABLE ft (a int) SERVER srv",
                "IMPORT FOREIGN SCHEMA remote FROM SERVER srv INTO scr",
                "SET LOCAL work_mem = '1MB'", "SET TRANSACTION READ ONLY",
                "SET CONSTRAINTS ALL DEFERRED",
                "CREATE TABLE scr.t (a int REFERENCES other.parent (id))",
                "CREATE TABLE t (a int REFERENCES other.parent (id))",
                "CREATE TEMP TABLE pg_temp.x (a int)",
                "GRANT USAGE ON SCHEMA scr TO PUBLIC",
                "COMMENT ON TABLE scr.t IS 'x'",
                "SELECT set_config('app.x', '1', true)",
                "SELECT pg_advisory_xact_lock(1)"}) {
            PgScriptCheck.check(sql, SCRATCH);
        }
    }
}
