package pg

// Attribute keys used by the renderer (PgCatalog.ts).
const (
	AttrCreateSQL  = "createSql"
	AttrDropSQL    = "dropSql"
	AttrPostSQL    = "postSql"
	AttrOwnedSQL   = "ownedSql"
	AttrOptionsSQL = "optionsSql"
	AttrColumnSQL  = "columnSql"
	AttrAttnum     = "attnum"
	AttrNotnull    = "notnull"
	AttrDefault    = "default"
	attrSigArgs    = "sigargs"
	// AttrProkind — `f` function or `p` procedure — ALTER ... OWNER TO
	// applies to both.
	AttrProkind    = "prokind"
	AttrReturnType = "returnType"
	// AttrTableOptions — UNLOGGED/WITH/REPLICA IDENTITY fragments a
	// CREATE TABLE appends.
	AttrTableOptions = "tableOptions"
	// AttrReplident — `d`/`n`/`f`/`i` — `REPLICA IDENTITY USING INDEX`
	// rides the index.
	AttrReplident = "replident"
	AttrPartkey   = "partkey"
	// AttrGrantTarget — the object a PRIVILEGES object grants on
	// ("TABLE t" form).
	AttrGrantTarget = "grantTarget"
	// AttrGrantColumn — the column list of a column-level grant, or
	// absent for object grants.
	AttrGrantColumn = "grantColumn"
	// AttrGrantPrefix — the privilege prefix of a DEFAULT_PRIVILEGES
	// canonical ("SELECT"…): the grant text itself stays folded into
	// the definition.
	AttrGrantPrefix = "grantPrefix"
	AttrFtServer    = "ftServer"
	AttrFtOptions   = "ftOptions"
	// AttrPopulated — `false` on a materialized view created WITH NO
	// DATA — dump emits REFRESH.
	AttrPopulated = "populated"
	// AttrDumpSQL — the dump-time create fragment for this object —
	// `\u0000`-separated statements (dumps write several objects'
	// create fragments as one unit: a table with its folded serial
	// columns).
	AttrDumpSQL = "dumpSql"
)
