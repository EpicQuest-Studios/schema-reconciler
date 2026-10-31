import assert from "node:assert/strict";
import test from "node:test";
import { DesiredStateException } from "@schema-reconciler/core";
import * as PgScriptCheck from "../../src/internal/PgScriptCheck.ts";

/** The statement lists of spec A.5 and Appendix C ("rejected" and "legal") against {@link PgScriptCheck}. */

const SCRATCH = "scr";

test("everyStatementTheSpecCallsRejectedIsRejected", () => {
    for (const sql of [
            // cannot run inside a transaction block (marked * in Appendix C)
            "CREATE DATABASE d", "CREATE TABLESPACE ts LOCATION '/x'",
            "CREATE SUBSCRIPTION s CONNECTION 'x' PUBLICATION p",
            "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT sp", "VACUUM", "CHECKPOINT",
            "DISCARD ALL", "LOAD 'x'",
            // REPACK without a table, the USING INDEX-only form, and
            // CONCURRENTLY cannot run inside a transaction block;
            // REPACK t is confined like CLUSTER t and stays legal
            "REPACK", "REPACK (VERBOSE)", "REPACK USING INDEX",
            "REPACK (CONCURRENTLY) t", "REPACK (VERBOSE, CONCURRENTLY)",
            "CREATE INDEX CONCURRENTLY i ON t (a)", "REINDEX SCHEMA scr",
            // no namespace anchor
            "CREATE PUBLICATION p FOR TABLE t", "DROP PUBLICATION p",
            "ALTER PUBLICATION p SET (publish = 'insert')",
            "ALTER PUBLICATION p OWNER TO r", "ALTER PUBLICATION p RENAME TO q",
            "ALTER PUBLICATION p ADD TABLE other.t",
            "ALTER PUBLICATION p ADD TABLES IN SCHEMA other",
            "CREATE EVENT TRIGGER e ON ddl_command_start EXECUTE FUNCTION f()",
            "CREATE SERVER s FOREIGN DATA WRAPPER w", "CREATE FOREIGN DATA WRAPPER w",
            "CREATE USER MAPPING FOR u SERVER s", "CREATE ACCESS METHOD m TYPE INDEX HANDLER h",
            "CREATE LANGUAGE l", "CREATE ROLE r", "CREATE GROUP g", "CREATE USER u",
            "ALTER DEFAULT PRIVILEGES GRANT SELECT ON TABLES TO r",
            "ALTER DEFAULT PRIVILEGES FOR ROLE r GRANT SELECT ON TABLES TO r",
            "ALTER DEFAULT PRIVILEGES IN SCHEMA other GRANT SELECT ON TABLES TO r",
            "ALTER DEFAULT PRIVILEGES IN SCHEMA scr, other GRANT SELECT ON TABLES TO r",
            "ALTER DEFAULT PRIVILEGES IN SCHEMA scr OWNER TO r",
            "ALTER DEFAULT x",
            "REASSIGN OWNED BY a TO b", "DROP OWNED BY a", "ALTER SYSTEM SET work_mem = 1",
            "ALTER TABLE t SET SCHEMA other", "GRANT admin_role TO member_role",
            "REVOKE admin_role FROM member_role",
            // session and utility statements
            "SELECT pg_advisory_lock(1)",
            "WAIT FOR LSN '0/0'", "SET search_path = x", "RESET search_path",
            // namespace handling belongs to the core
            "CREATE SCHEMA s", "DROP SCHEMA s",
            // qualified by another schema — in name position, ON/TO targets,
            // name lists, and database-wide COMMENT/SECURITY LABEL targets
            "CREATE TABLE other.t (a int)", "DROP TABLE other.t",
            "CREATE INDEX i ON other.t (a)", "COMMENT ON TABLE other.t IS 'x'",
            // a keyword-named column does not end the target scan —
            // the qualifier must still be checked
            "COMMENT ON COLUMN other.t.is IS 'x'",
            "COMMENT ON COLUMN other.t.on IS 'x'",
            // a rule/provider named after the anchor keyword does not
            // shift the real anchor
            "CREATE RULE to AS ON INSERT TO other.t DO NOTHING",
            "SECURITY LABEL FOR on ON DATABASE d IS 'x'",
            "SECURITY LABEL FOR on ON TABLE other.t IS 'x'",
            "GRANT SELECT ON other.t TO PUBLIC", "GRANT USAGE ON SCHEMA other TO PUBLIC",
            'CREATE TABLE "Other".t (a int)', 'CREATE TABLE "SCR".t (a int)',
            "DROP TABLE t1, other.t2", "DROP RULE r ON other.t",
            "CREATE RULE r AS ON SELECT TO other.t DO INSTEAD NOTHING",
            "GRANT SELECT ON TABLE a, other.b TO PUBLIC",
            "GRANT SELECT ON FOREIGN TABLE other.ft TO PUBLIC",
            // a column named after a keyword folds to the delimiter
            // token — the real ON target must still be checked
            "GRANT SELECT (on) ON TABLE other.t TO r",
            "GRANT SELECT (a, on) ON TABLE other.t TO r",
            "CREATE TRIGGER g AFTER UPDATE OF a, on ON other.t EXECUTE FUNCTION f()",
            "IMPORT FOREIGN SCHEMA r LIMIT TO (into) FROM SERVER s INTO other",
            "GRANT USAGE ON FOREIGN SERVER s TO PUBLIC",
            "GRANT USAGE ON SCHEMA scr, other TO PUBLIC",
            "GRANT SELECT ON ALL TABLES IN SCHEMA scr, other TO r",
            // the qualifier is an unreserved keyword the modifier scan skips
            "CREATE TABLE exists.evil (a int)",
            "CREATE TABLE if.evil (a int)",
            "CREATE TABLE concurrently.evil (a int)",
            "CREATE TEXT SEARCH CONFIGURATION other.cfg ()",
            "CREATE OPERATOR FAMILY other.f USING btree",
            "SELECT 1 INTO TEMP if.evil",
            "COMMENT ON DATABASE d IS 'x'", "COMMENT ON SCHEMA other IS 'x'",
            "COMMENT ON PROCEDURAL LANGUAGE plpgsql IS 'x'",
            "SECURITY LABEL FOR p ON ROLE r IS 'x'",
            "SELECT * INTO other.t FROM src", "SELECT INTO other.t FROM src",
            "WITH x AS (SELECT 1) SELECT * INTO other.t FROM x",
            "IMPORT FOREIGN SCHEMA r FROM SERVER s INTO other",
            "ALTER INDEX ALL IN TABLESPACE ts", "ALTER LARGE OBJECT 1 OWNER TO u",
            "ALTER TABLE t ATTACH PARTITION other.c",
            "ALTER TABLE t DETACH PARTITION other.c",
            "ALTER INDEX i ATTACH PARTITION other.pi",
            // session statements, non-transactional sequence writes,
            // and DO bodies
            "DECLARE c CURSOR FOR SELECT 1", "EXECUTE x(1)", "DEALLOCATE x",
            "COPY t TO STDOUT",
            // bare CLUSTER re-clusters everything — cannot run in a
            // transaction block; CLUSTER t is confined and stays legal
            "CLUSTER", "CLUSTER VERBOSE", "CLUSTER (VERBOSE)",
            // catalog state outside the snapshot (6a): grants/comments
            // on the namespace object itself
            "GRANT USAGE ON SCHEMA scr TO PUBLIC",
            "GRANT USAGE ON SCHEMA scr, scr TO PUBLIC",
            "REVOKE USAGE ON SCHEMA scr FROM r",
            "COMMENT ON SCHEMA scr IS 'x'",
            "SECURITY LABEL FOR p ON SCHEMA scr IS 'x'",
            // clustering-index membership
            "CLUSTER t USING i", "CLUSTER VERBOSE t USING i",
            "CLUSTER other.t USING i",
            "ALTER TABLE t CLUSTER ON i", "ALTER TABLE t SET WITHOUT CLUSTER",
            // storage placement
            "CREATE TABLE t (a int) TABLESPACE ts",
            "CREATE TABLE t TABLESPACE ts",
            "CREATE INDEX i ON t (a) TABLESPACE ts",
            "ALTER TABLE t SET TABLESPACE ts",
            "ALTER INDEX i SET TABLESPACE ts",
            "ALTER MATERIALIZED VIEW mv SET TABLESPACE ts",
            "REINDEX (TABLESPACE ts) INDEX i", "REINDEX TABLE t TABLESPACE ts",
            "REINDEX INDEX i TABLESPACE ts",
            // typed tables and non-heap access methods
            "CREATE TABLE t OF ty", "CREATE TABLE t OF other.ty",
            "ALTER TABLE t OF ty", "ALTER TABLE t OF other.ty",
            "ALTER TABLE t NOT OF",
            "CREATE TABLE t (a int) USING heap",
            // planner tuning, runtime sequence state
            "ALTER TABLE t ALTER COLUMN c SET STATISTICS 100",
            "ALTER STATISTICS s SET STATISTICS 100",
            "ALTER INDEX i ALTER COLUMN 1 SET STATISTICS 100",
            "ALTER SEQUENCE s RESTART", "ALTER SEQUENCE s RESTART WITH 5",
            "ALTER TABLE t ALTER COLUMN c RESTART",
            // ownership on targets whose owner column is not modeled
            "ALTER INDEX i OWNER TO r",
            "ALTER COLLATION c OWNER TO r", "ALTER COLLATION c REFRESH VERSION",
            "ALTER CONVERSION c OWNER TO r", "ALTER STATISTICS s OWNER TO r",
            "ALTER OPERATOR FAMILY f OWNER TO r",
            "ALTER TEXT SEARCH CONFIGURATION c OWNER TO r",
            // member mutations the snapshot cannot represent
            "ALTER INDEX i ATTACH PARTITION pi",
            "ALTER TEXT SEARCH CONFIGURATION c RENAME TO c2",
            "ALTER TEXT SEARCH PARSER p SET SCHEMA s",
            "ALTER TEXT SEARCH TEMPLATE t RENAME TO t2",
            "ALTER TEXT SEARCH DICTIONARY d RENAME TO d2",
            "ALTER OPERATOR FAMILY f ADD OPERATOR 1 < (int,int)",
            "ALTER FUNCTION f() DEPENDS ON EXTENSION e",
            "ALTER MATERIALIZED VIEW mv DEPENDS ON EXTENSION e",
            // view column defaults are not loaded
            "ALTER VIEW v ALTER COLUMN c SET DEFAULT 1",
            "ALTER VIEW v ALTER COLUMN c DROP DEFAULT",
            "DISCARD PLANS", "DISCARD ALL", "PREPARE TRANSACTION 'x'",
            "PREPARE q AS SELECT 1",

            "CREATE VIEW v AS SELECT 1 INTO other.t",
            "SELECT nextval('other.seq')", "SELECT setval('other.seq', 5)",
            "SELECT setval('\"other\".\"seq\"', 5)",
            // statements that evaluate now: ANALYZE really executes the
            // query; CTAS and matview creation populate from theirs
            "EXPLAIN ANALYZE SELECT nextval('other.seq')",
            "EXPLAIN (ANALYZE) SELECT nextval('other.seq')",
            "CREATE TABLE x AS SELECT nextval('other.seq')",
            "CREATE MATERIALIZED VIEW mv AS SELECT nextval('other.seq')",
            // quoting and comment gaps do not evade the sequence-call check
            'SELECT "nextval"(\'other.seq\')',
            'SELECT pg_catalog."nextval"(\'other.seq\')',
            "SELECT nextval /*c*/ ('other.seq')",
            "SELECT setval -- c\n ('other.seq', 5)",
            // the qualified literal rejects inside any arg expression
            "SELECT nextval(CAST('other.seq' AS regclass))",
            "SELECT nextval(to_regclass('other.seq'))",
            "SELECT nextval(E'other.seq')",
            "SELECT nextval($$other.seq$$)",
            "SELECT nextval('other.seq' || '.x')",
            "SELECT nextval(lower('other.seq'))",
            "DO 'BEGIN PERFORM nextval(''other.s''); END'",
            "DO 'BEGIN PERFORM \"setval\" /*c*/ (''other.s'', 1); END'",
            "DO $$ BEGIN EXECUTE 'DROP TABLE other.t'; END $$",
            // PL/pgSQL table-creating SELECT INTO forms (INTO + temp/unlogged/table)
            "DO 'BEGIN SELECT * INTO UNLOGGED TABLE other.t FROM x; END'",
            "DO 'BEGIN SELECT * INTO TEMP TABLE other.t FROM x; END'",
            "DO 'BEGIN SELECT * INTO TABLE other.t FROM x; END'",
            "DO 'BEGIN PERFORM pg_advisory_lock(1); END'",
            // an unterminated inner literal still yields its full body —
            // truncating it would let the tail evade the recursive check
            "DO 'EXECUTE ''COMMIT''",
            "DO 'EXECUTE ''SELECT nextval(''''o.s'''')'''",
            // non-transactional or cross-session side effects survive
            // the scratch rollback or hit other sessions
            "SELECT pg_terminate_backend(1234)",
            "SELECT pg_cancel_backend(1234)",
            "SELECT pg_reload_conf()",
            "SELECT pg_switch_wal()",
            "SELECT pg_backup_start('x', true)",
            "SELECT pg_promote(false, 10)",
            "SELECT pg_file_write('/tmp/x', 'd', false)",
            "SELECT pg_file_unlink('/tmp/x')",
            "SELECT lo_export(12345, '/tmp/x')",
            "SELECT pg_stat_reset()",
            "SELECT pg_stat_reset_single_table_counters(123)",
            "SELECT pg_create_logical_replication_slot('s', 'pgoutput')",
            "SELECT pg_temporary_replication_slot()",
            "SELECT pg_drop_replication_slot('s')",
            "SELECT pg_replication_slot_advance('s', '0/1')",
            "SELECT pg_logical_slot_get_changes('s', NULL, NULL)",
            "SELECT pg_replication_origin_session_setup('o')",
            "SELECT pg_replication_origin_create('o')",
            "SELECT pg_log_emit(false, 'WARNING', 'x')",
            "SELECT pg_logical_emit_message(false, 'p', 'm')",
            'SELECT pg_catalog."pg_terminate_backend"(1234)',
            "SELECT pg_terminate_backend /* a call anyway */ (1234)",
            "SELECT * FROM pg_terminate_backend(1234)",
            "DO 'BEGIN PERFORM pg_terminate_backend(1); END'",
            "DO 'BEGIN PERFORM pg_file_write(''/tmp/x'', ''d'', false); END'",
            'SELECT pg_catalog."set_config"(\'a\', \'1\', false)',
            'SELECT "set_config"(\'a\', \'1\', false)',
            "SELECT set_config /*c*/ ('a', '1', false)",
            "CREATE EXTENSION pgcrypto WITH SCHEMA other",
            // extensions are global objects: only CREATE (declaring
            // presence, SCHEMA operand checked) is representable
            "DROP EXTENSION pgcrypto",
            "DROP EXTENSION IF EXISTS pgcrypto",
            "ALTER EXTENSION pgcrypto UPDATE",
            "ALTER EXTENSION pgcrypto ADD FUNCTION f()",
            // objects anchored to another namespace's type — the
            // qualified operand carries the object outside scratch

            "CREATE CAST (other.t AS int) WITHOUT FUNCTION",
            "DROP CAST (other.t AS int)",
            "CREATE TRANSFORM FOR other.t LANGUAGE plpgsql (FROM SQL WITH FUNCTION f())",
            "DROP TRANSFORM FOR other.t LANGUAGE plpgsql",
            "COMMENT ON CAST (other.t AS int) IS 'x'",
            "COMMENT ON TRANSFORM FOR other.t LANGUAGE plpgsql IS 'x'",
            "SECURITY LABEL FOR p ON TRANSFORM FOR other.t LANGUAGE plpgsql IS 'x'",
            "COMMENT ON EXTENSION pg_trgm IS 'x'",
            "SECURITY LABEL FOR p ON EXTENSION pg_trgm IS 'x'"]) {
        assert.throws(() => PgScriptCheck.check(sql, SCRATCH),
                DesiredStateException, sql);
    }
});

test("aLiteralThatIsTheScratchNameIsRejected", () => {
    // ${namespace} substituted inside a literal produces '"scr"' — the
    // baked name differs on every run and could never converge
    for (const sql of [
            "CREATE TABLE t (c text DEFAULT '\"scr\"')",
            "CREATE TABLE t (c text DEFAULT 'scr')",
            "COMMENT ON TABLE t IS 'scr'",
            "SELECT $tag$\"scr\"$tag$",
            // the escaped token form left in place by $${namespace} —
            // it would bind to the quoted target on apply and drift forever
            "CREATE TABLE t (c text DEFAULT '${namespace}')",
            "CREATE TABLE t (c text DEFAULT 'a${namespace}b')"]) {
        assert.throws(() => PgScriptCheck.check(sql, SCRATCH),
                DesiredStateException, sql);
    }
    // the scratch name as a qualifier, embedded in data, or inside a
    // DO body (transient — it cannot reach a canonical) stays legal
    PgScriptCheck.check("SELECT nextval('scr.seq')", SCRATCH);
    // a token followed by '.' converges: it binds '"target".seq' which
    // normalisation maps back — only the bare-token form is unstable
    PgScriptCheck.check("SELECT '${namespace}.seq'", SCRATCH);
    PgScriptCheck.check("CREATE TABLE t (c text DEFAULT 'a''scr''b')", SCRATCH);
    PgScriptCheck.check("DO 'BEGIN PERFORM ''scr''; END'", SCRATCH);
});

test("everyStatementTheSpecCallsLegalPasses", () => {
    for (const sql of [
            "CREATE FOREIGN TABLE ft (a int) SERVER srv",
            "IMPORT FOREIGN SCHEMA remote FROM SERVER srv INTO scr",
            "SET LOCAL work_mem = '1MB'", "SET TRANSACTION READ ONLY",
            "SET CONSTRAINTS ALL DEFERRED",
            "CREATE TABLE scr.t (a int REFERENCES other.parent (id))",
            "CREATE TABLE t (a int REFERENCES other.parent (id))",
            "CREATE TEMP TABLE pg_temp.x (a int)",
            "COMMENT ON TABLE scr.t IS 'x'",
            'COMMENT ON TABLE "scr".t IS \'x\'',
            'CREATE TABLE "scr".t (a int)',
            "DROP TABLE scr.t1, scr.t2",
            "DROP RULE r ON scr.t",
            "GRANT SELECT ON TABLE scr.a, scr.b TO PUBLIC",
            "GRANT SELECT ON FOREIGN TABLE scr.ft TO PUBLIC",
            // keyword-named columns are legal names, not delimiters —
            // the scratch-qualified target is what matters
            "GRANT SELECT (on) ON TABLE scr.t TO r",
            "GRANT SELECT (a, schema) ON TABLE scr.t TO r",
            "GRANT SELECT (a, schema) ON ALL TABLES IN SCHEMA scr TO r",
            "CREATE TRIGGER g AFTER UPDATE OF a, on ON scr.t EXECUTE FUNCTION f()",
            "IMPORT FOREIGN SCHEMA r LIMIT TO (into) FROM SERVER s INTO scr",
            "GRANT SELECT ON ALL TABLES IN SCHEMA scr TO r1, r2",
            "CREATE TABLE exists (a int)",
            "CREATE TEXT SEARCH CONFIGURATION cfg ()",
            "ALTER TEXT SEARCH CONFIGURATION c ADD MAPPING FOR x WITH d",
            "ALTER TEXT SEARCH CONFIGURATION scr.c ADD MAPPING FOR x WITH scr.d",
            "ALTER TEXT SEARCH CONFIGURATION c ALTER MAPPING FOR x WITH d1, d2",
            "ALTER TEXT SEARCH CONFIGURATION c DROP MAPPING FOR x",
            "ALTER TEXT SEARCH CONFIGURATION c DROP MAPPING IF EXISTS FOR x",
            "ALTER TEXT SEARCH CONFIGURATION c ALTER MAPPING REPLACE d1 WITH d2",
            "ALTER TEXT SEARCH DICTIONARY d (stopwords = 'x, y')",
            "ALTER OPERATOR CLASS c USING btree ADD OPERATOR 1 < (int,int)",
            "ALTER OPERATOR CLASS c USING btree DROP OPERATOR 1",
            "ALTER OPERATOR CLASS scr.c USING hash ADD FUNCTION 1 (int,int) f(int)",
            "CREATE OPERATOR FAMILY f USING btree",
            "CREATE AGGREGATE scr.a1(int) (SFUNC = f, STYPE = int)",
            "DROP AGGREGATE scr.a1(int)",
            "ALTER AGGREGATE scr.a1(int) OWNER TO r",
            "COMMENT ON AGGREGATE scr.a1(int) IS 'x'",
            "GRANT EXECUTE ON FUNCTION scr.a1(int) TO r",
            "REINDEX TABLE scr.i",
            "SELECT * INTO scr.t FROM src",
            "SELECT * INTO t FROM src",
            "TRUNCATE scr.t", "TRUNCATE a, b RESTART IDENTITY CASCADE",
            "LOCK TABLE scr.t IN ACCESS SHARE MODE",
            "ANALYZE scr.t", "ANALYZE VERBOSE t", "ANALYZE (VERBOSE, SKIP_LOCKED) scr.t",
            "CLUSTER scr.t", "CLUSTER t",
            "CREATE TABLE t PARTITION OF other.p",
            "ALTER TABLE t INHERIT other.p",
            "REFRESH MATERIALIZED VIEW mv CONCURRENTLY",
            "REFRESH MATERIALIZED VIEW mv",
            "INSERT INTO t SELECT 1", "UPDATE scr.t SET a = 1",
            "DELETE FROM scr.t WHERE a = 1",
            "MERGE INTO scr.t USING x ON true WHEN MATCHED THEN UPDATE SET a = 1",
            "SELECT 1 FROM t FOR UPDATE OF scr.t",
            "SELECT nextval('seq')", "SELECT nextval('scr.seq')",
            "SELECT nextval('pg_temp.seq')",
            "DO 'BEGIN DELETE FROM scr.t; END'",
            "DO 'BEGIN CALL p(1); END'",
            // in PL/pgSQL, INTO without a temp/table lead-in is variable
            // assignment — a qualified target is record.field, not a table
            "DO 'BEGIN SELECT * INTO rec.field FROM x; END'",
            "DO 'BEGIN SELECT * INTO STRICT rec FROM x; END'",
            "DO 'BEGIN SELECT * INTO UNLOGGED TABLE scr.t FROM x; END'",
            // malformed SQL (unterminated literals) passes the check —
            // it fails at execution; it must only never crash the scan
            "DO 'EXECUTE ''''''", "DO 'x'' EXECUTE '''",
            // deferred code: a function or view body only stores the call
            "CREATE FUNCTION f() RETURNS bigint LANGUAGE sql AS 'SELECT nextval(''o.s'')'",
            "CREATE VIEW v AS SELECT nextval('o.s')",
            "EXPLAIN SELECT nextval('o.s')",
            "REINDEX INDEX scr.i", "IMPORT FOREIGN SCHEMA r FROM SERVER s INTO scr",
            "SELECT set_config('app.x', '1', true)",
            'SELECT pg_catalog."set_config"(\'a\', \'1\', true)',
            // the word alone is not a call — column names, literals and
            // comments mentioning the functions stay legal
            "CREATE TABLE t (set_config text)",
            "SELECT 'set_config'",
            "SELECT pg_terminate_backend FROM t",
            "SELECT set_config FROM t",
            "SELECT pg_advisory_xact_lock(1)",
            "SELECT pg_advisory_unlock_all()",
            "SELECT pg_notify('c', 'm')",
            // transactional, so confined to the scratch connection:
            // NOTIFY only delivers at commit and a rolled-back
            // LISTEN/UNLISTEN leaves the channel set unchanged
            "NOTIFY c", "LISTEN c", "UNLISTEN c",
            "CREATE RULE r AS ON UPDATE TO t DO ALSO NOTIFY x",
            // transactional effects are confined to the scratch
            // connection (3.2 rule 4): foreign-table writes, locks,
            // reindexing, ANALYZE/CLUSTER/REFRESH/TRUNCATE and CALLs all
            // roll back; only non-transactional or session effects and
            // object names qualified outside scratch reject
            "TRUNCATE other.t", "TRUNCATE a, other.b",
            "LOCK TABLE other.t", "LOCK a, other.b IN ACCESS EXCLUSIVE MODE",
            "REFRESH MATERIALIZED VIEW other.mv",
            "ANALYZE", "ANALYZE VERBOSE", "ANALYZE other.t",
            "ANALYZE (VERBOSE)", "ANALYZE (VERBOSE) other.t",
            "CLUSTER (VERBOSE) other.t",
            "REPACK t", "REPACK (VERBOSE) other.t", "REPACK (ANALYZE) t (c1)",
            "INSERT INTO other.t SELECT 1", "UPDATE other.t SET a = 1",
            "DELETE FROM other.t", "DELETE FROM ONLY other.t",
            "MERGE INTO other.t USING t ON true WHEN MATCHED THEN UPDATE SET a = 1",
            "WITH x AS (DELETE FROM other.t RETURNING *) SELECT 1",
            "SELECT 1 FROM t FOR UPDATE OF other.t",
            "CALL other.p(1)",
            "DO 'BEGIN DELETE FROM other.t; END'",
            "DO 'BEGIN CALL other.p(1); END'",
            "DO 'BEGIN EXECUTE ''INSERT INTO other.t SELECT 1''; END'",
            "REINDEX TABLE exists.i", "REINDEX TABLE other.t",
            "CREATE RULE r AS ON INSERT TO t DO INSTEAD INSERT INTO other.t2",
            "CREATE RULE r AS ON INSERT TO t DO INSTEAD (SELECT * INTO other.t2)",
            "CREATE RULE r AS ON UPDATE TO t DO INSTEAD SELECT nextval('other.s')",
            "SELECT pg_logical_slot_peek_changes('s', NULL, NULL)",
            "SELECT pg_sleep(0)",
            "CALL p(1)", "CALL scr.p(1)",
            "CREATE RULE r AS ON INSERT TO t DO INSTEAD INSERT INTO t2",
            "CREATE RULE r AS ON INSERT TO t DO INSTEAD NOTHING",
            "CREATE RULE r AS ON UPDATE TO scr.t DO ALSO UPDATE t2 SET a = 1",
            // publication bindings are namespace-anchored — the
            // publication name is a cluster reference (4.1), the bound
            // table/schema/sequence must live in scratch
            "ALTER PUBLICATION p ADD TABLE t",
            "ALTER PUBLICATION p ADD TABLE scr.t",
            "ALTER PUBLICATION p ADD TABLE ONLY t WHERE (a > 0)",
            "ALTER PUBLICATION p ADD TABLE t (a, b)",
            "ALTER PUBLICATION p SET TABLE t, scr.u",
            "ALTER PUBLICATION p DROP TABLE t",
            "ALTER PUBLICATION p ADD TABLES IN SCHEMA scr",
            "ALTER PUBLICATION p SET TABLES IN SCHEMA scr, scr",
            "ALTER PUBLICATION p DROP TABLES IN SCHEMA scr",
            "ALTER PUBLICATION p ADD SEQUENCE s",
            "ALTER PUBLICATION p DROP SEQUENCE scr.s",
            "ALTER PUBLICATION p ADD SEQUENCES IN SCHEMA scr",
            "ALTER DEFAULT PRIVILEGES IN SCHEMA scr GRANT SELECT ON TABLES TO r",
            "ALTER DEFAULT PRIVILEGES FOR ROLE r IN SCHEMA scr GRANT EXECUTE ON FUNCTIONS TO r",
            "ALTER DEFAULT PRIVILEGES FOR USER r IN SCHEMA scr GRANT USAGE ON SEQUENCES TO r",
            "ALTER DEFAULT PRIVILEGES IN SCHEMA scr GRANT USAGE ON TYPES TO r",
            "ALTER DEFAULT PRIVILEGES IN SCHEMA scr REVOKE SELECT ON TABLES FROM r",
            "ALTER DEFAULT PRIVILEGES FOR ROLE r IN SCHEMA scr REVOKE GRANT OPTION FOR SELECT ON TABLES FROM r",
            "CREATE PROCEDURE p() LANGUAGE plpgsql AS $$ BEGIN NULL; END $$",
            "CREATE PROCEDURE p() LANGUAGE plpgsql AS $$ BEGIN -- the commit record\n PERFORM 1; END $$",
            // routine bodies are opaque to the check (spec §8 rule 5) —
            // a callable COMMIT procedure is caught by the savepoint
            // backstop if the script invokes it; BEGIN ATOMIC keeps its
            // ';'s inside the statement either way
            "CREATE PROCEDURE p() LANGUAGE SQL BEGIN ATOMIC INSERT INTO scr.t VALUES (1); END",
            "CREATE FUNCTION f() RETURNS int LANGUAGE SQL BEGIN ATOMIC RETURN 1; END",
            "CREATE PROCEDURE p() BEGIN ATOMIC FOR r IN SELECT 1 LOOP INSERT INTO scr.t VALUES (1); END LOOP; END",
            "CREATE PROCEDURE p(x text DEFAULT 'commit;') LANGUAGE sql AS $$ SELECT 1 $$",
            "CREATE PROCEDURE p() AS $$ BEGIN EXECUTE 'INSERT INTO scr.t VALUES (1)'; END $$",
            "CREATE PROCEDURE p() LANGUAGE plpgsql AS $$ BEGIN COMMIT; END $$",
            "CREATE PROCEDURE p() LANGUAGE plpgsql AS $$ BEGIN EXECUTE 'ROLLBACK AND CHAIN'; END $$",
            "CREATE PROCEDURE p() LANGUAGE plpgsql AS $$ BEGIN EXECUTE 'INSERT INTO other.t'; END $$",
            "CREATE FUNCTION f() RETURNS int LANGUAGE plpgsql AS $$ BEGIN EXECUTE 'INSERT INTO other.t'; END $$",
            "CREATE PROCEDURE p() BEGIN ATOMIC COMMIT; END",
            "CREATE PROCEDURE p() BEGIN ATOMIC EXECUTE 'INSERT INTO other.t'; END",
            "CREATE CAST (scr.t AS int) WITHOUT FUNCTION",
            "CREATE CAST (int AS text) WITHOUT FUNCTION",
            "CREATE TRANSFORM FOR scr.t LANGUAGE plpgsql (FROM SQL WITH FUNCTION f())",
            "COMMENT ON CAST (scr.t AS int) IS 'x'",
            "COMMENT ON TRANSFORM FOR scr.t LANGUAGE plpgsql IS 'x'"]) {
        PgScriptCheck.check(sql, SCRATCH);
    }
});
