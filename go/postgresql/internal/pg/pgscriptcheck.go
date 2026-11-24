package pg

import (
	"regexp"
	"strings"

	core "github.com/EpicQuest-Studios/schema-reconciler/go/core"
)

// Enforces the script constraints of spec 3.2 rules 1–3 for PostgreSQL:
// no transaction control, no session-level locks, no namespace DDL, no
// statements that cannot run inside a transaction block, no session-level
// SET/RESET, no DDL for database-wide objects — servers, roles, event
// triggers and the like have no namespace anchor, so they can never
// appear in a snapshot and a script creating one would go undetected
// (5.4) — and no object names qualified by a namespace other than the
// scratch namespace itself: a qualified `other_schema.name` in
// object-name position would create (or alter, or grant on) an object
// that escapes the scratch namespace. References to other namespaces
// elsewhere in a statement are allowed: a cross-schema REFERENCES clause
// is a legitimate external dependency (5.8.3), not an escaping creation.
// The scan runs on the substituted script — after ${namespace} has been
// replaced by the scratch name — so a legitimate
// `CREATE SCHEMA ${namespace}`-as-text is still rejected: the core owns
// namespace creation.
//
// SCOPE FROZEN: this is a best-effort lexical guard, not a security
// sandbox (SPEC §8 rule 5, docs/adr/0001-pg-script-check.md). Its keyword
// and form coverage is fixed — do not add new lexer edge cases here;
// untrusted scripts must use a scratch DB with a least-privilege role
// instead of relying on this scan.

var transactionControl = set(
	"BEGIN", "START", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE",
	"END", "ABORT")

var nonTransactional = set("VACUUM", "CHECKPOINT")

// Session-level advisory lock functions (spec 3.2 rule 2). The
// transaction-scoped pg_advisory_xact_* variants are allowed: they are
// released by the scratch rollback. Unlock functions acquire nothing,
// so they are not banned either.
var sessionLocks = set(
	"PG_ADVISORY_LOCK", "PG_ADVISORY_LOCK_SHARED",
	"PG_TRY_ADVISORY_LOCK", "PG_TRY_ADVISORY_LOCK_SHARED")

// System functions whose effect is not transactional — it survives the
// scratch rollback (replication slots and origins, statistics resets,
// WAL and backup control, server file writes, log emission) or hits
// other sessions outright (terminating or cancelling backends, config
// reload). Transactional variants — pg_logical_slot_peek_*,
// lo_import, pg_notify — stay legal.
var nonTxnFunctions = set(
	"PG_TERMINATE_BACKEND", "PG_CANCEL_BACKEND", "PG_RELOAD_CONF",
	"PG_ROTATE_LOGFILE", "PG_LOG_BACKEND_MEMORY_CONTEXTS",
	"PG_LOG_STANDBY_SNAPSHOT", "PG_LOG_EMIT", "PG_LOGICAL_EMIT_MESSAGE",
	"PG_SWITCH_WAL", "PG_SWITCH_XLOG", "PG_CREATE_RESTORE_POINT",
	"PG_BACKUP_START", "PG_BACKUP_STOP", "PG_START_BACKUP", "PG_STOP_BACKUP",
	"PG_PROMOTE", "PG_CHECKPOINT",
	"PG_WAL_REPLAY_PAUSE", "PG_WAL_REPLAY_RESUME",
	"PG_SYNC_REPLICATION_SLOTS",
	"PG_FILE_WRITE", "PG_FILE_RENAME", "PG_FILE_UNLINK",
	"PG_FILE_MKDIR", "PG_FILE_SYNC", "LO_EXPORT", "BE_LO_EXPORT",
	"PG_STAT_RESET", "PG_STAT_RESET_SHARED", "PG_STAT_RESET_SLRU",
	"PG_STAT_RESET_SINGLE_TABLE_COUNTERS",
	"PG_STAT_RESET_SINGLE_FUNCTION_COUNTERS",
	"PG_STAT_RESET_REPLICATION_SLOT", "PG_STAT_RESET_SUBSCRIPTION_STATS",
	"PG_STAT_FORCE_NEXT_FLUSH",
	"PG_CREATE_LOGICAL_REPLICATION_SLOT",
	"PG_CREATE_PHYSICAL_REPLICATION_SLOT",
	"PG_TEMPORARY_REPLICATION_SLOT", "PG_DROP_REPLICATION_SLOT",
	"PG_REPLICATION_SLOT_ADVANCE", "PG_LOGICAL_SLOT_GET_CHANGES",
	"PG_LOGICAL_SLOT_GET_BINARY_CHANGES",
	"PG_COPY_LOGICAL_REPLICATION_SLOT",
	"PG_COPY_PHYSICAL_REPLICATION_SLOT",
	"PG_REPLICATION_ORIGIN_CREATE", "PG_REPLICATION_ORIGIN_DROP",
	"PG_REPLICATION_ORIGIN_ADVANCE", "PG_REPLICATION_ORIGIN_SESSION_SETUP",
	"PG_REPLICATION_ORIGIN_SESSION_RESET",
	"PG_REPLICATION_ORIGIN_XACT_SETUP", "PG_REPLICATION_ORIGIN_XACT_RESET")

var setConfigCall = regexp.MustCompile(`\bSET_CONFIG\b`)

// Sequence-valued functions whose effect is NOT transactional: nextval
// and setval survive the scratch rollback, so a namespace-qualified
// literal argument is an escape. (currval/lastval only read session
// state.) The name match stops at the word: callArguments handles the
// quoting and comment gap before the '(', so `"nextval" ('o.s')` cannot
// evade the check.
var sequenceCall = regexp.MustCompile(`\b(NEXTVAL|SETVAL)\b`)

// Statements operating on session state with no schema effect.
// PREPARE covers both forms — prepared statements and two-phase
// PREPARE TRANSACTION; DISCARD is the same for all variants.
var sessionState = set(
	"DECLARE", "EXECUTE", "DEALLOCATE", "COPY", "FETCH", "MOVE", "CLOSE",
	"PREPARE", "DISCARD")

// CREATE/DROP/ALTER head modifiers skipped before the object-kind word.
var headModifiers = set(
	"UNIQUE", "OR", "REPLACE", "IF", "NOT", "EXISTS", "TEMP",
	"TEMPORARY", "UNLOGGED", "GLOBAL", "LOCAL", "MATERIALIZED",
	"RECURSIVE", "FOREIGN", "TRUSTED", "UNTRUSTED", "PROCEDURAL",
	"CONSTRAINT")

// Tokens between the object-kind word and the object name.
var nameModifiers = set(
	"IF", "NOT", "EXISTS", "ONLY", "CONCURRENTLY",
	// multi-word kinds: TEXT SEARCH ..., OPERATOR FAMILY/CLASS
	"SEARCH", "CONFIGURATION", "DICTIONARY", "PARSER", "TEMPLATE",
	"FAMILY", "CLASS")

// Objects whose name anchors a second object after ON. A RULE's
// anchor follows TO on CREATE and ON on DROP/ALTER;
// checkOnTarget handles both words.
var onAnchored = set("INDEX", "TRIGGER", "POLICY", "RULE")

// GRANT/REVOKE object kinds that take a namespace-scoped name.
var grantKinds = set(
	"TABLE", "SEQUENCE", "FUNCTION", "PROCEDURE", "ROUTINE",
	"DOMAIN", "TYPE", "STATISTICS")

// GRANT/REVOKE targets with no namespace anchor.
var grantDatabaseWide = set(
	"DATABASE", "TABLESPACE", "ROLE", "USER", "GROUP", "LARGE",
	"FOREIGN", "LANGUAGE", "PARAMETER", "SERVER")

// Tokens between `SELECT ... INTO` and the created table's name.
var selectIntoModifiers = set("TEMP", "TEMPORARY", "UNLOGGED", "TABLE")

// COMMENT ON / SECURITY LABEL ON kinds whose target carries its argument
// types in parentheses — `FUNCTION f(t)`, `OPERATOR op(t,t)`.
// Those are type references, like a cross-schema REFERENCES: a
// qualified `other.t` inside the parentheses is a legitimate
// foreign type, so only the name before the opening parenthesis anchors
// the target. A CAST's operands stay checked: the parenthesized type
// pair is the cast's entire name and anchors it.
var signatureTargetKinds = set(
	"FUNCTION", "PROCEDURE", "ROUTINE", "AGGREGATE", "OPERATOR")

// The ${namespace} placeholder text of spec 3.2.
const token = "${namespace}"

// CREATE/DROP/ALTER objects with no namespace anchor (spec 5.4).
var databaseWideObjects = set(
	"SERVER", "PUBLICATION", "ROLE", "USER", "GROUP", "EVENT",
	"ACCESS", "DEFAULT", "OWNED", "LANGUAGE", "LARGE")

// ALTER ... OWNER TO targets whose owner column feeds the
// snapshot — the pg_class/pg_proc/pg_type ownership legs (A.5
// OWNERSHIP). An index's owner follows its table silently, and
// conversion, collation, text-search, operator, statistics and
// extension owners are never loaded — either way an unlisted target
// would apply without a trace.
var ownerModeled = set(
	"TABLE", "VIEW", "MATERIALIZED", "SEQUENCE", "FOREIGN",
	"FUNCTION", "PROCEDURE", "ROUTINE", "AGGREGATE", "TYPE",
	"DOMAIN")

// COMMENT ON / SECURITY LABEL ON kinds whose target can never appear in a
// namespace snapshot. FOREIGN is handled separately:
// FOREIGN SERVER/FOREIGN DATA WRAPPER are database-wide, but a
// FOREIGN TABLE is namespace-scoped and takes the qualifier check.
var commentDatabaseWide = set(
	"DATABASE", "TABLESPACE", "ROLE", "USER", "GROUP", "SERVER",
	"PUBLICATION", "SUBSCRIPTION", "EVENT", "LANGUAGE", "ACCESS",
	"LARGE", "PROCEDURAL", "EXTENSION")

// Check scans script for spec-3.2 violations. scratchNamespace is the
// substituted scratch name; pass "" to run only the namespace-agnostic
// checks (scratchNamespace === undefined in the TypeScript original).
func Check(script, scratchNamespace string) error {
	for _, statement := range Statements(script) {
		if err := checkStatement(statement, scratchNamespace); err != nil {
			return err
		}
	}
	return nil
}

func checkStatement(statement StatementTokens, scratchNamespace string) error {
	t := statement.Tokens
	first := t[0]
	scratch := scratchNamespace != ""
	if transactionControl[first] {
		return rejected(statement, "transaction control is not allowed in the desired script")
	}
	if nonTransactional[first] {
		return rejected(statement,
			first+" cannot run inside a transaction block")
	}
	if first == "CLUSTER" && clusterNamesNoTable(t) {
		return rejected(statement,
			"CLUSTER without a table cannot run inside a transaction block")
	}
	if first == "CLUSTER" && contains(t, "USING") {
		return rejected(statement,
			"CLUSTER ... USING records the clustering index; that"+
				" catalog state is outside the snapshot")
	}
	if first == "CREATE" || first == "DROP" || first == "ALTER" {
		if err := checkDdl(statement, first, scratchNamespace); err != nil {
			return err
		}
	}
	if first == "REASSIGN" {
		return rejected(statement,
			"REASSIGN OWNED changes ownership database-wide and cannot be represented in a snapshot")
	}
	if first == "GRANT" || first == "REVOKE" {
		if !contains(t, "ON") {
			return rejected(statement,
				"role membership grants are database-wide and cannot be represented in a snapshot")
		}
		if scratch {
			if err := checkGrantTarget(statement, scratchNamespace); err != nil {
				return err
			}
		}
	}
	if first == "COMMENT" || first == "SECURITY" {
		if err := checkCommentTarget(statement, scratchNamespace); err != nil {
			return err
		}
	}
	if first == "REINDEX" {
		if err := checkReindex(statement); err != nil {
			return err
		}
	}
	if scratch {
		// SELECT INTO / CREATE TABLE AS-style INTO at top level —
		// also reachable through CREATE VIEW v AS SELECT ... INTO;
		// INSERT INTO / MERGE INTO name a write target, which is a
		// confined data effect (3.2 rule 4), not an object creation
		into := indexAtDepthZero(t, "INTO", 0)
		if into >= 0 && !(into > 0 &&
			(t[into-1] == "INSERT" || t[into-1] == "MERGE")) {
			if err := checkNameAt(statement,
				pastModifiers(t, into+1, selectIntoModifiers),
				scratchNamespace); err != nil {
				return err
			}
		}
		if evaluatesNow(t, first) {
			if err := checkSequenceCalls(statement, collapseEscapes(statement.Text),
				scratchNamespace); err != nil {
				return err
			}
		}
		if err := checkScratchLiteral(statement, scratchNamespace); err != nil {
			return err
		}
	}
	if sessionState[first] {
		return rejected(statement,
			first+" is session state and must not appear in the script")
	}
	if first == "IMPORT" && scratch {
		if into := indexAtDepthZero(t, "INTO", 0); into >= 0 {
			if err := checkSchemaName(statement, into+1, scratchNamespace); err != nil {
				return err
			}
		}
	}
	if first == "SET" && len(t) < 2 {
		return rejected(statement, "SET names no setting")
	}
	if first == "SET" &&
		!(t[1] == "LOCAL" || t[1] == "TRANSACTION" || t[1] == "CONSTRAINTS") {
		return rejected(statement,
			"session-level SET leaks onto the connection; use SET LOCAL")
	}
	if first == "RESET" || first == "LOAD" {
		return rejected(statement,
			first+" changes session state and must not appear in the script")
	}
	for _, x := range t {
		if unquote(x) == "SET_CONFIG" {
			if err := checkSetConfig(statement, collapseEscapes(statement.Text)); err != nil {
				return err
			}
			break
		}
	}
	if first == "DO" {
		if err := checkDoBody(statement, scratchNamespace); err != nil {
			return err
		}
	}
	if err := checkBannedCalls(statement); err != nil {
		return err
	}
	if first == "REPACK" &&
		(contains(t, "CONCURRENTLY") || repackNamesNoTable(t)) {
		return rejected(statement,
			"REPACK without a table, or with CONCURRENTLY,"+
				" cannot run inside a transaction block")
	}
	if first == "WAIT" {
		return rejected(statement,
			"WAIT FOR LSN is a session-level replication operation and must not appear in the script")
	}
	return nil
}

// checkDoBody — a DO block executes during the replay, so its body gets
// the non-transactional scans top-level statements get: set_config
// and sequence calls matched on the whole body text (with ”
// escapes collapsed — spec §8 rule 5 scans literals deliberately, they
// are what an EXECUTE hands to the executor), banned calls on
// the tokenized inner statements, and the object-creation check for
// table-creating INTO forms. `EXECUTE '...'` literals
// inside the body recurse through the full statement check — dynamic
// SQL can carry anything. Transactional effects inside (foreign-table
// writes, CALLs) are confined per 3.2 rule 4 and stay legal.
func checkDoBody(statement StatementTokens, scratchNamespace string) error {
	text := collapseEscapes(statement.Text)
	if err := checkSetConfig(statement, text); err != nil {
		return err
	}
	if scratchNamespace == "" {
		return nil
	}
	if err := checkSequenceCalls(statement, text, scratchNamespace); err != nil {
		return err
	}
	for _, body := range LiteralBodies(statement.Text) {
		for _, inner := range Statements(collapseEscapes(body)) {
			innerTokens := inner.Tokens
			// in PL/pgSQL an INTO not led by TEMP/TEMPORARY/UNLOGGED/TABLE is
			// variable assignment; the modifier-led forms run a table-creating
			// SELECT INTO whose target is checked
			for i := 0; i+1 < len(innerTokens); i++ {
				if innerTokens[i] == "INTO" &&
					selectIntoModifiers[innerTokens[i+1]] {
					if err := checkNameAt(inner, pastModifiers(innerTokens, i+1,
						selectIntoModifiers), scratchNamespace); err != nil {
						return err
					}
				}
			}
			if err := checkBannedCalls(inner); err != nil {
				return err
			}
			if err := checkExecuteLiterals(inner, scratchNamespace); err != nil {
				return err
			}
		}
	}
	return nil
}

// checkBannedCalls — session-leaking and non-transactional calls: a call
// always has '(' after the name, so a bare identifier (a column or object
// reference, a name inside a literal or comment) is not an invocation
// and must not reject. Token-based, so comments and quoted literals are
// already gone; inside DO bodies the same check runs on the
// body's inner statements.
func checkBannedCalls(statement StatementTokens) error {
	t := statement.Tokens
	for i := 0; i+1 < len(t); i++ {
		if t[i+1] != "(" {
			continue
		}
		fn := unquote(t[i])
		if sessionLocks[fn] {
			return rejected(statement,
				"session-level advisory locks leak onto the pooled connection and are not allowed")
		}
		if nonTxnFunctions[fn] {
			return rejected(statement,
				"its effect is not transactional or hits other sessions; it must not"+
					" appear in the script (3.2 rule 1)")
		}
	}
	return nil
}

// checkDdl — the CREATE/DROP/ALTER rules: object-kind resolution, the
// always-rejected kinds (namespace DDL, database-wide and
// non-transactional objects, CONCURRENTLY forms), then the
// qualified-name checks for the head object, ON/TO anchors,
// comma-separated object lists, and ALTER sub-command operands.
func checkDdl(statement StatementTokens, first string,
	scratchNamespace string) error {
	t := statement.Tokens
	if kindIndex(t) >= len(t) {
		return rejected(statement, first+" names no object kind")
	}
	object := objectWord(t)
	if object == "SCHEMA" {
		if first == "ALTER" {
			// ALTER SCHEMA <scratch> OWNER TO <role> mutates nspowner —
			// the one ALTER SCHEMA form the snapshot models (the
			// namespace's OWNERSHIP object, A.5)
			return checkSchemaAlter(statement, scratchNamespace)
		}
		return rejected(statement,
			first+" SCHEMA is not allowed; the core owns namespace creation")
	}
	// CREATE/DROP DATABASE/TABLESPACE and ALTER SYSTEM cannot run inside a
	// transaction block at all; the remaining forms of those objects are
	// transaction-legal but are database-wide objects below
	if object == "SYSTEM" ||
		((object == "DATABASE" || object == "TABLESPACE") && first != "ALTER") {
		return rejected(statement,
			first+" "+object+" cannot run inside a transaction block")
	}
	if object == "DATABASE" || object == "SUBSCRIPTION" ||
		object == "TABLESPACE" {
		return rejected(statement,
			first+" "+object+" is a database-wide object"+
				" that cannot be represented in a namespace snapshot")
	}
	if object == "DEFAULT" && first == "ALTER" {
		// ALTER DEFAULT PRIVILEGES — only an IN SCHEMA-scoped form
		// mutates a namespace-visible row (pg_default_acl); a global
		// default ACL belongs to no namespace
		return checkDefaultPrivileges(statement, scratchNamespace)
	}
	if object == "PUBLICATION" && first == "ALTER" {
		// a binding mutation (ADD|SET|DROP TABLE/TABLES IN SCHEMA/SEQUENCE
		// — the only PUBLICATION form that touches a namespace) is
		// namespace-anchored through its bound object; publication
		// properties, ownership and renames stay database-wide
		return checkPublicationBinding(statement, scratchNamespace)
	}
	if databaseWideObjects[object] ||
		(object == "DATA" && contains(t, "WRAPPER")) {
		return rejected(statement,
			first+" "+object+
				" creates a database-wide object that cannot be represented in a namespace snapshot")
	}
	if contains(t, "CONCURRENTLY") {
		// CREATE/DROP INDEX CONCURRENTLY and ALTER ... DETACH PARTITION
		// CONCURRENTLY — the only CONCURRENTLY forms of these statements
		return rejected(statement,
			first+" "+object+" CONCURRENTLY cannot run inside a transaction block")
	}
	if first == "ALTER" && adjacent(t, "SET", "SCHEMA") {
		return rejected(statement,
			"ALTER ... SET SCHEMA is not allowed; the core owns namespace creation")
	}
	if first == "ALTER" && object == "INDEX" && contains(t, "ALL") {
		return rejected(statement,
			"ALTER INDEX ALL IN TABLESPACE is database-wide"+
				" and cannot be represented in a snapshot")
	}
	// Statements whose catalog effect is outside the snapshot model —
	// they would apply in the scratch replay yet leave no trace, so a
	// plan could claim success while the target never changed (6a).
	if adjacent(t, "SET", "STATISTICS") {
		return rejected(statement,
			"SET STATISTICS tunes the planner and stays outside the snapshot")
	}
	if first == "ALTER" && object == "COLLATION" &&
		adjacent(t, "REFRESH", "VERSION") {
		return rejected(statement,
			"REFRESH VERSION reloads environment metadata, not schema state")
	}
	if first == "ALTER" {
		d := 0
		for i := 1; i+2 < len(t); i++ {
			w := t[i]
			if w == "(" {
				d++
			} else if w == ")" {
				d--
			} else if d == 0 && w == "DEPENDS" && t[i+1] == "ON" &&
				t[i+2] == "EXTENSION" {
				return rejected(statement,
					"DEPENDS ON EXTENSION mutates extension membership,"+
						" which the model excludes")
			}
		}
	}
	if first == "ALTER" && object == "INDEX" && contains(t, "ATTACH") {
		return rejected(statement,
			"ALTER INDEX ... ATTACH PARTITION mutates an auto-managed"+
				" partition index binding")
	}
	if first == "ALTER" && (adjacent(t, "CLUSTER", "ON") ||
		adjacent(t, "WITHOUT", "CLUSTER")) {
		return rejected(statement,
			"CLUSTER membership is catalog state the snapshot does not model")
	}
	if first == "ALTER" && object == "TEXT" {
		if err := checkFtsAlter(statement, scratchNamespace); err != nil {
			return err
		}
	}
	if first == "ALTER" && object == "OPERATOR" {
		if err := checkOperatorAlter(statement); err != nil {
			return err
		}
	}
	if first == "ALTER" && adjacent(t, "OWNER", "TO") &&
		!ownerModeled[object] {
		return rejected(statement,
			"ownership of "+object+" objects is not modeled")
	}
	if first == "ALTER" && object == "VIEW" &&
		(adjacent(t, "SET", "DEFAULT") || adjacent(t, "DROP", "DEFAULT")) {
		return rejected(statement,
			"view column defaults are not modeled")
	}
	depth := 0
	for i := 1; i < len(t); i++ {
		w := t[i]
		if w == "(" {
			depth++
			continue
		}
		if w == ")" {
			depth--
			continue
		}
		if depth > 0 {
			continue
		}
		// a top-level RESTART is sequence/identity runtime state, a
		// top-level TABLESPACE the storage clause — an unquoted object
		// named after either keyword rejects too, but the quoted form
		// stays legal (fail closed)
		if w == "RESTART" && first == "ALTER" {
			return rejected(statement,
				"RESTART changes runtime sequence state, not schema state")
		}
		if w == "TABLESPACE" {
			return rejected(statement,
				"TABLESPACE is a cluster-level object that cannot be"+
					" represented in a snapshot")
		}
	}
	if object == "TABLE" {
		// OF at top level is the typed-table clause — PARTITION OF is the
		// one legal OF form; trailing NOT OF detaches the type
		of := indexAtDepthZero(t, "OF", 0)
		if of > 0 && t[of-1] != "PARTITION" &&
			(of+1 < len(t) || t[of-1] == "NOT") {
			return rejected(statement, "typed tables (OF) are not modeled")
		}
		if first == "CREATE" && indexAtDepthZero(t, "USING", 0) >= 0 {
			return rejected(statement,
				"CREATE TABLE ... USING names an access method that"+
					" is not modeled")
		}
	}
	if scratchNamespace == "" {
		return nil
	}
	name := nameIndex(t)
	if err := checkNameAt(statement, name, scratchNamespace); err != nil {
		return err
	}
	if onAnchored[object] {
		// the anchor ON follows the object name — an object named like
		// the keyword or a modifier (… on ON t, … exists ON t), or an
		// UPDATE OF column, is itself an ON token or shifts the name
		// index, so every depth-0 ON is examined
		d := 0
		for i := 1; i < len(t); i++ {
			w := t[i]
			if w == "(" {
				d++
			} else if w == ")" {
				d--
			} else if d == 0 && w == "ON" {
				if err := checkOnTarget(statement, i, scratchNamespace); err != nil {
					return err
				}
			}
		}
	}
	if first == "CREATE" && object == "RULE" {
		// the target TO follows the rule name — a rule named after the
		// keyword (… AS ON INSERT TO t) must not shift the anchor
		if err := checkOnTarget(statement, indexAtDepthZero(t, "TO", name+1), scratchNamespace); err != nil {
			return err
		}
	}
	if object == "CAST" {
		// (source AS target) is the cast's name — a qualified type
		// anchors the cast to that type's namespace
		if open := indexOf(t, "("); open >= 0 {
			close_ := pastParen(t, open)
			for i := open + 1; i+1 < close_-1; i++ {
				if t[i+1] == "." {
					if err := checkQualifierAt(statement, i, scratchNamespace); err != nil {
						return err
					}
				}
			}
		}
	}
	if object == "TRANSFORM" {
		// the type after FOR anchors the transform's namespace
		if typ := indexOf(t, "FOR") + 1; typ > 0 {
			if err := checkQualifierAt(statement, typ, scratchNamespace); err != nil {
				return err
			}
		}
	}
	if first == "DROP" {
		if err := checkNameList(statement, name, scratchNamespace); err != nil {
			return err
		}
	}
	if first == "ALTER" && (object == "TABLE" || object == "INDEX") {
		// ALTER sub-commands whose operand is an object that gets mutated —
		// a partition attaches into (or detaches from) the named table.
		// INHERIT/PARTITION OF name references instead: a foreign parent
		// is a legitimate external dependency (5.8.3); a bare OF (typed
		// tables) is rejected above as unmodeled.
		if err := checkQualifiedAfter(statement, t, "ATTACH", "PARTITION", scratchNamespace); err != nil {
			return err
		}
		if err := checkQualifiedAfter(statement, t, "DETACH", "PARTITION", scratchNamespace); err != nil {
			return err
		}
	}
	if object == "EXTENSION" {
		if first != "CREATE" {
			return rejected(statement, first+" EXTENSION mutates a database-wide"+
				" object that cannot be represented in a namespace snapshot")
		}
		if schema := indexAtDepthZero(t, "SCHEMA", name+1); schema >= 0 {
			if err := checkSchemaName(statement, schema+1, scratchNamespace); err != nil {
				return err
			}
		}
	}
	return nil
}

// checkPublicationBinding — the ALTER PUBLICATION binding forms:
// `ADD|SET|DROP TABLE`, `... TABLES IN SCHEMA` and `... SEQUENCE`
// mutate the namespace-visible binding (A.5 PUBLICATION); anything else —
// `SET (…)`, `OWNER TO`, `RENAME`, `SET SCHEMA` —
// mutates the cluster-level publication and is rejected. Bound
// table/sequence names take the qualifier check; schema names must be
// the scratch namespace. Returns silently when scratchNamespace
// is "": the form check alone decides admissibility.
func checkPublicationBinding(statement StatementTokens,
	scratchNamespace string) error {
	t := statement.Tokens
	name := nameIndex(t)
	i := name + 1
	if i >= len(t) || (t[i] != "ADD" && t[i] != "SET" && t[i] != "DROP") {
		return rejected(statement, "ALTER PUBLICATION changes a database-wide"+
			" object; only its binding forms can appear in a snapshot")
	}
	i++
	if i >= len(t) ||
		(t[i] != "TABLE" && t[i] != "TABLES" && t[i] != "SEQUENCE" && t[i] != "SEQUENCES") {
		return rejected(statement, "ALTER PUBLICATION changes a database-wide"+
			" object; only its binding forms can appear in a snapshot")
	}
	schema := t[i] == "TABLES" || t[i] == "SEQUENCES"
	if scratchNamespace == "" {
		return nil
	}
	if schema {
		s := indexAtDepthZero(t, "SCHEMA", 0)
		return checkSchemaList(statement, s+1, scratchNamespace)
	}
	// table/sequence name list — the first name follows the verb word
	// (with an optional ONLY), the rest follow commas
	first := i + 1
	if first < len(t) && t[first] == "ONLY" {
		first++
	}
	if err := checkQualifierAt(statement, first, scratchNamespace); err != nil {
		return err
	}
	return checkNameList(statement, i, scratchNamespace)
}

// checkDefaultPrivileges — `ALTER DEFAULT PRIVILEGES`: only the
// `IN SCHEMA`-scoped GRANT/REVOKE form is namespace state (A.5
// DEFAULT_PRIVILEGES). The FOR ROLE clause is a cluster reference like
// a grantee; a global default ACL (no `IN SCHEMA`) is database-wide
// and rejected. Schema names must be the scratch namespace. Returns
// silently when scratchNamespace is "": the form check alone
// decides admissibility.
func checkDefaultPrivileges(statement StatementTokens,
	scratchNamespace string) error {
	t := statement.Tokens
	if len(t) < 3 || t[2] != "PRIVILEGES" {
		return rejected(statement, "ALTER DEFAULT changes a database-wide"+
			" object that cannot be represented in a snapshot")
	}
	into := indexAtDepthZero(t, "IN", 0)
	if into < 0 || into+1 >= len(t) || t[into+1] != "SCHEMA" {
		return rejected(statement, "ALTER DEFAULT PRIVILEGES without IN SCHEMA"+
			" is database-wide; only the IN SCHEMA form can appear"+
			" in a snapshot")
	}
	if !contains(t, "GRANT") && !contains(t, "REVOKE") {
		return rejected(statement, "ALTER DEFAULT PRIVILEGES needs a"+
			" GRANT or REVOKE clause")
	}
	if scratchNamespace == "" {
		return nil
	}
	return checkSchemaList(statement, into+2, scratchNamespace)
}

// checkReindex — the REINDEX rules: `CONCURRENTLY` and the `DATABASE`/
// `SCHEMA`/`SYSTEM` forms cannot run in a transaction block.
// A qualified `TABLE`/`INDEX` name only selects which object
// gets rebuilt — reindexing is transaction-safe, so the effect is
// confined to the scratch connection (3.2 rule 4).
func checkReindex(statement StatementTokens) error {
	t := statement.Tokens
	if contains(t, "CONCURRENTLY") {
		return rejected(statement,
			"REINDEX CONCURRENTLY cannot run inside a transaction block")
	}
	objectIndex := reindexObjectIndex(t)
	object := ""
	if objectIndex >= 0 {
		object = t[objectIndex]
	}
	if object == "DATABASE" || object == "SCHEMA" || object == "SYSTEM" {
		return rejected(statement,
			"REINDEX "+object+" cannot run inside a transaction block")
	}
	if contains(t, "TABLESPACE") {
		return rejected(statement,
			"REINDEX ... TABLESPACE moves storage, which is not modeled")
	}
	return nil
}

// Statement heads whose statement body is evaluated immediately.
var executingFirst = set(
	"SELECT", "WITH", "VALUES", "TABLE", "INSERT", "UPDATE", "DELETE",
	"MERGE", "CALL")

// evaluatesNow — whether statement evaluates expressions now: a query,
// DML, or CALL runs its expressions during the desired-script replay,
// but DDL only stores them (a nextval inside a CREATE
// FUNCTION body is an opaque deferred body like REFERENCES,
// per spec §8 rule 5). The exception: CREATE TABLE AS and
// CREATE MATERIALIZED VIEW populate from their query at
// creation, and EXPLAIN ANALYZE really executes its inner
// statement.
func evaluatesNow(t []string, first string) bool {
	if executingFirst[first] {
		return true
	}
	if first == "EXPLAIN" {
		return contains(t, "ANALYZE")
	}
	if first == "CREATE" && indexAtDepthZero(t, "AS", 0) >= 0 {
		object := objectWord(t)
		return object == "TABLE" ||
			(object == "VIEW" && contains(t, "MATERIALIZED"))
	}
	return false
}

// checkSequenceCalls — the literal-argument check for nextval/setval:
// the sequence name is a string like 'schema.seq', so the qualifier
// has to be parsed out of the literal's content. Every literal in the
// first argument is inspected — `CAST('o.s' AS regclass)`,
// `to_regclass('o.s')`, `E'…'` and dollar-quoted forms all
// wrap the same qualified name the bare literal carries.
func checkSequenceCalls(statement StatementTokens, text string,
	scratchNamespace string) error {
	upper := strings.ToUpper(text)
	for _, m := range sequenceCall.FindAllStringIndex(upper, -1) {
		args := callArguments(text, m[1])
		if len(args) == 0 {
			continue
		}
		for _, literal := range LiteralBodies(args[0]) {
			qualifier := literalQualifier(literal)
			if qualifier != "" && !isScratchOrPgTemp(qualifier, scratchNamespace) {
				return rejected(statement, "'"+qualifier+"…' names a sequence in another"+
					" namespace; sequence values do not roll back (3.2 rule 1)")
			}
		}
	}
	return nil
}

// checkScratchLiteral — a namespace literal that can never converge
// (rule-5 rationale): a string whose whole content is the scratch
// name — `'"rcl_x"'` or `'rcl_x'` — is the residue of a
// bare ${namespace} token inside a literal, baked freshly every
// run; and literal text carrying the token without a `.` after it —
// `'${namespace}'`, `'a${namespace}b'` — binds to the
// quoted target on apply but normalisation sees no qualifier, so it
// drifts forever. A token followed by `.` stays legal:
// `'${namespace}.seq'` binds to `'"target".seq'` which
// normalisation maps back.
func checkScratchLiteral(statement StatementTokens,
	scratchNamespace string) error {
	quotedScratch, err := Quote(scratchNamespace)
	if err != nil {
		return err
	}
	for _, body := range LiteralBodies(statement.Text) {
		if body == scratchNamespace || body == quotedScratch {
			return rejected(statement, "'"+body+
				"' is the scratch namespace substituted into a literal;"+
				" a bare ${namespace} inside a literal bakes an unstable name")
		}
		for p := strings.Index(body, token); p >= 0; {
			next := -1
			if p+len(token) >= len(body) || body[p+len(token)] != '.' {
				return rejected(statement, "'"+token+"' inside a literal has no"+
					" target form it can converge to; use "+token+"."+
					" for namespace references or a literal value")
			}
			next = strings.Index(body[p+1:], token)
			if next < 0 {
				break
			}
			p += 1 + next
		}
	}
	return nil
}

// literalQualifier — the namespace qualifier of a literal like
// `schema.name`, `"schema"."name"` or `schema."name"` — in token form
// (quoted verbatim, unquoted upper-cased) so isScratchOrPgTemp
// applies. "" when the literal is an unqualified name.
func literalQualifier(literal string) string {
	if strings.HasPrefix(literal, `"`) {
		var qualifier strings.Builder
		i := 1
		for i < len(literal) {
			c := literal[i]
			if c == '"' {
				if i+1 < len(literal) && literal[i+1] == '"' {
					qualifier.WriteByte('"')
					i += 2
					continue
				}
				if i+1 < len(literal) && literal[i+1] == '.' {
					return `"` + qualifier.String() + `"`
				}
				return ""
			}
			qualifier.WriteByte(c)
			i++
		}
		return ""
	}
	dot := strings.Index(literal, ".")
	if dot < 0 {
		return ""
	}
	return strings.ToUpper(literal[:dot])
}

// checkExecuteLiterals — `EXECUTE '…'` inside a body is dynamic SQL —
// every literal of the EXECUTE statement (a format() template
// argument included) recurses through the full check.
func checkExecuteLiterals(statement StatementTokens,
	scratchNamespace string) error {
	if contains(statement.Tokens, "EXECUTE") {
		for _, sql := range LiteralBodies(statement.Text) {
			if err := Check(collapseEscapes(sql), scratchNamespace); err != nil {
				return err
			}
		}
	}
	return nil
}

// pastModifiers is the index after the run of modifiers starting at from.
func pastModifiers(t []string, from int, modifiers map[string]bool) int {
	i := from
	for i < len(t) && modifiers[t[i]] {
		i++
	}
	return i
}

// pastParen is the index right after the parenthesized group opening at open.
func pastParen(t []string, open int) int {
	depth := 0
	for i := open; i < len(t); i++ {
		if t[i] == "(" {
			depth++
		} else if t[i] == ")" {
			depth--
			if depth == 0 {
				return i + 1
			}
		}
	}
	return len(t)
}

// clusterNamesNoTable — CLUSTER names no table after its `(options)`
// or `VERBOSE` lead-ins — the bare form re-clusters every table and
// cannot run inside a transaction block; `CLUSTER t` is confined.
func clusterNamesNoTable(t []string) bool {
	i := 1
	if i < len(t) && t[i] == "(" {
		i = pastParen(t, i)
	}
	if i < len(t) && t[i] == "VERBOSE" {
		i++
	}
	return i >= len(t)
}

// repackNamesNoTable — REPACK (PostgreSQL 19+) names no table after
// its `(options)` list — bare `REPACK` and `REPACK … USING INDEX`
// repack every maintained table and cannot run inside a transaction
// block; `REPACK t` is confined.
func repackNamesNoTable(t []string) bool {
	i := 1
	if i < len(t) && t[i] == "(" {
		i = pastParen(t, i)
	}
	return i >= len(t) || t[i] == "USING"
}

// collapseEscapes — `”` escapes collapsed to `'`: inside a
// `DO` literal an embedded string arrives doubled, and every level of
// nesting doubles it again — collapsing until stable reveals the
// innermost SQL text.
func collapseEscapes(text string) string {
	for strings.Contains(text, "''") {
		text = strings.ReplaceAll(text, "''", "'")
	}
	return text
}

// unquote is the upper-cased name a token refers to: an upper-cased
// keyword for plain tokens, the identifier's content for a quoted
// token — so a quoted `"pg_advisory_lock"` function name cannot
// evade the session-lock check.
func unquote(tok string) string {
	if len(tok) >= 2 && strings.HasPrefix(tok, `"`) && strings.HasSuffix(tok, `"`) {
		return strings.ToUpper(strings.ReplaceAll(tok[1:len(tok)-1], `""`, `"`))
	}
	return tok
}

// checkSetConfig — every `set_config(name, value, is_local)` call in
// text (string literals included, so `EXECUTE '...'` is covered) must
// pass the literal `true` as its third argument; any other form —
// `false`, an expression, fewer arguments — is rejected.
func checkSetConfig(statement StatementTokens, text string) error {
	upper := strings.ToUpper(text)
	for _, m := range setConfigCall.FindAllStringIndex(upper, -1) {
		// the word without a parenthesized call isn't a call — a column,
		// literal or comment mention must not reject
		if !opensCall(text, m[1]) {
			continue
		}
		args := callArguments(text, m[1])
		if len(args) != 3 ||
			strings.ToUpper(strings.TrimSpace(args[2])) != "TRUE" {
			return rejected(statement,
				"set_config must pass the literal true as is_local: a session-level"+
					" setting leaks onto the pooled connection")
		}
	}
	return nil
}

// opensCall — whether a (possibly quoted) function name ending at
// `from` opens a parenthesized call — mirrors callArguments' lookahead.
func opensCall(text string, from int) bool {
	i := skipCallGap(text, from)
	if i < len(text) && text[i] == '"' {
		i = skipCallGap(text, i+1)
	}
	return i < len(text) && text[i] == '('
}

// skipCallGap skips the gap between a function name and its opening
// parenthesis: whitespace and comments — `--` to end of line and
// (nesting) `/* ... */` — so `nextval --x\n ('s.t')` and
// `setval /*c*/ ('s.t', 1)` cannot evade the call checks.
func skipCallGap(text string, from int) int {
	i := from
	for {
		for i < len(text) && isWhitespace(rune(text[i])) {
			i++
		}
		if i+1 < len(text) && text[i] == '-' && text[i+1] == '-' {
			for i < len(text) && text[i] != '\n' {
				i++
			}
		} else if i+1 < len(text) && text[i] == '/' && text[i+1] == '*' {
			depth := 1
			i += 2
			for i < len(text) && depth > 0 {
				if i+1 < len(text) && text[i] == '/' && text[i+1] == '*' {
					depth++
					i += 2
				} else if i+1 < len(text) && text[i] == '*' && text[i+1] == '/' {
					depth--
					i += 2
				} else {
					i++
				}
			}
		} else {
			return i
		}
	}
}

// callArguments is the top-level comma-separated arguments of the call
// whose name ends at `from`, or nil.
func callArguments(text string, from int) []string {
	i := skipCallGap(text, from)
	// a quoted "set_config" ends at the closing quote before the '('
	if i < len(text) && text[i] == '"' {
		i = skipCallGap(text, i+1)
	}
	if i >= len(text) || text[i] != '(' {
		return nil
	}
	var args []string
	depth := 0
	start := i + 1
	for ; i < len(text); i++ {
		c := text[i]
		if c == '\'' {
			i++
			for i < len(text) &&
				!(text[i] == '\'' && (i+1 >= len(text) || text[i+1] != '\'')) {
				if text[i] == '\'' {
					i += 2
				} else {
					i++
				}
			}
		} else if c == '(' {
			depth++
		} else if c == ')' {
			depth--
			if depth == 0 {
				return append(args, text[start:i])
			}
		} else if c == ',' && depth == 1 {
			args = append(args, text[start:i])
			start = i + 1
		}
	}
	return nil
}

// checkQualifierAt rejects when the token at index begins a
// `qualifier.name` pair whose qualifier is not the scratch namespace.
// `pg_temp` is allowed: temporary objects are session-confined like
// the scratch namespace itself.
func checkQualifierAt(statement StatementTokens, index int,
	scratchNamespace string) error {
	t := statement.Tokens
	if index+2 >= len(t) || t[index+1] != "." {
		return nil
	}
	qualifier := t[index]
	if isScratchOrPgTemp(qualifier, scratchNamespace) {
		return nil
	}
	return rejected(statement, "'"+qualifier+"."+t[index+2]+
		"' names an object in another namespace; objects must be created"+
		" inside ${namespace} or unqualified (3.2 rule 1)")
}

// checkSchemaName rejects a bare schema name that is not the scratch
// namespace.
func checkSchemaName(statement StatementTokens, index int,
	scratchNamespace string) error {
	t := statement.Tokens
	if index >= len(t) || isScratchOrPgTemp(t[index], scratchNamespace) {
		return nil
	}
	return rejected(statement, "'"+t[index]+
		"' names another namespace; schema-level grants are only allowed"+
		" on ${namespace} itself (3.2 rule 1)")
}

// isScratchOrPgTemp is true when token names the scratch namespace or
// the session's pg_temp. A quoted token (its quotes are preserved)
// compares case-sensitively — `"S".t` is a different schema from
// `s.t`; an unquoted token compares case-insensitively, which is
// sound because the scratch name is fold-safe — it is already its own
// lowercase fold (ScratchName).
func isScratchOrPgTemp(tok, scratchNamespace string) bool {
	if strings.HasPrefix(tok, `"`) {
		quotedScratch, err := Quote(scratchNamespace)
		return (err == nil && tok == quotedScratch) || tok == `"pg_temp"`
	}
	return strings.EqualFold(tok, scratchNamespace) || tok == "PG_TEMP"
}

// checkQualifiedAfter — the qualified-name check for the operand after
// `keyword [second]` — ALTER sub-commands (ATTACH/DETACH PARTITION)
// whose operand may name an object in another namespace.
func checkQualifiedAfter(statement StatementTokens, t []string,
	keyword, second, scratchNamespace string) error {
	for i := 0; i+1 < len(t); i++ {
		if t[i] == keyword && t[i+1] == second {
			if err := checkQualifierAt(statement, i+2, scratchNamespace); err != nil {
				return err
			}
		}
	}
	return nil
}

// nameIndex is the object-name index of a CREATE/DROP/ALTER statement:
// kind word + modifiers.
func nameIndex(t []string) int {
	i := kindIndex(t) + 1
	for i < len(t) && nameModifiers[t[i]] {
		i++
	}
	return i
}

// checkNameAt — the qualified-name check at an object-name position
// found by skipping modifiers. When the skipped word itself was the
// qualifier — `CREATE TABLE exists.evil`, where `exists` is an
// unreserved keyword the modifier scan treats as a modifier — the
// scan lands on the dot, and the token before it is the qualifier to
// check.
func checkNameAt(statement StatementTokens, index int,
	scratchNamespace string) error {
	t := statement.Tokens
	if index > 0 && index < len(t) && t[index] == "." {
		return checkQualifierAt(statement, index-1, scratchNamespace)
	}
	return checkQualifierAt(statement, index, scratchNamespace)
}

// kindIndex is the index of the object-kind word, skipping
// CREATE/DROP/ALTER modifiers.
func kindIndex(t []string) int {
	i := 1
	for i < len(t) && headModifiers[t[i]] {
		i++
	}
	return i
}

// checkFtsAlter — the `ALTER TEXT SEARCH` carve-out. Managed
// text-search objects make two member-mutation forms snapshot state: a
// configuration's `MAPPING` actions (`pg_ts_config_map` rows fold into
// the configuration object) and a dictionary's option redefinition
// (`(opt = value, …)`, the `dictinitoption` field). Every
// other form — `RENAME`, `OWNER TO`, `SET SCHEMA` and
// every parser/template body — touches state the snapshot does not
// model and rejects. Dictionary references inside `WITH` and
// `REPLACE` name lists still pass the qualifier check.
func checkFtsAlter(statement StatementTokens,
	scratchNamespace string) error {
	t := statement.Tokens
	name := nameIndex(t)
	kind := ""
	if name > 3 {
		kind = t[name-1]
	}
	rest := name + 1
	if rest+1 < len(t) && t[rest] == "." {
		rest += 2
	}
	switch kind {
	case "CONFIGURATION":
		if rest+1 < len(t) && (t[rest] == "ADD" ||
			t[rest] == "ALTER" || t[rest] == "DROP") &&
			t[rest+1] == "MAPPING" {
			depth := 0
			for i := rest + 2; i < len(t); i++ {
				w := t[i]
				if w == "(" {
					depth++
				} else if w == ")" {
					depth--
				} else if depth == 0 && scratchNamespace != "" &&
					(w == "WITH" || w == "REPLACE") {
					if err := checkQualifierAt(statement, i+1, scratchNamespace); err != nil {
						return err
					}
					if err := checkNameList(statement, i+1, scratchNamespace); err != nil {
						return err
					}
				}
			}
			return nil
		}
		return rejected(statement, "ALTER TEXT SEARCH "+kind+
			" mutates state the snapshot does not model")
	case "DICTIONARY":
		if rest < len(t) && t[rest] == "(" {
			return nil
		}
		return rejected(statement, "ALTER TEXT SEARCH DICTIONARY"+
			" mutates state the snapshot does not model")
	default:
		return rejected(statement, "ALTER TEXT SEARCH mutates"+
			" members the snapshot does not model")
	}
}

// checkOperatorAlter — the `ALTER OPERATOR` carve-out. A class's member
// list is snapshot state (the `pg_amop`/`pg_amproc` rows fold into the
// `OPERATOR_CLASS` canonical), so `ALTER OPERATOR
// CLASS n USING am ADD|DROP OPERATOR|FUNCTION …` is legal.
// `ALTER OPERATOR FAMILY … ADD|DROP` member mutations are not —
// standalone family members are outside the snapshot model — and
// every other form (`RENAME`, `OWNER TO`,
// `SET SCHEMA`, plain-operator alters) rejects.
func checkOperatorAlter(statement StatementTokens) error {
	t := statement.Tokens
	name := nameIndex(t)
	kind := ""
	if name > 1 {
		kind = t[name-1]
	}
	rest := name + 1
	if rest+1 < len(t) && t[rest] == "." {
		rest += 2
	}
	if kind == "CLASS" && rest+3 < len(t) &&
		t[rest] == "USING" &&
		(t[rest+2] == "ADD" || t[rest+2] == "DROP") &&
		(t[rest+3] == "OPERATOR" || t[rest+3] == "FUNCTION") {
		return nil
	}
	if kind != "" {
		kind += " "
	}
	return rejected(statement, "ALTER OPERATOR "+kind+
		"mutates state the snapshot does not model")
}

// checkOnTarget — the qualified-name check for the object after `ON`
// (index/table).
func checkOnTarget(statement StatementTokens, onIndex int,
	scratchNamespace string) error {
	if onIndex < 0 {
		return nil
	}
	i := onIndex + 1
	if i < len(statement.Tokens) && statement.Tokens[i] == "ONLY" {
		i++
	}
	return checkNameAt(statement, i, scratchNamespace)
}

// checkGrantTarget — the qualified-name check for a GRANT/REVOKE
// object: database-wide targets are rejected outright, `ON SCHEMA`
// and `ON ALL ... IN SCHEMA` take a namespace name, object kinds take
// a possibly qualified object name.
func checkGrantTarget(statement StatementTokens,
	scratchNamespace string) error {
	t := statement.Tokens
	on := indexAtDepthZero(t, "ON", 0)
	i := on + 1
	if i >= len(t) {
		return nil
	}
	w := t[i]
	// ON FOREIGN TABLE grants are namespace-scoped; FOREIGN SERVER and
	// FOREIGN DATA WRAPPER are database-wide
	foreignTable := w == "FOREIGN" && i+1 < len(t) && t[i+1] == "TABLE"
	if grantDatabaseWide[w] && !foreignTable {
		return rejected(statement, "ON "+w+
			" is a database-wide grant and cannot be represented in a snapshot")
	}
	if w == "ALL" {
		if schema := indexAtDepthZero(t, "SCHEMA", 0); schema >= 0 {
			return checkSchemaList(statement, schema+1, scratchNamespace)
		}
		return nil
	}
	if w == "SCHEMA" {
		// GRANT/REVOKE ... ON SCHEMA <scratch> mutates the namespace's
		// own acl — modeled as the PRIVILEGES object named <namespace>
		// (A.5); every listed schema must be the scratch namespace
		return checkSchemaList(statement, i+1, scratchNamespace)
	}
	if foreignTable || grantKinds[w] {
		if foreignTable {
			i += 2
		} else {
			i++
		}
	}
	if err := checkQualifierAt(statement, i, scratchNamespace); err != nil {
		return err
	}
	return checkNameList(statement, i, scratchNamespace)
}

// checkSchemaList — the bare-schema-name check for a `,`-separated
// list — `ON SCHEMA a, b` and `ON ALL TABLES IN SCHEMA a, b` — bounded by the
// grantee clause (TO in GRANT, FROM in REVOKE) so role lists behind it
// are not mistaken for schema names.
func checkSchemaList(statement StatementTokens, index int,
	scratchNamespace string) error {
	t := statement.Tokens
	end := len(t)
	for j := index; j < len(t); j++ {
		if t[j] == "TO" || t[j] == "FROM" {
			end = j
			break
		}
	}
	if err := checkSchemaName(statement, index, scratchNamespace); err != nil {
		return err
	}
	for j := index; j+1 < end; j++ {
		if t[j] == "," {
			if err := checkSchemaName(statement, j+1, scratchNamespace); err != nil {
				return err
			}
		}
	}
	return nil
}

// checkSchemaAlter — the `ALTER SCHEMA` carve-out: only
// `ALTER SCHEMA <scratch> OWNER TO <role>` is snapshot state — the
// namespace's OWNERSHIP object (A.5). `RENAME` and every other form
// mutates the namespace object itself, which the core owns; a foreign
// schema name or a malformed tail rejects. Returns silently on the name
// check when scratchNamespace is "" — the form check alone decides
// admissibility.
func checkSchemaAlter(statement StatementTokens,
	scratchNamespace string) error {
	t := statement.Tokens
	name := nameIndex(t)
	if len(t) != name+4 || t[name+1] != "OWNER" || t[name+2] != "TO" {
		return rejected(statement,
			"ALTER SCHEMA mutates the namespace object itself; only"+
				" ALTER SCHEMA ${namespace} OWNER TO <role>"+
				" is modeled")
	}
	if scratchNamespace == "" {
		return nil
	}
	return checkSchemaName(statement, name, scratchNamespace)
}

// checkNameList — the qualified-name check for every name of a
// top-level `,`-separated list — a `DROP TABLE a, s.b` or `GRANT ON
// TABLE a, s.b` carries several objects. Commas inside parentheses are
// signature arguments, not list separators.
func checkNameList(statement StatementTokens, fromIndex int,
	scratchNamespace string) error {
	t := statement.Tokens
	depth := 0
	// the scan includes fromIndex itself: a modifier-word name (…
	// exists, s.t) makes the name index land on the first list comma,
	// and the name after it still has to be checked
	for i := fromIndex; i < len(t); i++ {
		w := t[i]
		if w == "(" {
			depth++
		} else if w == ")" {
			depth--
		} else if depth == 0 && w == "," {
			if err := checkQualifierAt(statement, i+1, scratchNamespace); err != nil {
				return err
			}
		}
	}
	return nil
}

// checkCommentTarget — the qualified-name check for COMMENT ON /
// SECURITY LABEL ON. Database-wide kinds are rejected outright and
// `ON SCHEMA` takes a namespace name; the rest of the target is
// scanned between `ON` and `IS`: a `q.n.m` triple always exposes
// namespace qualifier `q`, and a two-part `q.n` is a qualifier for
// every kind except `COLUMN`, where it is a `table.column` pair.
func checkCommentTarget(statement StatementTokens,
	scratchNamespace string) error {
	t := statement.Tokens
	// the target ON is fixed per leading form: COMMENT takes it at the
	// head; SECURITY LABEL takes it after FOR <provider> — a provider
	// named after a keyword must not shift the anchor, and an object
	// named for must not fake the provider form (FOR sits at index 2)
	on := indexAtDepthZero(t, "ON", 0)
	if t[0] == "SECURITY" && len(t) > 2 && t[2] == "FOR" {
		on = 4
	}
	// the keyword IS is the last "IS" token — an earlier one can be a
	// keyword-named identifier (a column named "is"), which would
	// truncate the target scan and let a foreign qualifier through
	is := lastIndexOf(t, "IS")
	if on < 0 || is < 0 || on+1 >= len(t) {
		return nil
	}
	kind := t[on+1]
	if commentDatabaseWide[kind] ||
		(kind == "FOREIGN" && on+2 < len(t) &&
			(t[on+2] == "SERVER" || t[on+2] == "DATA")) {
		return rejected(statement, kind+
			" has no namespace anchor; a comment or security label on it"+
			" can never appear in a snapshot (3.2 rule 1)")
	}
	if kind == "SCHEMA" {
		return rejected(statement,
			"SCHEMA is the reconcile unit itself; a comment or"+
				" security label on it can never appear in a"+
				" namespace snapshot")
	}
	if scratchNamespace == "" {
		return nil
	}
	column := kind == "COLUMN"
	end := is
	if signatureTargetKinds[kind] {
		for i := on + 1; i < is; i++ {
			if t[i] == "(" {
				end = i
				break
			}
		}
	}
	for i := on + 1; i+2 < end; i++ {
		if t[i+1] != "." {
			continue
		}
		if i+4 < end && t[i+3] == "." {
			return checkQualifierAt(statement, i, scratchNamespace)
		}
		if !column {
			return checkQualifierAt(statement, i, scratchNamespace)
		}
		i++
	}
	return nil
}

// adjacent — true when a is immediately followed by b in the token list.
func adjacent(t []string, a, b string) bool {
	for i := 0; i+1 < len(t); i++ {
		if t[i] == a && t[i+1] == b {
			return true
		}
	}
	return false
}

// objectWord is the object-kind word after CREATE/DROP/ALTER and its
// modifiers, or "".
func objectWord(t []string) string {
	i := kindIndex(t)
	if i < len(t) {
		return t[i]
	}
	return ""
}

// reindexObjectIndex is the object-kind index of a REINDEX statement —
// TABLE, INDEX, SCHEMA, DATABASE or SYSTEM — skipping the `(...)`
// option list and the CONCURRENTLY/VERBOSE modifiers of the legacy
// syntax.
func reindexObjectIndex(t []string) int {
	depth := 0
	for i := 1; i < len(t); i++ {
		w := t[i]
		if w == "(" {
			depth++
		} else if w == ")" {
			depth--
		} else if depth == 0 && w != "CONCURRENTLY" && w != "VERBOSE" {
			return i
		}
	}
	return -1
}

// indexAtDepthZero is the index of word at parenthesis depth 0, or -1.
func indexAtDepthZero(t []string, word string, from int) int {
	depth := 0
	for i := from; i < len(t); i++ {
		w := t[i]
		if w == "(" {
			depth++
		} else if w == ")" {
			depth--
		} else if depth == 0 && w == word {
			return i
		}
	}
	return -1
}

func indexOf(t []string, word string) int {
	for i, w := range t {
		if w == word {
			return i
		}
	}
	return -1
}

func lastIndexOf(t []string, word string) int {
	for i := len(t) - 1; i >= 0; i-- {
		if t[i] == word {
			return i
		}
	}
	return -1
}

func contains(t []string, word string) bool {
	return indexOf(t, word) >= 0
}

func set(words ...string) map[string]bool {
	m := make(map[string]bool, len(words))
	for _, w := range words {
		m[w] = true
	}
	return m
}

func rejected(statement StatementTokens, reason string) error {
	return core.NewDesiredStateErrorf("the desired script was rejected at position "+
		"%d: %s (\"%s\")", statement.Offset, reason,
		strings.TrimSpace(statement.Text))
}
