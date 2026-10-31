import { Dialect } from "@epicquest-studios/schema-reconciler-core";
import { Definition } from "@epicquest-studios/schema-reconciler-core";
import { ObjectId, ObjectIds, ObjectKind } from "@epicquest-studios/schema-reconciler-core";
import { ObjectMap, ObjectSet } from "@epicquest-studios/schema-reconciler-core";
import { SchemaObject, SchemaSnapshot } from "@epicquest-studios/schema-reconciler-core";
import type { SqlConnection } from "@epicquest-studios/schema-reconciler-core";
import * as PgIdentifiers from "./PgIdentifiers.ts";
import * as PgSql from "./PgSql.ts";

/**
 * The catalog snapshot reader of spec A.5. Reads with `search_path` bound
 * to the namespace so engine-rendered definitions omit its qualifier; any
 * remaining qualifier is tokenised by the 5.3 normaliser.
 */

/** Attribute keys used by the renderer. */
export const CREATE_SQL = "createSql";
export const DROP_SQL = "dropSql";
export const POST_SQL = "postSql";
export const OWNED_SQL = "ownedSql";
export const OPTIONS_SQL = "optionsSql";
export const COLUMN_SQL = "columnSql";
export const ATTNUM = "attnum";
export const NOTNULL = "notnull";
export const DEFAULT = "default";
const SIG_ARGS = "sigargs";
/** `f` function or `p` procedure — ALTER ... OWNER TO applies to both. */
export const PROKIND = "prokind";
export const RETURN_TYPE = "returnType";
/** UNLOGGED/WITH/REPLICA IDENTITY fragments a CREATE TABLE appends. */
export const TABLE_OPTIONS = "tableOptions";
/** `d`/`n`/`f`/`i` — `REPLICA IDENTITY USING INDEX` rides the index. */
export const REPLIDENT = "replident";
export const PARTKEY = "partkey";
/** The object a PRIVILEGES object grants on (`"TABLE t"` form). */
export const GRANT_TARGET = "grantTarget";
/** The column list of a column-level grant, or `undefined` for object grants. */
export const GRANT_COLUMN = "grantColumn";
/**
 * The privilege prefix of a DEFAULT_PRIVILEGES canonical
 * (`"SELECT"`…): the grant text itself stays folded into `def`.
 */
export const GRANT_PREFIX = "grantPrefix";
export const FT_SERVER = "ftServer";
export const FT_OPTIONS = "ftOptions";
/** `false` on a materialized view created WITH NO DATA — dump emits REFRESH. */
export const POPULATED = "populated";
/**
 * The dump-time create fragment for this object —
 * `\u0000`-separated statements (dumps write several objects'
 * create fragments as one unit: a table with its folded serial columns).
 */
export const DUMP_SQL = "dumpSql";

export async function read(conn: SqlConnection, namespace: string): Promise<SchemaSnapshot> {
    await conn.execute("SET LOCAL search_path = " + PgIdentifiers.quote(namespace));

    const data = new Data(namespace);
    data.serverMajor = (await conn.metadata()).databaseMajorVersion;
    await data.loadExtensionOwned(conn);
    await data.loadAutoTypeFamily(conn);
    await data.loadRelations(conn);
    await data.loadColumns(conn);
    await data.loadConstraints(conn);
    await data.loadIndexes(conn);
    await data.loadViews(conn);
    await data.loadSequences(conn);
    await data.loadRoutines(conn);
    await data.loadAggregates(conn);
    await data.loadTriggers(conn);
    await data.loadEnums(conn);
    await data.loadDomains(conn);
    await data.loadComposites(conn);
    await data.loadRanges(conn);
    await data.loadPolicies(conn);
    await data.loadStatistics(conn);
    await data.loadRules(conn);
    await data.loadCollations(conn);
    await data.loadConversions(conn);
    await data.loadTextSearch(conn);
    await data.loadOperators(conn);
    await data.loadSecLabels(conn);
    await data.loadOther(conn);
    await data.loadComments(conn);
    await data.loadDependencies(conn);
    await data.loadExternalDependents(conn);
    data.computeDumpSql();
    return data.snapshot();
}

/** int8/oid columns come back as strings from the driver — parse explicitly. */
function num(v: unknown): number {
    if (v == null) {
        return 0;
    }
    return typeof v === "number" ? v : Number(v);
}

function numOrNull(v: unknown): number | null {
    return v == null ? null : num(v);
}

function str(v: unknown): string {
    return v == null ? "" : String(v);
}

function strOrNull(v: unknown): string | null {
    return v == null ? null : String(v);
}

function bool(v: unknown): boolean {
    return v === true || v === "t" || v === "true";
}

function identitySqlOptions(row: Row): string {
    if (row.seqincrement == null) {
        return "";
    }
    return "INCREMENT BY " + str(row.seqincrement)
            + " MINVALUE " + str(row.seqmin)
            + " MAXVALUE " + str(row.seqmax)
            + " START WITH " + str(row.seqstart)
            + " CACHE " + str(row.seqcache)
            + (bool(row.seqcycle) ? " CYCLE" : " NO CYCLE");
}

function identityCanonical(row: Row): string {
    if (row.seqincrement == null) {
        return "";
    }
    return "type=" + str(row.idtype)
            + ";increment=" + str(row.seqincrement)
            + ";min=" + str(row.seqmin)
            + ";max=" + str(row.seqmax)
            + ";start=" + str(row.seqstart)
            + ";cache=" + str(row.seqcache)
            + ";cycle=" + bool(row.seqcycle);
}

function nullToEmpty(s: string | null): string {
    return s === null ? "" : s;
}

function reloptions(text: string | null): string[] {
    if (text === null || text.length < 3) {
        return [];
    }
    const inner = text.substring(1, text.length - 1);
    return [...new Set(inner.split(",").map(s => s.trim()))].sort();
}

/** Namespace-scoped catalog rows resolved to a stable display name, for
 *  locating comments and security labels. Every SELECT emits
 *  (classid, objoid, name); the parameter order matches the SELECTs. */
const NS_OBJECTS = `
        SELECT 'pg_class'::regclass AS cls, c.oid AS obj, c.relname AS name
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = $1
        UNION ALL SELECT 'pg_proc', p.oid,
                p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')'
        FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace AND n.nspname = $2
        UNION ALL SELECT 'pg_type', t.oid, t.typname
        FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace AND n.nspname = $3
        UNION ALL SELECT 'pg_constraint', c.oid, rel.relname || '.' || c.conname
        FROM pg_constraint c JOIN pg_class rel ON rel.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = rel.relnamespace AND n.nspname = $4
        UNION ALL SELECT 'pg_rewrite', r.oid, c.relname || '.' || r.rulename
        FROM pg_rewrite r JOIN pg_class c ON c.oid = r.ev_class
        JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = $5
        UNION ALL SELECT 'pg_namespace', n.oid, 'namespace'
        FROM pg_namespace n WHERE n.nspname = $6
        UNION ALL SELECT 'pg_policy', p.oid, c.relname || '.' || p.polname
        FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = $7
        UNION ALL SELECT 'pg_collation', cl.oid, cl.collname
        FROM pg_collation cl JOIN pg_namespace n ON n.oid = cl.collnamespace AND n.nspname = $8
        UNION ALL SELECT 'pg_statistic_ext', s.oid, s.stxname
        FROM pg_statistic_ext s JOIN pg_namespace n ON n.oid = s.stxnamespace AND n.nspname = $9
        UNION ALL SELECT 'pg_ts_config', g.oid, g.cfgname
        FROM pg_ts_config g JOIN pg_namespace n ON n.oid = g.cfgnamespace AND n.nspname = $10
        UNION ALL SELECT 'pg_ts_dict', g.oid, g.dictname
        FROM pg_ts_dict g JOIN pg_namespace n ON n.oid = g.dictnamespace AND n.nspname = $11
        UNION ALL SELECT 'pg_ts_parser', g.oid, g.prsname
        FROM pg_ts_parser g JOIN pg_namespace n ON n.oid = g.prsnamespace AND n.nspname = $12
        UNION ALL SELECT 'pg_ts_template', g.oid, g.tmplname
        FROM pg_ts_template g JOIN pg_namespace n ON n.oid = g.tmplnamespace AND n.nspname = $13
        UNION ALL SELECT 'pg_operator', g.oid, g.oprname
        FROM pg_operator g JOIN pg_namespace n ON n.oid = g.oprnamespace AND n.nspname = $14
        UNION ALL SELECT 'pg_opclass', g.oid, g.opcname
        FROM pg_opclass g JOIN pg_namespace n ON n.oid = g.opcnamespace AND n.nspname = $15
        UNION ALL SELECT 'pg_opfamily', g.oid, g.opfname
        FROM pg_opfamily g JOIN pg_namespace n ON n.oid = g.opfnamespace AND n.nspname = $16
        UNION ALL SELECT 'pg_trigger', t.oid, c.relname || '.' || t.tgname
        FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = $17
        `;

function nsParams(ns: string, count: number): string[] {
    return new Array(count).fill(ns);
}

/** A `text[]` value: `'{a,"b,c","d""e"}'` → elements (or a driver-parsed array). */
function textArray(v: unknown): string[] {
    if (v == null) {
        return [];
    }
    if (Array.isArray(v)) {
        return v.map(String);
    }
    const s = String(v);
    if (!s.startsWith("{") || !s.endsWith("}")) {
        return [s];
    }
    const out: string[] = [];
    const inner = s.substring(1, s.length - 1);
    for (let i = 0; i <= inner.length;) {
        if (inner.charAt(i) === '"') {
            let el = "";
            i++;
            while (i < inner.length && inner.charAt(i) !== '"') {
                if (inner.charAt(i) === "\\") {
                    i++;
                }
                el += inner.charAt(i);
                i++;
            }
            out.push(el);
            i++;
        } else {
            const comma = inner.indexOf(",", i);
            const end = comma < 0 ? inner.length : comma;
            out.push(inner.substring(i, end));
            i = end;
        }
        while (i < inner.length && inner.charAt(i) === ",") {
            i++;
        }
        if (i >= inner.length) {
            break;
        }
    }
    return s === "{}" ? [] : out;
}

/**
 * SQL for a deterministic rendering of an ACL column: one
 * `grantee:PRIVILEGE[*]` token per distinct entry (PUBLIC for
 * grantee 0, `<owner>` for the object's owner, `*` for
 * grantable), sorted, so grant order cannot cause drift. The
 * grantor is dropped — who made a grant is provenance, not desired
 * state: a grant recorded under a different grantor is the same
 * held privilege, and comparing it would drift forever (a
 * REVOKE+GRANT pair cannot rename the grantor anyway).
 */
function aclTokens(acl: string, owner: string): string {
    return "(SELECT string_agg(tok, ',' ORDER BY tok) FROM (SELECT DISTINCT"
            + " CASE WHEN a.grantee = 0 THEN 'PUBLIC'"
            + " WHEN a.grantee = " + owner + " THEN '<owner>'"
            + " ELSE a.grantee::regrole::text END || ':' || a.privilege_type ||"
            + " CASE WHEN a.is_grantable THEN '*' ELSE '' END AS tok"
            + " FROM aclexplode(" + acl + ") a) toks)";
}

/** The mirrored COMMENT id for a managed target, with the optional
 *  name suffix used to break collisions. */
function commentId(target: ObjectId, suffix: string): ObjectId {
    const name = target.name + suffix;
    return target.parent !== undefined
            ? new ObjectId(ObjectKind.COMMENT, name, target.parent)
            : new ObjectId(ObjectKind.COMMENT, name);
}

/** The mirrored SECURITY_LABEL id for a managed target and provider,
 *  with the optional collision suffix. */
function seclabelId(target: ObjectId, provider: string, suffix: string): ObjectId {
    const name = provider + ":" + target.name + suffix;
    return target.parent !== undefined
            ? new ObjectId(ObjectKind.SECURITY_LABEL, name, target.parent)
            : new ObjectId(ObjectKind.SECURITY_LABEL, name);
}

/** The mirrored OWNERSHIP id for a managed target, with the optional
 *  collision suffix. */
function ownershipId(target: ObjectId, suffix: string): ObjectId {
    const name = target.name + suffix;
    return target.parent !== undefined
            ? new ObjectId(ObjectKind.OWNERSHIP, name, target.parent)
            : new ObjectId(ObjectKind.OWNERSHIP, name);
}

/** The mirrored PRIVILEGES id for a managed target, with the optional
 *  collision suffix. */
function privilegesId(target: ObjectId, suffix: string): ObjectId {
    const name = target.name + suffix;
    return target.parent !== undefined
            ? new ObjectId(ObjectKind.PRIVILEGES, name, target.parent)
            : new ObjectId(ObjectKind.PRIVILEGES, name);
}

/**
 * The GRANT object-class word of a `pg_default_acl` objtype —
 * `r` tables, `S` sequences, `f` functions and
 * procedures, `T` types. An unknown future objtype renders no
 * statement and is skipped.
 */
function defaultAclClass(objtype: string): string | null {
    switch (objtype) {
        case "r": return "TABLES";
        case "S": return "SEQUENCES";
        case "f": return "FUNCTIONS";
        case "T": return "TYPES";
        default: return null;
    }
}

/** A managed namespace object an anchored catalog row can belong
 *  to — a null or `OTHER` anchor leaves the row unsupported. */
function managedAnchor(id: ObjectId | null | undefined): boolean {
    return id != null && id.kind !== ObjectKind.OTHER;
}

/** An in-namespace `OTHER` anchor — the row references an
 *  unsupported object, so it can never be created. */
function otherAnchor(id: ObjectId | null | undefined): boolean {
    return id != null && id.kind === ObjectKind.OTHER;
}

/**
 * The GRANT list of a PRIVILEGES object: one statement per
 * `grantee:PRIVILEGE[*]` token of the canonical — a bootstrap
 * dump replays the grants the way the reconcile diff renders them.
 * Implicit `<owner>` rights are not grants and are skipped.
 */
function privilegeDumpStatements(o: { def: Definition | undefined;
        attrs: Map<string, string> }): string[] {
    const target = o.attrs.get(GRANT_TARGET);
    if (target === undefined) {
        return [];
    }
    const column = o.attrs.get(GRANT_COLUMN);
    const colClause = column === undefined ? "" : " (" + column + ")";
    const prefix = o.attrs.get(GRANT_PREFIX);
    const pfx = prefix === undefined ? "" : prefix + " ";
    let c = o.def?.canonical ?? "";
    c = c.startsWith("privileges=")
            ? c.substring("privileges=".length) : c;
    const grants: string[] = [];
    if (c !== "") {
        for (const tok of c.split(",")) {
            const g = tok.substring(0, tok.lastIndexOf(":"));
            if (g === "<owner>") {
                continue;
            }
            let p = tok.substring(tok.lastIndexOf(":") + 1);
            const option = p.endsWith("*");
            if (option) {
                p = p.substring(0, p.length - 1);
            }
            grants.push(pfx + "GRANT " + p + colClause + " ON " + target + " TO "
                    + (g === "PUBLIC" ? g : PgIdentifiers.quote(g))
                    + (option ? " WITH GRANT OPTION" : ""));
        }
    }
    return grants;
}

function fragments(o: { attrs: Map<string, string> },
        ...keys: string[]): string[] {
    const out: string[] = [];
    for (const key of keys) {
        const sql = o.attrs.get(key);
        if (sql !== undefined) {
            out.push(sql);
        }
    }
    return out;
}

/** A scalar `name=value` field of a canonical definition. */
function canonicalField(canonical: string, name: string): string {
    for (const part of PgSql.splitOutsideLiterals(canonical, ";")) {
        if (part.startsWith(name + "=")) {
            return part.substring(name.length + 1);
        }
    }
    return "";
}

function nullToDash(v: string | null): string {
    return v === null || v.trim().length === 0 || v === "0" || v === "-"
            ? "-" : v;
}

/** A single-quoted SQL literal for a canonical or DDL fragment. */
function sqlLiteral(value: string): string {
    return "'" + value.replaceAll("'", "''") + "'";
}

/** Adds a `KEY = value` aggregate option when non-null. */
function aggOpt(options: string[], key: string, value: string | null): void {
    if (value !== null) {
        options.push(key + " = " + value);
    }
}

/** `FINALFUNC_MODIFY`/`MFINALFUNC_MODIFY` — `'r'`
 *  (read-only) is the default and stays implicit. */
function aggModify(options: string[], key: string, value: string | null): void {
    switch (value) {
        case "w":
            options.push(key + " = READ_WRITE");
            break;
        case "s":
            options.push(key + " = SHAREABLE");
            break;
        default:
            break;
    }
}

type Row = Record<string, unknown>;

/** Mutable per-object accumulation. */
class Obj {
    def: Definition | undefined;
    readonly attrs = new Map<string, string>();
}

/** Rows accumulated while reading, then assembled into SchemaObjects. */
class Data {
    private readonly ns: string;
    private readonly qNs: string;
    serverMajor = 0;
    private readonly objects = new ObjectMap<Obj>();
    private readonly relByOid = new Map<number, ObjectId>();
    private readonly procByOid = new Map<number, ObjectId>();
    private readonly conByOid = new Map<number, ObjectId>();
    /** Backing-index oid → the constraint it backs (pk/unique constraint deps). */
    private readonly conByIndexOid = new Map<number, ObjectId>();
    private readonly typeByOid = new Map<number, ObjectId>();
    private readonly polByOid = new Map<number, ObjectId>();
    private readonly tgByOid = new Map<number, ObjectId>();
    private readonly stxByOid = new Map<number, ObjectId>();
    private readonly ruleByOid = new Map<number, ObjectId>();
    private readonly convByOid = new Map<number, ObjectId>();
    private readonly collByOid = new Map<number, ObjectId>();
    private readonly langByOid = new Map<number, ObjectId>();
    private readonly tmplByOid = new Map<number, ObjectId>();
    private readonly prsByOid = new Map<number, ObjectId>();
    private readonly dictByOid = new Map<number, ObjectId>();
    private readonly cfgByOid = new Map<number, ObjectId>();
    private readonly oprByOid = new Map<number, ObjectId>();
    private readonly opcByOid = new Map<number, ObjectId>();
    private readonly opfByOid = new Map<number, ObjectId>();
    /** Catalog-row oid -> the OTHER object it loaded as, per classid, so
     *  an unsupported object that depends on a managed one still records
     *  the edge and blocks an unrecreatable drop (5.8.3). */
    private readonly otherByOid = new Map<string, Map<number, ObjectId>>();
    private readonly deps = new ObjectMap<ObjectSet>();
    /** Catalog oids owned by an extension (pg_depend deptype 'e'). Member
     *  objects cannot be created or dropped standalone, so they never
     *  enter the model; the extension itself is reported as OTHER. */
    private readonly extOwned = new Set<number>();
    /** A range type's generated members (its multirange and the
     *  constructor routines and `range→multirange` cast recorded
     *  with `deptype 'i'`) fold into the range like a table's
     *  rowtype — they are never objects, their oids remap to the
     *  `TYPE` id so dependents still order after the range. */
    private readonly autoProcs = new Map<number, number>();
    private readonly autoCasts = new Map<number, number>();
    /** Oids that must never resolve to an annotation/acl target: a
     *  comment or grant on a folded member has no renderable target
     *  (GRANT on a multirange is even rejected by the server). */
    private readonly foldedMembers = new Set<number>();
    private readonly seqOptions = new ObjectMap<string>();
    private readonly seqOwnedBy = new ObjectMap<string>();

    constructor(ns: string) {
        this.ns = ns;
        this.qNs = PgIdentifiers.quote(ns);
    }

    private obj(id: ObjectId): Obj {
        let o = this.objects.get(id);
        if (o === undefined) {
            o = new Obj();
            this.objects.set(id, o);
        }
        return o;
    }

    private define(id: ObjectId, canonical: string,
                   attrs: ReadonlyMap<string, string>): void {
        const o = this.obj(id);
        o.def = new Definition(PgSql.normalize(canonical, this.ns));
        for (const [k, v] of attrs) {
            o.attrs.set(k, PgSql.normalize(v, this.ns));
        }
    }

    private dep(from: ObjectId | null | undefined, to: ObjectId | null | undefined): void {
        if (from != null && to != null && !to.equals(from)) {
            let set = this.deps.get(from);
            if (set === undefined) {
                set = new ObjectSet();
                this.deps.set(from, set);
            }
            set.add(to);
        }
    }

    private otherOid(cls: string, oid: number, id: ObjectId): void {
        let map = this.otherByOid.get(cls);
        if (map === undefined) {
            map = new Map();
            this.otherByOid.set(cls, map);
        }
        map.set(oid, id);
    }

    /** Resolves a catalog oid to the snapshot object it names, if it is in this namespace. */
    private byOid(oid: number | null): ObjectId | undefined {
        if (oid == null) {
            return undefined;
        }
        return this.relByOid.get(oid) ?? this.procByOid.get(oid)
                ?? this.typeByOid.get(oid) ?? this.langByOid.get(oid)
                ?? this.tmplByOid.get(oid) ?? this.prsByOid.get(oid)
                ?? this.dictByOid.get(oid) ?? this.cfgByOid.get(oid)
                ?? this.oprByOid.get(oid) ?? this.opcByOid.get(oid)
                ?? this.opfByOid.get(oid);
    }

    private depOid(from: ObjectId | null | undefined, refOid: number | null): void {
        this.dep(from, this.byOid(refOid));
    }

    /**
     * Propagates a covered column's dependency up to its parent table so
     * that a `CREATE TABLE` change still orders after the referenced
     * object. Skipped when the referenced object already depends on the
     * table — a serial-owned sequence, for example — because a mutual
     * edge would read as a dependency cycle.
     */
    private depTableForCoveredChild(table: ObjectId, ref: ObjectId | null | undefined): void {
        if (ref != null && !(this.deps.get(ref)?.has(table) ?? false)) {
            this.dep(table, ref);
        }
    }

    // ---- loads ------------------------------------------------------

    private extMember(oid: number): boolean {
        return this.extOwned.has(oid);
    }

    async loadExtensionOwned(conn: SqlConnection): Promise<void> {
        // relations, routines and types dominate the member count (a
        // PostGIS install owns thousands), so only those in this
        // namespace are read; other classes are few and read whole
        for (const row of await conn.query<Row>(
                "SELECT d.objid FROM pg_depend d"
                        + " WHERE d.refclassid = 'pg_extension'::regclass AND d.deptype = 'e'"
                        + " AND CASE d.classid"
                        + " WHEN 'pg_class'::regclass THEN EXISTS (SELECT 1 FROM pg_class o"
                        + "   WHERE o.oid = d.objid AND o.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = $1))"
                        + " WHEN 'pg_proc'::regclass THEN EXISTS (SELECT 1 FROM pg_proc o"
                        + "   WHERE o.oid = d.objid AND o.pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = $2))"
                        + " WHEN 'pg_type'::regclass THEN EXISTS (SELECT 1 FROM pg_type o"
                        + "   WHERE o.oid = d.objid AND o.typnamespace = (SELECT oid FROM pg_namespace WHERE nspname = $3))"
                        + " ELSE true END",
                [this.ns, this.ns, this.ns])) {
            this.extOwned.add(num(row.objid));
        }
    }

    /**
     * A range type's generated family: the `typtype 'm'`
     * multirange, the constructor routines and the
     * `range→multirange` cast, all internally bound to the range
     * (`pg_depend deptype 'i'`). They fold into the managed
     * `TYPE` like a table's rowtype — never objects of their own;
     * their oids remap to the range so dependents and annotations on
     * the family still resolve. Runs before every other type and
     * routine loader so the oid maps are populated early.
     */
    async loadAutoTypeFamily(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT t.oid, t.typname, r.rngmultitypid
                 FROM pg_range r
                 JOIN pg_type t ON t.oid = r.rngtypid
                 JOIN pg_namespace n ON n.oid = t.typnamespace
                 WHERE n.nspname = $1`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const id = new ObjectId(ObjectKind.TYPE, str(row.typname));
            this.typeByOid.set(oid, id);
            const mr = num(row.rngmultitypid);
            this.typeByOid.set(mr, id);
            this.foldedMembers.add(mr);
        }
        for (const row of await conn.query<Row>(
                `SELECT d.objid, coalesce(rg.rngtypid, d.refobjid) AS rng
                 FROM pg_depend d
                 JOIN pg_type t ON t.oid = d.refobjid
                 JOIN pg_namespace n ON n.oid = t.typnamespace
                 LEFT JOIN pg_range rg ON rg.rngmultitypid = d.refobjid
                 WHERE n.nspname = $1 AND t.typtype IN ('r','m')
                   AND d.classid = 'pg_proc'::regclass
                   AND d.refclassid = 'pg_type'::regclass
                   AND d.deptype = 'i'`, [this.ns])) {
            this.autoProcs.set(num(row.objid), num(row.rng));
            this.foldedMembers.add(num(row.objid));
        }
        for (const row of await conn.query<Row>(
                `SELECT d.objid, coalesce(rg.rngtypid, d.refobjid) AS rng
                 FROM pg_depend d
                 JOIN pg_type t ON t.oid = d.refobjid
                 JOIN pg_namespace n ON n.oid = t.typnamespace
                 LEFT JOIN pg_range rg ON rg.rngmultitypid = d.refobjid
                 WHERE n.nspname = $1 AND t.typtype IN ('r','m')
                   AND d.classid = 'pg_cast'::regclass
                   AND d.refclassid = 'pg_type'::regclass
                   AND d.deptype = 'i'`, [this.ns])) {
            this.autoCasts.set(num(row.objid), num(row.rng));
            this.foldedMembers.add(num(row.objid));
        }
    }

    async loadRelations(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT c.oid, c.relname, c.relkind, c.relpersistence,
                        c.reloptions::text AS options, c.relreplident,
                        c.relrowsecurity, c.relforcerowsecurity,
                        pg_get_partkeydef(c.oid) AS partkey,
                        pg_get_expr(c.relpartbound, c.oid) AS partbound,
                        (SELECT p.relname FROM pg_inherits i
                           JOIN pg_class p ON p.oid = i.inhparent
                          WHERE i.inhrelid = c.oid AND p.relkind = 'p'
                          LIMIT 1) AS pparent,
                        (SELECT string_agg(p.relkind, '') FROM pg_inherits i
                           JOIN pg_class p ON p.oid = i.inhparent
                          WHERE i.inhrelid = c.oid) AS parentkinds,
                        (SELECT ic.relname FROM pg_index ix JOIN pg_class ic ON ic.oid = ix.indexrelid
                          WHERE ix.indrelid = c.oid AND ix.indisreplident) AS replindex,
                        (SELECT t.oid FROM pg_type t
                          WHERE t.typrelid = c.oid) AS typeoid,
                        s.srvname AS ftserver, f.ftoptions::text AS ftoptions
                 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 LEFT JOIN pg_foreign_table f ON f.ftrelid = c.oid
                 LEFT JOIN pg_foreign_server s ON s.oid = f.ftserver
                 WHERE n.nspname = $1 AND c.relkind IN ('r','v','m','f','p','c','t','I','S')
                 ORDER BY c.oid`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const name = str(row.relname);
            const kind = str(row.relkind);
            const persistence = str(row.relpersistence);
            const options = reloptions(strOrNull(row.options));
            const parentkinds = nullToEmpty(strOrNull(row.parentkinds));
            const inherited = parentkinds.length > 0;
            // a plain table whose parents are also plain tables is a
            // classic-inheritance child — not faithfully creatable
            // as a standalone TABLE, so it stays OTHER. A partition
            // child ('r' under a 'p' parent) is managed as PARTITION
            // parented on the partitioned table; the 'p' parent
            // itself is a TABLE whose canonical carries the key.
            let id: ObjectId | null;
            switch (kind) {
                case "r":
                    id = !inherited ? new ObjectId(ObjectKind.TABLE, name)
                            : parentkinds.includes("p")
                                    ? new ObjectId(ObjectKind.PARTITION, name,
                                            strOrNull(row.pparent)!)
                                    : new ObjectId(ObjectKind.OTHER,
                                            "inherited:" + name);
                    break;
                case "v":
                    id = new ObjectId(ObjectKind.VIEW, name);
                    break;
                case "S":
                    id = new ObjectId(ObjectKind.SEQUENCE, name);
                    break;
                // managed: canonical definition lands in loadViews
                case "m":
                    id = new ObjectId(ObjectKind.MATERIALIZED_VIEW, name);
                    break;
                // a 'p' under a 'p' parent is a partition that is
                // itself partitioned — a PARTITION whose create
                // carries both the bound and the sub-partition key
                case "p":
                    id = !inherited ? new ObjectId(ObjectKind.TABLE, name)
                            : parentkinds.includes("p")
                                    ? new ObjectId(ObjectKind.PARTITION, name,
                                            strOrNull(row.pparent)!)
                                    : new ObjectId(ObjectKind.OTHER,
                                            "inherited:" + name);
                    break;
                case "f":
                    id = new ObjectId(ObjectKind.FOREIGN_TABLE, name);
                    break;
                // a documented category prefix per relkind (3.3):
                // tolerating one non-managed relation kind must
                // not exempt the others
                case "I":
                    id = new ObjectId(ObjectKind.OTHER, "partindex:" + name);
                    break;
                // a standalone composite's 'c' row is the type's
                // backing — the managed TYPE object claims it in
                // loadComposites
                case "c":
                    id = null;
                    break;
                case "t":
                    id = new ObjectId(ObjectKind.OTHER, "toast:" + name);
                    break;
                default:
                    id = new ObjectId(ObjectKind.OTHER, "relation:" + name);
                    break;
            }
            if (id === null) {
                continue;
            }
            this.relByOid.set(oid, id);
            // the companion composite rowtype — a column typed as
            // the relation resolves the pg_type oid, so it maps to
            // the same object (OTHER rowtypes included: an
            // inherited child's rowtype is equally unsupported)
            const typeoid = num(row.typeoid);
            if (typeoid !== 0) {
                if (!this.typeByOid.has(typeoid)) {
                    this.typeByOid.set(typeoid, id);
                }
                if (id.kind === ObjectKind.OTHER) {
                    this.otherOid("pg_type", typeoid, id);
                }
            }
            if (id.kind === ObjectKind.TABLE) {
                const replident = strOrNull(row.relreplident);
                const replindex = strOrNull(row.replindex);
                const partkey = strOrNull(row.partkey);
                const attrs = new Map<string, string>();
                attrs.set(TABLE_OPTIONS, options.join(", "));
                attrs.set(REPLIDENT, replident!);
                if (partkey !== null) {
                    attrs.set(PARTKEY, partkey);
                }
                this.define(id, "relkind=" + kind + ";relpersistence=" + persistence
                        + ";options=" + options
                        + ";replident=" + replident
                        + (replindex !== null ? ";replindex=" + replindex : "")
                        + ";rls=" + bool(row.relrowsecurity)
                        + ";forcerls=" + bool(row.relforcerowsecurity)
                        + (partkey !== null ? ";partkey=" + partkey : ""),
                        attrs);
            } else if (id.kind === ObjectKind.PARTITION) {
                const bound = strOrNull(row.partbound);
                const partkey = strOrNull(row.partkey);
                const qName = this.tableRef(name);
                this.define(id, "bound=" + bound
                                + (partkey !== null
                                        ? ";partkey=" + partkey : ""),
                        new Map([
                                [CREATE_SQL, "CREATE TABLE " + qName
                                        + " PARTITION OF "
                                        + this.tableRef(id.parent!)
                                        + " " + bound
                                        + (partkey !== null
                                                ? " PARTITION BY "
                                                        + partkey
                                                : "")],
                                [DROP_SQL, "DROP TABLE " + qName]]));
            } else if (id.kind === ObjectKind.FOREIGN_TABLE) {
                // the server name is a cluster object reference
                // (4.1): it lives inside the canonical so drift on
                // it reconciles the table, never the server
                const server = strOrNull(row.ftserver);
                const ftoptions = reloptions(strOrNull(row.ftoptions));
                const optionSql = ftoptions
                        .map(o => {
                            const eq = o.indexOf("=");
                            return o.substring(0, eq) + " "
                                    + sqlLiteral(o.substring(eq + 1));
                        })
                        .join(", ");
                const attrs = new Map<string, string>();
                attrs.set(FT_SERVER, server!);
                attrs.set(FT_OPTIONS, optionSql);
                attrs.set(DROP_SQL, "DROP FOREIGN TABLE " + this.tableRef(name));
                this.define(id, "server=" + server
                        + ";ftoptions=" + ftoptions.join(", ")
                        + ";options=" + options,
                        attrs);
            } else if (id.kind === ObjectKind.OTHER) {
                this.define(id, "relkind=" + kind + (inherited ? ";inherited" : ""),
                        new Map());
            }
        }
    }

    async loadColumns(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT c.relname AS parent, c.oid AS reloid, a.attnum,
                        a.attname, format_type(a.atttypid, a.atttypmod) AS typ,
                        a.attgenerated, a.attcollation, t.typcollation, a.attidentity,
                        a.attstorage, t.typstorage, a.attcompression,
                        co.collname,
                        (a.attnotnull OR EXISTS (
                            SELECT 1 FROM pg_constraint cn
                            WHERE cn.conrelid = a.attrelid AND cn.contype = 'n'
                              AND a.attnum = ANY(cn.conkey))) AS notnull,
                        pg_get_expr(d.adbin, d.adrelid) AS defexpr,
                        format_type(ids.seqtypid, NULL) AS idtype,
                        ids.seqincrement, ids.seqmin, ids.seqmax,
                        ids.seqstart, ids.seqcache, ids.seqcycle
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid AND c.relkind IN ('r', 'p', 'f')
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                 JOIN pg_type t ON t.oid = a.atttypid
                 LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                 LEFT JOIN pg_collation co ON co.oid = a.attcollation
                 LEFT JOIN LATERAL (
                     SELECT ps.* FROM pg_depend dep
                     JOIN pg_class sq ON sq.oid = dep.objid AND sq.relkind = 'S'
                     JOIN pg_sequence ps ON ps.seqrelid = sq.oid
                     WHERE dep.classid = 'pg_class'::regclass
                       AND dep.refclassid = 'pg_class'::regclass
                       AND dep.refobjid = a.attrelid
                       AND dep.refobjsubid = a.attnum
                       AND dep.deptype = 'i'
                 ) ids ON true
                 WHERE n.nspname = $1 AND a.attnum > 0 AND NOT a.attisdropped
                 ORDER BY c.oid, a.attnum`, [this.ns])) {
            if (this.extMember(num(row.reloid))) {
                continue;
            }
            const parent = str(row.parent);
            const name = str(row.attname);
            const type = str(row.typ);
            const notnull = bool(row.notnull);
            const generated = nullToEmpty(strOrNull(row.attgenerated));
            const identity = nullToEmpty(strOrNull(row.attidentity));
            const defexpr = strOrNull(row.defexpr);
            const collname = strOrNull(row.collname);
            const customCollation = num(row.attcollation) !== 0
                    && num(row.attcollation) !== num(row.typcollation);
            const storage = strOrNull(row.attstorage);
            const compression = nullToEmpty(strOrNull(row.attcompression));

            let canonical = "type=" + type + ";notnull=" + notnull;
            let sql = PgIdentifiers.quote(name) + " " + type;
            // storage/compression precede COLLATE in the column-def
            // grammar; emitted only when they deviate from the
            // type default (like a custom collation)
            if (storage !== strOrNull(row.typstorage)) {
                sql += " STORAGE " + (storage === "e" ? "EXTERNAL"
                        : storage === "m" ? "MAIN"
                        : storage === "p" ? "PLAIN" : "EXTENDED");
            }
            if (compression.length > 0) {
                sql += " COMPRESSION " + (compression === "l" ? "lz4" : "pglz");
            }
            if (customCollation && collname !== null) {
                canonical += ";collation=" + collname;
                sql += " COLLATE " + PgIdentifiers.quote(collname);
            }
            const attrs = new Map<string, string>();
            attrs.set(ATTNUM, str(row.attnum));
            attrs.set(NOTNULL, String(notnull));
            if (generated.length > 0) {
                canonical += ";generated=" + generated + ":" + defexpr;
                sql += " GENERATED ALWAYS AS (" + defexpr + ") "
                        + (generated === "v" ? "VIRTUAL" : "STORED");
            } else if (identity.length > 0) {
                const sqlOptions = identitySqlOptions(row);
                canonical += ";identity=" + identity
                        + ";identityOptions=" + identityCanonical(row);
                sql += " GENERATED "
                        + (identity === "a" ? "ALWAYS" : "BY DEFAULT")
                        + " AS IDENTITY";
                if (sqlOptions.length > 0) {
                    sql += " (" + sqlOptions + ")";
                }
            } else if (defexpr !== null) {
                canonical += ";default=" + defexpr;
                sql += " DEFAULT " + defexpr;
                attrs.set(DEFAULT, defexpr);
            }
            if (notnull) {
                sql += " NOT NULL";
            }
            canonical += ";storage=" + storage + ";compression=" + compression;
            attrs.set(COLUMN_SQL, sql);
            this.define(new ObjectId(ObjectKind.COLUMN, name, parent),
                    canonical, attrs);
        }
    }

    async loadConstraints(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT c.oid, c.conname, c.contype, rel.relname AS parent,
                        pg_get_constraintdef(c.oid) AS def,
                        ref.oid AS refoid, c.conindid,
                        EXISTS (SELECT 1 FROM pg_index ix
                                 WHERE ix.indexrelid = c.conindid AND ix.indisreplident
                                   AND c.contype IN ('p','u')) AS replident
                 FROM pg_constraint c
                 JOIN pg_class rel ON rel.oid = c.conrelid
                 JOIN pg_namespace n ON n.oid = rel.relnamespace
                 LEFT JOIN pg_class ref ON ref.oid = c.confrelid
                 WHERE n.nspname = $1 AND c.contype IN ('p','u','f','c','x')
                   AND c.conislocal
                 ORDER BY c.oid`, [this.ns])) {
            if (this.extMember(num(row.oid))) {
                continue;
            }
            const contype = str(row.contype);
            const kind = contype === "p" ? ObjectKind.PRIMARY_KEY
                    : contype === "u" ? ObjectKind.UNIQUE
                    : contype === "f" ? ObjectKind.FOREIGN_KEY
                    : contype === "c" ? ObjectKind.CHECK
                    : contype === "x" ? ObjectKind.EXCLUSION
                    : ObjectKind.OTHER;
            const name = str(row.conname);
            const parent = str(row.parent);
            const def = strOrNull(row.def);
            const id = kind === ObjectKind.OTHER
                    ? new ObjectId(kind, "constraint:" + parent + "." + name)
                    : new ObjectId(kind, name, parent);
            this.conByOid.set(num(row.oid), id);
            const conindid = num(row.conindid);
            // conindid is the backing index for p/u/x only; on an
            // FK it is the referenced index on the other table.
            if (conindid !== 0 && kind !== ObjectKind.FOREIGN_KEY
                    && kind !== ObjectKind.CHECK) {
                this.conByIndexOid.set(conindid, id);
            }
            const qParent = this.tableRef(parent);
            const qName = PgIdentifiers.quote(name);
            const attrs = new Map<string, string>();
            attrs.set(CREATE_SQL, "ALTER TABLE " + qParent
                    + " ADD CONSTRAINT " + qName + " " + def);
            attrs.set(DROP_SQL, "ALTER TABLE " + qParent
                    + " DROP CONSTRAINT " + qName);
            if (bool(row.replident)) {
                attrs.set(POST_SQL, "ALTER TABLE " + qParent
                        + " REPLICA IDENTITY USING INDEX " + qName);
            }
            this.define(id, def!, attrs);
            const refoid = numOrNull(row.refoid);
            if (refoid !== null) {
                this.depOid(id, refoid);
            }
        }
    }

    async loadIndexes(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT i.indexrelid AS oid, ic.relname, tc.relname AS parent,
                        pg_get_indexdef(i.indexrelid) AS def, i.indisreplident AS replident
                 FROM pg_index i
                 JOIN pg_class ic ON ic.oid = i.indexrelid
                 JOIN pg_class tc ON tc.oid = i.indrelid
                 JOIN pg_namespace n ON n.oid = ic.relnamespace
                 WHERE n.nspname = $1 AND ic.relkind = 'i'
                   AND NOT EXISTS (SELECT 1 FROM pg_constraint co
                                   WHERE co.conindid = i.indexrelid
                                     AND co.contype IN ('p','u','x'))
                   AND NOT EXISTS (SELECT 1 FROM pg_inherits inh WHERE inh.inhrelid = i.indexrelid)
                 ORDER BY i.indexrelid`, [this.ns])) {
            if (this.extMember(num(row.oid))) {
                continue;
            }
            const name = str(row.relname);
            const parent = str(row.parent);
            const def = strOrNull(row.def);
            const id = new ObjectId(ObjectKind.INDEX, name, parent);
            this.relByOid.set(num(row.oid), id);
            const attrs = new Map<string, string>();
            attrs.set(CREATE_SQL, def!);
            attrs.set(DROP_SQL, "DROP INDEX " + this.qNs + "." + PgIdentifiers.quote(name));
            if (bool(row.replident)) {
                attrs.set(POST_SQL, "ALTER TABLE " + this.tableRef(parent)
                        + " REPLICA IDENTITY USING INDEX " + PgIdentifiers.quote(name));
            }
            this.define(id, def!, attrs);
        }
    }

    async loadViews(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT c.oid, c.relname, c.relkind, c.relpersistence, c.relispopulated,
                        pg_get_viewdef(c.oid, true) AS def,
                        c.reloptions::text AS options,
                        (SELECT string_agg(a.attname || ' ' || format_type(a.atttypid, a.atttypmod),
                                           ', ' ORDER BY a.attnum)
                           FROM pg_attribute a
                          WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped) AS cols
                 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = $1 AND c.relkind IN ('v','m')
                 ORDER BY c.oid`, [this.ns])) {
            if (this.extMember(num(row.oid))) {
                continue;
            }
            const name = str(row.relname);
            const def = strOrNull(row.def);
            const cols = nullToEmpty(strOrNull(row.cols));
            const options = reloptions(strOrNull(row.options));
            const qName = this.qNs + "." + PgIdentifiers.quote(name);
            if (str(row.relkind) === "m") {
                const populated = bool(row.relispopulated);
                const attrs = new Map<string, string>();
                attrs.set(CREATE_SQL, "CREATE "
                        + (str(row.relpersistence) === "u" ? "UNLOGGED " : "")
                        + "MATERIALIZED VIEW " + qName
                        + (options.length === 0 ? "" : " WITH (" + options.join(",") + ")")
                        + " AS " + def);
                attrs.set(DROP_SQL, "DROP MATERIALIZED VIEW " + qName);
                attrs.set(POPULATED, String(populated));
                this.define(new ObjectId(ObjectKind.MATERIALIZED_VIEW, name),
                        "viewdef=" + def + ";cols=" + cols + ";options=" + options
                                + ";relpersistence=" + str(row.relpersistence)
                                + ";populated=" + populated,
                        attrs);
            } else {
                const id = new ObjectId(ObjectKind.VIEW, name);
                const create = "CREATE VIEW " + qName
                        + (options.length === 0 ? "" : " WITH (" + options.join(",") + ")")
                        + " AS " + def;
                this.define(id, "viewdef=" + def + ";cols=" + cols + ";options=" + options,
                        new Map([[CREATE_SQL, create], [DROP_SQL, "DROP VIEW " + qName]]));
            }
        }
    }

    async loadSequences(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT c.oid, c.relname, c.relpersistence,
                        format_type(s.seqtypid, NULL) AS stype,
                        s.seqincrement, s.seqmin, s.seqmax, s.seqstart, s.seqcache, s.seqcycle
                 FROM pg_class c
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                 JOIN pg_sequence s ON s.seqrelid = c.oid
                 WHERE n.nspname = $1 AND c.relkind = 'S'
                   AND NOT EXISTS (
                       SELECT 1 FROM pg_depend d
                       WHERE d.classid = 'pg_class'::regclass
                         AND d.refclassid = 'pg_class'::regclass
                         AND d.objid = c.oid AND d.deptype = 'i')
                 ORDER BY c.oid`, [this.ns])) {
            if (this.extMember(num(row.oid))) {
                continue;
            }
            const name = str(row.relname);
            const id = new ObjectId(ObjectKind.SEQUENCE, name);
            const stype = str(row.stype);
            const inc = str(row.seqincrement);
            const min = str(row.seqmin);
            const max = str(row.seqmax);
            const start = str(row.seqstart);
            const cache = str(row.seqcache);
            const cycle = bool(row.seqcycle);
            const options = "AS " + stype + " INCREMENT BY " + inc
                    + " MINVALUE " + min + " MAXVALUE " + max
                    + " START " + start + " CACHE " + cache
                    + (cycle ? " CYCLE" : " NO CYCLE");
            const persistence = str(row.relpersistence);
            const qSeq = this.qNs + "." + PgIdentifiers.quote(name);
            const attrs = new Map<string, string>();
            attrs.set(OPTIONS_SQL, options);
            attrs.set(CREATE_SQL, "CREATE "
                    + (persistence === "u" ? "UNLOGGED " : "")
                    + "SEQUENCE " + qSeq + " " + options);
            attrs.set(DROP_SQL, "DROP SEQUENCE " + qSeq);
            this.seqOptions.set(id, "type=" + stype + ";increment=" + inc + ";min=" + min
                    + ";max=" + max + ";start=" + start + ";cache=" + cache
                    + ";cycle=" + cycle + ";relpersistence=" + persistence);
            // the canonical needs ownership, resolved by the
            // second query — attrs accumulate here, define() runs
            // (and normalizes them) once below
            for (const [k, v] of attrs) {
                this.obj(id).attrs.set(k, v);
            }
        }
        // serial-style ownership (deptype 'a')
        for (const row of await conn.query<Row>(
                `SELECT dep.objid AS seq_oid, dep.refobjid AS tbl_oid,
                        rc.relname AS tbl, ra.attname AS col,
                        rn.nspname AS tblns
                 FROM pg_depend dep
                 JOIN pg_class sq ON sq.oid = dep.objid AND sq.relkind = 'S'
                 JOIN pg_namespace sn ON sn.oid = sq.relnamespace
                 JOIN pg_class rc ON rc.oid = dep.refobjid
                 JOIN pg_namespace rn ON rn.oid = rc.relnamespace
                 JOIN pg_attribute ra ON ra.attrelid = dep.refobjid AND ra.attnum = dep.refobjsubid
                 WHERE dep.classid = 'pg_class'::regclass
                   AND dep.refclassid = 'pg_class'::regclass
                   AND dep.deptype = 'a' AND sn.nspname = $1`, [this.ns])) {
            const seq = this.relByOid.get(num(row.seq_oid));
            if (seq === undefined) {
                continue;
            }
            const tbl = str(row.tbl);
            const col = str(row.col);
            const sameNs = this.ns === str(row.tblns);
            const ownedBy = (sameNs ? "${namespace}" : PgIdentifiers.quote(str(row.tblns)))
                    + "." + PgIdentifiers.quote(tbl) + "." + PgIdentifiers.quote(col);
            this.seqOwnedBy.set(seq, ownedBy);
            if (sameNs) {
                // the owner may be a PARTITION or an
                // inherited: OTHER, not always a TABLE
                this.dep(seq, this.relByOid.get(num(row.tbl_oid))
                        ?? new ObjectId(ObjectKind.TABLE, tbl));
            }
        }
        // finish sequence canonicals now that ownership is known
        for (const [id, canonical] of this.seqOptions) {
            const ownedBy = this.seqOwnedBy.get(id);
            let def = canonical;
            if (ownedBy !== undefined) {
                def += ";owned_by=" + ownedBy;
            }
            const objAttrs = this.objects.get(id)!.attrs;
            const attrs = new Map<string, string>();
            attrs.set(OPTIONS_SQL, objAttrs.get(OPTIONS_SQL)!);
            attrs.set(CREATE_SQL, objAttrs.get(CREATE_SQL)!);
            attrs.set(DROP_SQL, objAttrs.get(DROP_SQL)!);
            if (ownedBy !== undefined) {
                attrs.set(OWNED_SQL, "ALTER SEQUENCE " + this.seqRef(id) + " OWNED BY " + ownedBy);
            }
            this.define(id, def, attrs);
        }
    }

    async loadRoutines(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT p.oid, p.proname, p.prokind,
                        coalesce((SELECT string_agg(format_type(a, NULL), ', '
                                                  ORDER BY x.i)
                                    FROM unnest(string_to_array(p.proargtypes::text, ' ')::oid[])
                                         WITH ORDINALITY AS x(a, i)), '') AS sigargs,
                        (CASE WHEN p.proretset THEN 'SETOF ' ELSE '' END)
                            || format_type(p.prorettype, NULL) AS rettype,
                        pg_get_functiondef(p.oid) AS def
                 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = $1 AND p.prokind IN ('f','p','w')
                 ORDER BY p.oid`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            if (this.autoProcs.has(oid)) {
                // a range/multirange constructor folds into its
                // range type — dependents resolve its oid to the
                // TYPE object
                this.procByOid.set(oid, this.typeByOid.get(this.autoProcs.get(oid)!)!);
                continue;
            }
            const proname = str(row.proname);
            const prokind = str(row.prokind);
            const sigargs = str(row.sigargs);
            const rettype = str(row.rettype);
            const def = strOrNull(row.def);
            const id = new ObjectId(ObjectKind.ROUTINE,
                    PgSql.normalize(proname + "(" + sigargs + ")", this.ns));
            this.procByOid.set(oid, id);
            const qName = this.qNs + "." + PgIdentifiers.quote(proname);
            const dropWord = prokind === "p" ? "PROCEDURE" : "FUNCTION";
            this.define(id, def!, new Map([
                    [CREATE_SQL, def!],
                    [DROP_SQL, "DROP " + dropWord + " " + qName + "(" + sigargs + ")"],
                    [SIG_ARGS, sigargs],
                    [PROKIND, prokind],
                    [RETURN_TYPE, rettype]]));
        }
    }

    /**
     * Aggregates (`prokind 'a'`): `pg_get_functiondef`
     * refuses them, so the canonical definition is composed from the
     * `pg_aggregate` row — every option that can appear between
     * the parentheses, in a fixed order, with `null`/default
     * fields omitted. The same option list renders `CREATE
     * AGGREGATE`; drift recreates (`DROP AGGREGATE` +
     * `CREATE AGGREGATE`) because aggregates have no
     * `CREATE OR REPLACE` form. Ordered-set and hypothetical-set
     * aggregates ride the same path — `ORDER BY` inside the
     * signature and the `HYPOTHETICAL` flag carry their
     * difference.
     */
    async loadAggregates(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT p.oid, p.proname,
                        pg_get_function_identity_arguments(p.oid) AS idargs,
                        pg_get_function_arguments(p.oid) AS args,
                        a.aggkind,
                        a.aggtransfn::regprocedure::text AS sfunc,
                        CASE WHEN a.aggtranstype = 0 THEN 'internal'
                             ELSE format_type(a.aggtranstype, NULL) END AS stype,
                        NULLIF(a.aggtransspace, 0) AS sspace,
                        NULLIF(a.aggfinalfn, 0)::regprocedure::text AS ffunc,
                        a.aggfinalextra, a.aggfinalmodify,
                        NULLIF(a.aggcombinefn, 0)::regprocedure::text AS combine,
                        NULLIF(a.aggserialfn, 0)::regprocedure::text AS serial,
                        NULLIF(a.aggdeserialfn, 0)::regprocedure::text AS deserial,
                        a.agginitval,
                        NULLIF(a.aggmtransfn, 0)::regprocedure::text AS msfunc,
                        NULLIF(a.aggminvtransfn, 0)::regprocedure::text AS minv,
                        CASE WHEN a.aggmtransfn::text = '-' THEN NULL
                             WHEN a.aggmtranstype = 0 THEN 'internal'
                             ELSE format_type(a.aggmtranstype, NULL) END AS mstype,
                        NULLIF(a.aggmtransspace, 0) AS msspace,
                        NULLIF(a.aggmfinalfn, 0)::regprocedure::text AS mffunc,
                        a.aggmfinalextra, a.aggmfinalmodify,
                        a.aggminitval,
                        NULLIF(a.aggsortop, 0)::regoper::text AS sortop
                 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 JOIN pg_aggregate a ON a.aggfnoid = p.oid
                 WHERE n.nspname = $1 AND p.prokind = 'a'
                 ORDER BY p.oid`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid) || this.autoProcs.has(oid)) {
                continue;
            }
            const proname = str(row.proname);
            const idargs = str(row.idargs);
            const options: string[] = [];
            const kind = str(row.aggkind);
            if (kind === "h") {
                options.push("HYPOTHETICAL");
            }
            options.push("SFUNC = " + str(row.sfunc));
            options.push("STYPE = " + str(row.stype));
            aggOpt(options, "SSPACE", strOrNull(row.sspace));
            aggOpt(options, "FINALFUNC", strOrNull(row.ffunc));
            if (bool(row.aggfinalextra)) {
                options.push("FINALFUNC_EXTRA");
            }
            aggModify(options, "FINALFUNC_MODIFY", strOrNull(row.aggfinalmodify));
            aggOpt(options, "COMBINEFUNC", strOrNull(row.combine));
            aggOpt(options, "SERIALFUNC", strOrNull(row.serial));
            aggOpt(options, "DESERIALFUNC", strOrNull(row.deserial));
            if (row.agginitval != null) {
                options.push("INITCOND = '"
                        + str(row.agginitval).replaceAll("'", "''")
                        + "'");
            }
            aggOpt(options, "MSFUNC", strOrNull(row.msfunc));
            aggOpt(options, "MINVFUNC", strOrNull(row.minv));
            aggOpt(options, "MSTYPE", strOrNull(row.mstype));
            aggOpt(options, "MSSPACE", strOrNull(row.msspace));
            aggOpt(options, "MFINALFUNC", strOrNull(row.mffunc));
            if (bool(row.aggmfinalextra)) {
                options.push("MFINALFUNC_EXTRA");
            }
            aggModify(options, "MFINALFUNC_MODIFY", strOrNull(row.aggmfinalmodify));
            if (row.aggminitval != null) {
                options.push("MINITCOND = '"
                        + str(row.aggminitval).replaceAll("'", "''")
                        + "'");
            }
            if (row.sortop != null) {
                options.push("SORTOP = OPERATOR(" + str(row.sortop) + ")");
            }
            const opts = options.join(", ");
            const id = new ObjectId(ObjectKind.AGGREGATE,
                    PgSql.normalize(
                            proname + "(" + idargs + ")", this.ns));
            this.procByOid.set(oid, id);
            const qName = this.qNs + "." + PgIdentifiers.quote(proname);
            this.define(id, "prokind=a;kind=" + kind + ";" + opts,
                    new Map([
                            [CREATE_SQL, "CREATE AGGREGATE " + qName
                                    + "(" + str(row.args) + ")"
                                    + " (" + opts + ")"],
                            [DROP_SQL, "DROP AGGREGATE " + qName
                                    + "(" + idargs + ")"],
                            [SIG_ARGS, idargs],
                            [PROKIND, "a"]]));
        }
    }

    async loadTriggers(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT t.oid, t.tgname, c.relname AS parent, t.tgenabled,
                        pg_get_triggerdef(t.oid, true) AS def, t.tgfoid
                 FROM pg_trigger t
                 JOIN pg_class c ON c.oid = t.tgrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = $1 AND NOT t.tgisinternal AND t.tgparentid = 0
                 ORDER BY t.oid`, [this.ns])) {
            if (this.extMember(num(row.oid))) {
                continue;
            }
            const name = str(row.tgname);
            const parent = str(row.parent);
            const enabled = str(row.tgenabled);
            const def = strOrNull(row.def);
            const id = new ObjectId(ObjectKind.TRIGGER, name, parent);
            this.tgByOid.set(num(row.oid), id);
            const qParent = this.tableRef(parent);
            const qName = PgIdentifiers.quote(name);
            const attrs = new Map<string, string>();
            attrs.set(CREATE_SQL, def!);
            attrs.set(DROP_SQL, "DROP TRIGGER " + qName + " ON " + qParent);
            if (enabled !== "O") {
                const verb = enabled === "D" ? "DISABLE"
                        : enabled === "R" ? "ENABLE REPLICA" : "ENABLE ALWAYS";
                attrs.set(POST_SQL, "ALTER TABLE " + qParent + " " + verb
                        + " TRIGGER " + qName);
            }
            this.define(id, def + ";enabled=" + enabled, attrs);
            this.depOid(id, num(row.tgfoid));
        }
    }

    /** Enum types: the canonical definition is the ordered label list;
     *  the `typeByOid` entry resolves column and table dependency
     *  edges to the managed `TYPE` object. */
    async loadEnums(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT t.oid, t.typname,
                        (SELECT array_agg(e.enumlabel ORDER BY e.enumsortorder, e.oid)
                           FROM pg_enum e WHERE e.enumtypid = t.oid) AS labels
                 FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                 WHERE n.nspname = $1 AND t.typtype = 'e'
                 ORDER BY t.oid`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const name = str(row.typname);
            const labels = textArray(row.labels);
            const csv = labels.map(sqlLiteral).join(",");
            const qName = this.qNs + "." + PgIdentifiers.quote(name);
            const id = new ObjectId(ObjectKind.TYPE, name);
            this.define(id, "typtype=e;labels=" + csv,
                    new Map([[CREATE_SQL, "CREATE TYPE " + qName + " AS ENUM"
                                    + (csv === "" ? "" : " (" + csv + ")")],
                            [DROP_SQL, "DROP TYPE " + qName]]));
            this.typeByOid.set(oid, id);
        }
    }

    /**
     * Domains: the canonical definition is the base type (deparsed by
     * `format_type`), the nullability flag, the deparsed default
     * expression and the check constraints by name. The `typeByOid`
     * entry resolves column and table dependency edges to the managed
     * `DOMAIN` object; the base type and the checks'
     * referenced routines and types join the dependency graph in
     * `loadDependencies`.
     */
    async loadDomains(conn: SqlConnection): Promise<void> {
        const checks = new Map<number, string[]>();
        for (const row of await conn.query<Row>(
                `SELECT c.contypid AS dom, c.conname, pg_get_constraintdef(c.oid) AS def
                 FROM pg_constraint c
                 JOIN pg_type t ON t.oid = c.contypid
                 JOIN pg_namespace n ON n.oid = t.typnamespace AND n.nspname = $1
                 WHERE c.contype = 'c'
                 ORDER BY c.contypid, c.conname`, [this.ns])) {
            const dom = num(row.dom);
            const list = checks.get(dom) ?? [];
            list.push(str(row.conname) + "\u0000" + str(row.def));
            checks.set(dom, list);
        }
        for (const row of await conn.query<Row>(
                `SELECT t.oid, t.typname,
                        format_type(t.typbasetype, t.typtypmod) AS basetype,
                        t.typnotnull, pg_get_expr(t.typdefaultbin, 0) AS dflt,
                        NULLIF(t.typcollation, 0)::regcollation::text AS coll,
                        t.typbasetype
                 FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                 WHERE n.nspname = $1 AND t.typtype = 'd'
                 ORDER BY t.oid`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const name = str(row.typname);
            const basetype = str(row.basetype);
            const notnull = bool(row.typnotnull);
            const dflt = strOrNull(row.dflt);
            const collation = strOrNull(row.coll);
            const qName = this.qNs + "." + PgIdentifiers.quote(name);
            const id = new ObjectId(ObjectKind.DOMAIN, name);
            let create = "CREATE DOMAIN " + qName + " AS " + basetype;
            let canonical = "basetype=" + basetype + ";notnull=" + notnull;
            if (collation !== null) {
                canonical += ";collation=" + collation;
                create += " COLLATE " + collation;
            }
            if (dflt !== null) {
                canonical += ";default=" + dflt;
                create += " DEFAULT " + dflt;
            }
            if (notnull) {
                create += " NOT NULL";
            }
            for (const entry of checks.get(oid) ?? []) {
                const tab = entry.indexOf("\u0000");
                const cname = entry.substring(0, tab);
                const def = entry.substring(tab + 1);
                canonical += ";check." + cname + "=" + def;
                create += " CONSTRAINT " + PgIdentifiers.quote(cname)
                        + " " + def;
            }
            this.define(id, canonical,
                    new Map([[CREATE_SQL, create],
                            [DROP_SQL, "DROP DOMAIN " + qName]]));
            this.typeByOid.set(oid, id);
        }
    }

    /**
     * Standalone composite types (`CREATE TYPE n AS (a int, …)`):
     * the canonical definition is the ordered attribute list — quoted
     * name, deparsed type and non-default collation — in attnum order,
     * since field order is part of the type. The twin `relkind 'c'`
     * row is the composite's backing relation, not an object of its own;
     * `relByOid` maps it back to the type so dependency edges and
     * annotations resolve to the managed `TYPE` object.
     * A table's rowtype also carries `typtype 'c'`, but its
     * `typrelid` is an `'r'` relation — the join keeps only
     * standalone composites.
     */
    async loadComposites(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT t.oid, t.typname, t.typrelid,
                        (SELECT string_agg(quote_ident(a.attname) || ' '
                                 || format_type(a.atttypid, a.atttypmod)
                                 || CASE WHEN a.attcollation <> at.typcollation
                                         THEN ' collate '
                                              || a.attcollation::regcollation::text
                                         ELSE '' END,
                                 ', ' ORDER BY a.attnum)
                           FROM pg_attribute a
                           JOIN pg_type at ON at.oid = a.atttypid
                           WHERE a.attrelid = t.typrelid
                             AND a.attnum > 0 AND NOT a.attisdropped) AS attrs
                 FROM pg_type t
                 JOIN pg_class c ON c.oid = t.typrelid AND c.relkind = 'c'
                 JOIN pg_namespace n ON n.oid = t.typnamespace
                 WHERE n.nspname = $1 AND t.typtype = 'c'
                 ORDER BY t.oid`, [this.ns])) {
            const oid = num(row.oid);
            const relid = num(row.typrelid);
            if (this.extMember(oid) || this.extMember(relid)) {
                continue;
            }
            const name = str(row.typname);
            const attrs = nullToEmpty(strOrNull(row.attrs));
            const qName = this.qNs + "." + PgIdentifiers.quote(name);
            const id = new ObjectId(ObjectKind.TYPE, name);
            this.define(id, "typtype=c;attrs=" + attrs,
                    new Map([[CREATE_SQL, "CREATE TYPE " + qName
                                    + " AS (" + attrs + ")"],
                            [DROP_SQL, "DROP TYPE " + qName]]));
            this.typeByOid.set(oid, id);
            this.relByOid.set(relid, id);
        }
    }

    /**
     * Range types (`CREATE TYPE n AS RANGE (SUBTYPE = t, …)`):
     * the canonical definition is the `pg_range` row — subtype,
     * non-default collation, subtype operator class, canonical and
     * subtype-diff functions. The generated family (multirange,
     * constructor routines, the range→multirange cast) folds into the
     * range in `loadAutoTypeFamily`; a multirange has no
     * standalone create form, so it is never an object of its own.
     * `typeByOid` is already populated there.
     */
    async loadRanges(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT t.oid, t.typname,
                        format_type(r.rngsubtype, NULL) AS subtype,
                        NULLIF(r.rngcollation, 0)::regcollation::text AS coll,
                        -- SUBTYPE_OPCLASS takes a bare name and pg_range
                        -- always stores the resolved class, so it is
                        -- reported only when it is not the subtype's
                        -- default btree operator class
                        CASE WHEN EXISTS (
                                 SELECT 1 FROM pg_opclass d
                                 JOIN pg_opfamily f
                                   ON f.oid = d.opcfamily
                                 JOIN pg_am am
                                   ON am.oid = f.opfmethod
                                 WHERE am.amname = 'btree'
                                   AND d.opcintype = r.rngsubtype
                                   AND d.opcdefault
                                   AND d.oid = r.rngsubopc)
                             THEN NULL
                             ELSE (SELECT o.opcname FROM pg_opclass o
                                   WHERE o.oid = r.rngsubopc)
                        END AS opc,
                        NULLIF(r.rngcanonical, 0)::regproc::text AS canonical,
                        NULLIF(r.rngsubdiff, 0)::regproc::text AS diff
                 FROM pg_range r
                 JOIN pg_type t ON t.oid = r.rngtypid
                 JOIN pg_namespace n ON n.oid = t.typnamespace
                 WHERE n.nspname = $1
                 ORDER BY t.oid`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const name = str(row.typname);
            const subtype = str(row.subtype);
            const coll = strOrNull(row.coll);
            const opc = strOrNull(row.opc);
            const canonicalFn = strOrNull(row.canonical);
            const diff = strOrNull(row.diff);
            let canon = "typtype=r;subtype=" + subtype;
            let create = "CREATE TYPE " + this.qNs + "."
                    + PgIdentifiers.quote(name)
                    + " AS RANGE (SUBTYPE = " + subtype;
            if (opc !== null) {
                canon += ";opclass=" + opc;
                create += ", SUBTYPE_OPCLASS = " + opc;
            }
            if (coll !== null) {
                canon += ";collation=" + coll;
                create += ", COLLATION = " + coll;
            }
            if (canonicalFn !== null) {
                canon += ";canonical=" + canonicalFn;
                create += ", CANONICAL = " + canonicalFn;
            }
            if (diff !== null) {
                canon += ";subtype_diff=" + diff;
                create += ", SUBTYPE_DIFF = " + diff;
            }
            create += ")";
            const id = new ObjectId(ObjectKind.TYPE, name);
            this.define(id, canon,
                    new Map([[CREATE_SQL, create],
                            [DROP_SQL, "DROP TYPE " + this.qNs + "."
                                    + PgIdentifiers.quote(name)]]));
            this.typeByOid.set(oid, id);
        }
    }

    /**
     * Row-level-security policies on our tables: a `POLICY` object
     * parented on its table. The canonical definition is the command,
     * permissive flag, sorted distinct role list (`{0}` renders as
     * `PUBLIC`) and the deparsed `USING`/`WITH CHECK`
     * expressions. The parent linkage and an explicit dependency edge tie
     * the policy to its table; `loadDependencies` adds the
     * expressions' referenced routines and types.
     */
    async loadPolicies(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT po.oid, po.polname, po.polrelid, c.relname, po.polcmd,
                        po.polpermissive,
                        (SELECT array_agg(x ORDER BY x)
                           FROM (SELECT DISTINCT CASE WHEN u = 0 THEN 'PUBLIC'
                                                      ELSE r.rolname END AS x
                                   FROM unnest(po.polroles) u
                                   LEFT JOIN pg_roles r ON r.oid = u) s) AS roles,
                        pg_get_expr(po.polqual, po.polrelid) AS using,
                        pg_get_expr(po.polwithcheck, po.polrelid) AS wcheck
                 FROM pg_policy po
                 JOIN pg_class c ON c.oid = po.polrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = $1
                 ORDER BY po.oid`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const name = str(row.polname);
            const table = str(row.relname);
            const roles = textArray(row.roles)
                    .map(r => r === "PUBLIC" ? r : PgIdentifiers.quote(r));
            const using = strOrNull(row.using);
            const wcheck = strOrNull(row.wcheck);
            const qTable = this.qNs + "." + PgIdentifiers.quote(table);
            const id = new ObjectId(ObjectKind.POLICY, name, table);
            const polcmd = str(row.polcmd);
            const permissive = bool(row.polpermissive);
            let canonical = "cmd=" + polcmd
                    + ";permissive=" + permissive
                    + ";roles=" + roles.join(",");
            let create = "CREATE POLICY "
                    + PgIdentifiers.quote(name) + " ON " + qTable;
            if (!permissive) {
                create += " AS RESTRICTIVE";
            }
            const cmdWord = polcmd === "r" ? "SELECT"
                    : polcmd === "a" ? "INSERT"
                    : polcmd === "w" ? "UPDATE"
                    : polcmd === "d" ? "DELETE" : "ALL";
            create += " FOR " + cmdWord + " TO "
                    + (roles.length === 0 ? "PUBLIC" : roles.join(","));
            if (using !== null) {
                canonical += ";using=" + using;
                create += " USING (" + using + ")";
            }
            if (wcheck !== null) {
                canonical += ";check=" + wcheck;
                create += " WITH CHECK (" + wcheck + ")";
            }
            this.define(id, canonical,
                    new Map([[CREATE_SQL, create],
                            [DROP_SQL, "DROP POLICY " + PgIdentifiers.quote(name)
                                    + " ON " + qTable]]));
            this.polByOid.set(oid, id);
            this.dep(id, this.relByOid.get(num(row.polrelid)));
        }
    }

    /**
     * Extended statistics objects (pg_statistic_ext): a `STATISTICS`
     * object named by `stxname` — statistics names are unique per
     * schema, not per table, so the id is unparented; an explicit
     * `dependsOn` edge ties it to its table. The canonical
     * definition is `pg_get_statisticsobjdef`, which carries the
     * enabled statistic kinds, the column/expression list and the source
     * table. `stxstattarget` is a per-column planner property, not
     * declarable, so it stays outside the canonical (like attstattarget).
     */
    async loadStatistics(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT s.oid, s.stxname, s.stxrelid,
                        pg_get_statisticsobjdef(s.oid) AS def
                 FROM pg_statistic_ext s
                 JOIN pg_namespace n ON n.oid = s.stxnamespace AND n.nspname = $1
                 ORDER BY s.oid`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const name = str(row.stxname);
            const def = strOrNull(row.def);
            const id = new ObjectId(ObjectKind.STATISTICS, name);
            this.stxByOid.set(oid, id);
            // the unsupported-dependents union resolves edges from
            // expression statistics (function/type refs in
            // stxexprs) through this registration too
            this.otherOid("pg_statistic_ext", oid, id);
            this.define(id, def!, new Map([
                    [CREATE_SQL, def!],
                    [DROP_SQL, "DROP STATISTICS " + this.qNs + "."
                            + PgIdentifiers.quote(name)]]));
            this.dep(id, this.relByOid.get(num(row.stxrelid)));
        }
    }

    /**
     * Non-view rewrite rules (pg_rewrite rows other than `_RETURN`,
     * which is part of its view): a `RULE` object parented on its
     * relation — rule names are unique per table. The canonical
     * definition is `pg_get_ruledef` plus the enabled state
     * (`ev_enabled`, like a trigger's `tgenabled`); the
     * `pg_rewrite`-classid registration feeds the
     * unsupported-dependents union, which records the rule's edges to
     * its relation and every object the action references.
     */
    async loadRules(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT r.oid, r.rulename, c.relname AS parent, r.ev_class,
                        r.ev_enabled, pg_get_ruledef(r.oid, true) AS def
                 FROM pg_rewrite r
                 JOIN pg_class c ON c.oid = r.ev_class
                 JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = $1
                 WHERE r.rulename <> '_RETURN'
                 ORDER BY r.oid`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const name = str(row.rulename);
            const parent = str(row.parent);
            const enabled = str(row.ev_enabled);
            const def = strOrNull(row.def);
            const id = new ObjectId(ObjectKind.RULE, name, parent);
            this.ruleByOid.set(oid, id);
            this.otherOid("pg_rewrite", oid, id);
            const qName = PgIdentifiers.quote(name);
            const qParent = this.tableRef(parent);
            const attrs = new Map<string, string>();
            attrs.set(CREATE_SQL, def!);
            attrs.set(DROP_SQL, "DROP RULE " + qName + " ON " + qParent);
            if (enabled !== "O") {
                const verb = enabled === "D" ? "DISABLE"
                        : enabled === "R" ? "ENABLE REPLICA" : "ENABLE ALWAYS";
                attrs.set(POST_SQL, "ALTER TABLE " + qParent + " "
                        + verb + " RULE " + qName);
            }
            this.define(id, def + ";enabled=" + enabled, attrs);
            this.dep(id, this.relByOid.get(num(row.ev_class)));
        }
    }

    /**
     * Collations a desired script can create: libc or ICU provider and an
     * encoding either universal (`-1`) or bound to this database's
     * own encoding — that is what `CREATE COLLATION` records, and a
     * collation bound to a different encoding cannot be recreated here
     * (the database-default provider can't either — both stay
     * `OTHER`). There is no `pg_get_collationdef`, so the
     * canonical definition and the `CREATE COLLATION` fragment are
     * composed from the provider fields; `collversion` is
     * environment metadata (the libc/ICU library version at creation)
     * and deliberately excluded so the same collation does not drift
     * across hosts. `colllocale` is named `colliculocale`
     * before PostgreSQL 17 — read via `to_json` so both spellings
     * resolve.
     */
    async loadCollations(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT cl.oid, cl.collname, cl.collprovider, cl.collisdeterministic,
                        cl.collencoding, cl.collcollate, cl.collctype,
                        COALESCE(to_json(cl)->>'colllocale',
                                 to_json(cl)->>'colliculocale') AS colllocale,
                        cl.collicurules
                 FROM pg_collation cl
                 JOIN pg_namespace n ON n.oid = cl.collnamespace AND n.nspname = $1
                 WHERE cl.collprovider IN ('c', 'i')
                   AND cl.collencoding IN (-1,
                       (SELECT encoding FROM pg_database
                        WHERE datname = current_database()))
                 ORDER BY cl.oid`, [this.ns])) {
            if (this.extMember(num(row.oid))) {
                continue;
            }
            const name = str(row.collname);
            const icu = str(row.collprovider) === "i";
            const deterministic = bool(row.collisdeterministic);
            const collate = str(row.collcollate);
            const ctype = str(row.collctype);
            const locale = str(row.colllocale);
            const rules = strOrNull(row.collicurules);
            let canonical: string;
            let options: string;
            if (icu) {
                options = "provider = icu, locale = " + sqlLiteral(locale)
                        + (rules === null ? "" : ", rules = " + sqlLiteral(rules))
                        + (deterministic ? "" : ", deterministic = false");
                canonical = "provider=icu;locale=" + locale
                        + (rules === null ? "" : ";rules=" + rules)
                        + (deterministic ? "" : ";deterministic=false")
                        + ";encoding=" + num(row.collencoding);
            } else {
                options = collate === ctype
                        ? "locale = " + sqlLiteral(collate)
                        : "lc_collate = " + sqlLiteral(collate)
                                + ", lc_ctype = " + sqlLiteral(ctype);
                canonical = "provider=libc;collate=" + collate
                        + ";ctype=" + ctype
                        + ";encoding=" + num(row.collencoding);
            }
            const id = new ObjectId(ObjectKind.COLLATION, name);
            this.collByOid.set(num(row.oid), id);
            const qName = this.tableRef(name);
            this.define(id, canonical, new Map([
                    [CREATE_SQL, "CREATE COLLATION " + qName + " (" + options + ")"],
                    [DROP_SQL, "DROP COLLATION " + qName]]));
        }
    }

    /**
     * Conversions between client encodings: there is no deparsed
     * definition, so the canonical composes source/target encoding names
     * and the conversion function (`regproc`, which is the
     * reference form `CREATE CONVERSION ... FROM` accepts).
     * `DEFAULT CONVERSION` rows stay `OTHER` — `DEFAULT`
     * heads the script check's database-wide list, so a desired script
     * can never express the flag. A conversion depends on its function
     * when the function is managed.
     */
    async loadConversions(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT cv.oid, cv.conname,
                        pg_encoding_to_char(cv.conforencoding) AS forenc,
                        pg_encoding_to_char(cv.contoencoding) AS toenc,
                        cv.conproc::regproc::text AS procname,
                        cv.conproc::oid AS conproc
                 FROM pg_conversion cv
                 JOIN pg_namespace n ON n.oid = cv.connamespace AND n.nspname = $1
                 WHERE NOT cv.condefault
                 ORDER BY cv.oid`, [this.ns])) {
            if (this.extMember(num(row.oid))) {
                continue;
            }
            const name = str(row.conname);
            const forenc = str(row.forenc);
            const toenc = str(row.toenc);
            const conproc = str(row.procname);
            const id = new ObjectId(ObjectKind.CONVERSION, name);
            this.convByOid.set(num(row.oid), id);
            const qName = this.tableRef(name);
            this.define(id,
                    "from=" + forenc + ";to=" + toenc + ";proc=" + conproc,
                    new Map([
                            [CREATE_SQL, "CREATE CONVERSION " + qName
                                    + " FOR " + sqlLiteral(forenc)
                                    + " TO " + sqlLiteral(toenc)
                                    + " FROM " + conproc],
                            [DROP_SQL, "DROP CONVERSION " + qName]]));
            this.dep(id, this.procByOid.get(num(row.conproc)));
        }
    }

    /**
     * Full-text-search objects — four managed kinds:
     * `TEXT_SEARCH_TEMPLATE`, `TEXT_SEARCH_PARSER`,
     * `TEXT_SEARCH_DICTIONARY` and `TEXT_SEARCH_CONFIGURATION`.
     * No `pg_get_*def` exists for them, so the canonical and the
     * `CREATE TEXT SEARCH …` fragment are composed from the catalog
     * row (the AGGREGATE pattern): function references as
     * `regproc` text, the dictionary's template reference and
     * `dictinitoption` verbatim, the configuration's parser
     * reference plus the `pg_ts_config_map` token→dictionary chain
     * folded into the configuration object (map rows are never objects —
     * `ALTER ... ADD MAPPING` fragments ride in `POST_SQL` in
     * `mapseqno` order). Token aliases resolve through
     * `ts_token_type(cfgparser)`; an unresolvable alias falls back
     * to `tok<N>`. Any canonical drift is a DROP+CREATE REBUILD —
     * PostgreSQL has no in-place create for the function references.
     */
    async loadTextSearch(conn: SqlConnection): Promise<void> {
        // templates: the two support functions are the whole body
        for (const row of await conn.query<Row>(
                `SELECT t.oid, t.tmplname,
                        t.tmplinit::regproc::text AS init,
                        t.tmpllexize::regproc::text AS lexize,
                        t.tmplinit::oid AS init_oid,
                        t.tmpllexize::oid AS lexize_oid
                 FROM pg_ts_template t
                 JOIN pg_namespace n ON n.oid = t.tmplnamespace
                 WHERE n.nspname = $1
                 ORDER BY t.oid`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const name = str(row.tmplname);
            const init = str(row.init);
            const lexize = str(row.lexize);
            const id = new ObjectId(ObjectKind.TEXT_SEARCH_TEMPLATE, name);
            this.tmplByOid.set(oid, id);
            this.otherOid("pg_ts_template", oid, id);
            const qName = this.qNs + "." + PgIdentifiers.quote(name);
            const opts = init === "-"
                    ? "LEXIZE = " + lexize
                    : "INIT = " + init + ", LEXIZE = " + lexize;
            this.define(id,
                    "ts_template=init:" + init + ";lexize:" + lexize,
                    new Map([
                            [CREATE_SQL, "CREATE TEXT SEARCH TEMPLATE "
                                    + qName + " (" + opts + ")"],
                            [DROP_SQL, "DROP TEXT SEARCH TEMPLATE "
                                    + qName]]));
            if (init !== "-") {
                this.dep(id, this.procByOid.get(num(row.init_oid)));
            }
            this.dep(id, this.procByOid.get(num(row.lexize_oid)));
        }
        // parsers: five support functions; prsheadline is optional ('-')
        for (const row of await conn.query<Row>(
                `SELECT p.oid, p.prsname,
                        p.prsstart::regproc::text AS start,
                        p.prstoken::regproc::text AS token,
                        p.prsend::regproc::text AS end,
                        p.prsheadline::regproc::text AS headline,
                        p.prslextype::regproc::text AS lextype,
                        p.prsstart::oid AS start_oid,
                        p.prstoken::oid AS token_oid,
                        p.prsend::oid AS end_oid,
                        p.prsheadline::oid AS headline_oid,
                        p.prslextype::oid AS lextype_oid
                 FROM pg_ts_parser p
                 JOIN pg_namespace n ON n.oid = p.prsnamespace
                 WHERE n.nspname = $1
                 ORDER BY p.oid`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const name = str(row.prsname);
            const headline = str(row.headline);
            const id = new ObjectId(ObjectKind.TEXT_SEARCH_PARSER, name);
            this.prsByOid.set(oid, id);
            this.otherOid("pg_ts_parser", oid, id);
            const qName = this.qNs + "." + PgIdentifiers.quote(name);
            let opts = "START = " + str(row.start)
                    + ", GETTOKEN = " + str(row.token)
                    + ", END = " + str(row.end)
                    + ", LEXTYPES = " + str(row.lextype);
            if (headline !== "-") {
                opts += ", HEADLINE = " + headline;
            }
            this.define(id, "ts_parser=start:" + str(row.start)
                            + ";token:" + str(row.token)
                            + ";end:" + str(row.end)
                            + ";headline:" + headline
                            + ";lextype:" + str(row.lextype),
                    new Map([
                            [CREATE_SQL, "CREATE TEXT SEARCH PARSER "
                                    + qName + " (" + opts + ")"],
                            [DROP_SQL, "DROP TEXT SEARCH PARSER "
                                    + qName]]));
            for (const c of ["start_oid", "token_oid",
                    "end_oid", "headline_oid", "lextype_oid"]) {
                const fn = num(row[c]);
                if (fn !== 0) {
                    this.dep(id, this.procByOid.get(fn));
                }
            }
        }
        // dictionaries: template reference + free-form init option text
        for (const row of await conn.query<Row>(
                `SELECT d.oid, d.dictname, d.dicttemplate,
                        tn.nspname || '.' || t.tmplname AS tmplref,
                        d.dictinitoption
                 FROM pg_ts_dict d
                 JOIN pg_ts_template t ON t.oid = d.dicttemplate
                 JOIN pg_namespace tn ON tn.oid = t.tmplnamespace
                 JOIN pg_namespace n ON n.oid = d.dictnamespace
                 WHERE n.nspname = $1
                 ORDER BY d.oid`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const name = str(row.dictname);
            const tmplref = str(row.tmplref);
            const initopt = strOrNull(row.dictinitoption);
            const id = new ObjectId(
                    ObjectKind.TEXT_SEARCH_DICTIONARY, name);
            this.dictByOid.set(oid, id);
            this.otherOid("pg_ts_dict", oid, id);
            const qName = this.qNs + "." + PgIdentifiers.quote(name);
            let opts = "TEMPLATE = " + tmplref;
            if (initopt !== null && initopt.trim().length > 0) {
                opts += ", " + initopt;
            }
            this.define(id, "ts_dict=template:" + tmplref
                            + ";init:" + (initopt === null
                                    ? "-" : initopt),
                    new Map([
                            [CREATE_SQL, "CREATE TEXT SEARCH DICTIONARY "
                                    + qName + " (" + opts + ")"],
                            [DROP_SQL, "DROP TEXT SEARCH DICTIONARY "
                                    + qName]]));
            this.dep(id, this.tmplByOid.get(num(row.dicttemplate)));
        }
        // configurations: parser reference plus the folded token→dict
        // map chain; each map row renders an ALTER ... ADD MAPPING in
        // mapseqno order inside POST_SQL
        for (const row of await conn.query<Row>(
                `SELECT c.oid, c.cfgname, c.cfgparser,
                        pn.nspname || '.' || p.prsname AS prsref
                 FROM pg_ts_config c
                 JOIN pg_ts_parser p ON p.oid = c.cfgparser
                 JOIN pg_namespace pn ON pn.oid = p.prsnamespace
                 JOIN pg_namespace n ON n.oid = c.cfgnamespace
                 WHERE n.nspname = $1
                 ORDER BY c.oid`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const name = str(row.cfgname);
            const prsref = str(row.prsref);
            const id = new ObjectId(
                    ObjectKind.TEXT_SEARCH_CONFIGURATION, name);
            this.cfgByOid.set(oid, id);
            this.otherOid("pg_ts_config", oid, id);
            const qName = this.qNs + "." + PgIdentifiers.quote(name);
            const attrs = new Map<string, string>();
            attrs.set(CREATE_SQL, "CREATE TEXT SEARCH CONFIGURATION "
                    + qName + " (PARSER = " + prsref + ")");
            attrs.set(DROP_SQL, "DROP TEXT SEARCH CONFIGURATION "
                    + qName);
            const maps = await this.configMaps(conn, oid);
            let canon = "ts_config=parser:" + prsref;
            if (maps.length > 0) {
                const entries: string[] = [];
                const alters: string[] = [];
                for (const m of maps) {
                    entries.push(m.alias + "@" + m.seq + "=" + m.dictref);
                    alters.push("ALTER TEXT SEARCH CONFIGURATION "
                            + qName + " ADD MAPPING FOR " + m.alias
                            + " WITH " + m.dictref);
                    this.dep(id, this.dictByOid.get(m.dictoid));
                }
                canon += ";map=" + entries.join(",");
                attrs.set(POST_SQL, alters.join("; "));
            }
            this.define(id, canon, attrs);
            this.dep(id, this.prsByOid.get(num(row.cfgparser)));
        }
    }

    /**
     * The `pg_ts_config_map` rows of one configuration, ordered by
     * `mapseqno`: token alias (resolved through
     * `ts_token_type(cfgparser)`, `tok<N>` when unresolvable),
     * sequence number and the dictionary reference as
     * `nspname.dictname` — normalized to `${namespace}` by
     * `define`'s caller path like every reference.
     */
    private async configMaps(conn: SqlConnection, cfgoid: number)
            : Promise<{ alias: string; seq: number; dictoid: number;
                    dictref: string }[]> {
        const out: { alias: string; seq: number; dictoid: number;
                dictref: string }[] = [];
        for (const row of await conn.query<Row>(
                `SELECT m.maptokentype, m.mapseqno, m.mapdict,
                        dn.nspname || '.' || d.dictname AS dictref,
                        tt.alias
                 FROM pg_ts_config_map m
                 JOIN pg_ts_config c ON c.oid = m.mapcfg
                 JOIN pg_ts_dict d ON d.oid = m.mapdict
                 JOIN pg_namespace dn ON dn.oid = d.dictnamespace
                 LEFT JOIN LATERAL (
                     SELECT alias FROM ts_token_type(c.cfgparser)
                     WHERE tokid = m.maptokentype
                 ) tt ON true
                 WHERE m.mapcfg = $1
                 ORDER BY m.mapseqno`, [cfgoid])) {
            let alias = strOrNull(row.alias);
            if (alias === null) {
                alias = "tok" + num(row.maptokentype);
            }
            out.push({ alias, seq: num(row.mapseqno),
                    dictoid: num(row.mapdict), dictref: str(row.dictref) });
        }
        return out;
    }

    /**
     * Operator objects — three managed kinds: `OPERATOR` (name
     * plus its `(leftarg,rightarg)` identity signature like a
     * routine, `NONE` for an absent prefix operand),
     * `OPERATOR_FAMILY` (name `opfname USING amname` — the
     * access method is part of identity) and `OPERATOR_CLASS`
     * (same name form; a class's `pg_amop`/`pg_amproc`
     * members fold into its canonical like a configuration's map).
     * No `pg_get_*def` exists, so canonical and `CREATE`
     * fragments are composed from the catalog rows: operator options
     * (procedure, argument/result types, commutator, negator,
     * restrict/join selectivity, HASHES/MERGES), family options (the
     * access method), class options (DEFAULT, FOR TYPE, FAMILY,
     * STORAGE, ordered `OPERATOR n`/`FUNCTION n` member
     * specs). Member operator references render as
     * `regoperator` text (name plus operand types), member
     * functions as `regprocedure`. Standalone family members
     * are not modeled — `ALTER OPERATOR FAMILY … ADD|DROP`
     * stays rejected in the desired-script check. Any drift is a
     * DROP+CREATE REBUILD.
     */
    async loadOperators(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT o.oid, o.oprname, o.oprleft, o.oprright,
                        o.oprleft::regtype::text AS leftt,
                        o.oprright::regtype::text AS rightt,
                        o.oprresult::regtype::text AS result,
                        o.oprcode::regproc::text AS proc,
                        o.oprcode::oid AS proc_oid,
                        o.oprrest::regproc::text AS rest,
                        o.oprrest::oid AS rest_oid,
                        o.oprjoin::regproc::text AS joinsel,
                        o.oprjoin::oid AS join_oid,
                        cn.nspname || '.' || co.oprname AS comref,
                        nn.nspname || '.' || no.oprname AS negref,
                        o.oprcom, o.oprnegate,
                        o.oprresult AS result_oid,
                        o.oprcanhash, o.oprcanmerge
                 FROM pg_operator o
                 JOIN pg_namespace n ON n.oid = o.oprnamespace
                 LEFT JOIN pg_operator co ON co.oid = o.oprcom
                 LEFT JOIN pg_namespace cn ON cn.oid = co.oprnamespace
                 LEFT JOIN pg_operator no ON no.oid = o.oprnegate
                 LEFT JOIN pg_namespace nn ON nn.oid = no.oprnamespace
                 WHERE n.nspname = $1
                 ORDER BY o.oid`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const left = num(row.oprleft);
            const right = num(row.oprright);
            const name = str(row.oprname) + "("
                    + (left === 0 ? "NONE" : str(row.leftt))
                    + ","
                    + (right === 0 ? "NONE" : str(row.rightt))
                    + ")";
            const id = new ObjectId(ObjectKind.OPERATOR, name);
            this.oprByOid.set(oid, id);
            this.otherOid("pg_operator", oid, id);
            // operator names are symbol strings, not identifiers
            // — double-quoting them is invalid syntax
            const qName = this.qNs + "." + str(row.oprname);
            let canon = "operator=" + str(row.result)
                    + ";proc:" + str(row.proc)
                    + ";rest:" + str(row.rest)
                    + ";join:" + str(row.joinsel)
                    + ";hash:" + bool(row.oprcanhash)
                    + ";merge:" + bool(row.oprcanmerge)
                    + ";com:" + nullToDash(strOrNull(row.comref))
                    + ";neg:" + nullToDash(strOrNull(row.negref));
            let opts = "PROCEDURE = " + str(row.proc);
            if (left !== 0) {
                opts += ", LEFTARG = " + str(row.leftt);
            }
            if (right !== 0) {
                opts += ", RIGHTARG = " + str(row.rightt);
            }
            opts += ", RESULT = " + str(row.result);
            if (num(row.oprcom) !== 0) {
                opts += ", COMMUTATOR = OPERATOR("
                        + str(row.comref) + ")";
            }
            if (num(row.oprnegate) !== 0) {
                opts += ", NEGATOR = OPERATOR("
                        + str(row.negref) + ")";
            }
            if (str(row.rest) !== "-") {
                opts += ", RESTRICT = " + str(row.rest);
            }
            if (str(row.joinsel) !== "-") {
                opts += ", JOIN = " + str(row.joinsel);
            }
            if (bool(row.oprcanhash)) {
                opts += ", HASHES";
            }
            if (bool(row.oprcanmerge)) {
                opts += ", MERGES";
            }
            this.define(id, canon, new Map([
                    [CREATE_SQL, "CREATE OPERATOR " + qName
                            + " (" + opts + ")"],
                    [DROP_SQL, "DROP OPERATOR " + qName + " ("
                            + (left === 0 ? "NONE" : str(row.leftt))
                            + ", "
                            + (right === 0 ? "NONE" : str(row.rightt))
                            + ")"]]));
            this.dep(id, this.procByOid.get(num(row.proc_oid)));
            this.dep(id, this.typeByOid.get(left));
            this.dep(id, this.typeByOid.get(right));
            this.dep(id, this.typeByOid.get(num(row.result_oid)));
            this.dep(id, this.oprByOid.get(num(row.oprcom)));
            this.dep(id, this.oprByOid.get(num(row.oprnegate)));
            this.dep(id, this.procByOid.get(num(row.rest_oid)));
            this.dep(id, this.procByOid.get(num(row.join_oid)));
        }
        // families: name + access method only — standalone members are
        // not modeled (ALTER FAMILY member mutations stay rejected)
        for (const row of await conn.query<Row>(
                `SELECT f.oid, f.opfname, a.amname
                 FROM pg_opfamily f
                 JOIN pg_am a ON a.oid = f.opfmethod
                 JOIN pg_namespace n ON n.oid = f.opfnamespace
                 WHERE n.nspname = $1
                 ORDER BY f.oid`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const name = str(row.opfname) + " USING " + str(row.amname);
            const id = new ObjectId(ObjectKind.OPERATOR_FAMILY, name);
            this.opfByOid.set(oid, id);
            this.otherOid("pg_opfamily", oid, id);
            const qName = this.qNs + "."
                    + PgIdentifiers.quote(str(row.opfname));
            this.define(id, "opfamily=" + str(row.amname), new Map([
                    [CREATE_SQL, "CREATE OPERATOR FAMILY " + qName
                            + " USING " + str(row.amname)],
                    [DROP_SQL, "DROP OPERATOR FAMILY " + qName
                            + " USING " + str(row.amname)]]));
        }
        // classes: am/type/family/default/storage plus the folded
        // amop/amproc member list (members where the row's left type is
        // the class's indexed type — cross-type members included)
        for (const row of await conn.query<Row>(
                `SELECT c.oid, c.opcname, a.amname, c.opcfamily,
                        fn.nspname || '.' || f.opfname AS famref,
                        format_type(c.opcintype, NULL) AS intype,
                        format_type(c.opckeytype, NULL) AS storage,
                        c.opcintype, c.opckeytype, c.opcdefault
                 FROM pg_opclass c
                 JOIN pg_am a ON a.oid = c.opcmethod
                 JOIN pg_opfamily f ON f.oid = c.opcfamily
                 JOIN pg_namespace fn ON fn.oid = f.opfnamespace
                 JOIN pg_namespace n ON n.oid = c.opcnamespace
                 WHERE n.nspname = $1
                 ORDER BY c.oid`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const name = str(row.opcname) + " USING " + str(row.amname);
            const id = new ObjectId(ObjectKind.OPERATOR_CLASS, name);
            this.opcByOid.set(oid, id);
            this.otherOid("pg_opclass", oid, id);
            const qName = this.qNs + "."
                    + PgIdentifiers.quote(str(row.opcname));
            const members = await this.opclassMembers(conn,
                    num(row.opcfamily), num(row.opcintype), id);
            let canon = "opclass=am:" + str(row.amname)
                    + ";type:" + str(row.intype)
                    + ";family:" + str(row.famref)
                    + ";default:" + bool(row.opcdefault)
                    + ";storage:" + nullToDash(strOrNull(row.storage));
            let body = (bool(row.opcdefault) ? "DEFAULT " : "")
                    + "FOR TYPE " + str(row.intype)
                    + " USING " + str(row.amname)
                    + " FAMILY " + str(row.famref);
            const specs: string[] = [];
            if (num(row.opckeytype) !== 0) {
                specs.push("STORAGE " + str(row.storage));
            }
            for (const m of members) {
                canon += ";m" + specs.length + ":" + m;
                specs.push(m);
            }
            body += specs.length === 0 ? "" : " AS "
                    + specs.join(", ");
            this.define(id, canon, new Map([
                    [CREATE_SQL, "CREATE OPERATOR CLASS " + qName
                            + " " + body],
                    [DROP_SQL, "DROP OPERATOR CLASS " + qName
                            + " USING " + str(row.amname)]]));
            this.dep(id, this.typeByOid.get(num(row.opcintype)));
            this.dep(id, this.opfByOid.get(num(row.opcfamily)));
        }
    }

    /**
     * The `pg_amop`/`pg_amproc` member specs of one
     * operator class — rows whose left type matches the class's
     * `opcintype`, ordered so `DROP` runs over a stable
     * list: `OPERATOR` members first (strategy, operator
     * reference, `FOR ORDER BY` family when set), then
     * `FUNCTION` members (number, type pair, function
     * reference). Dependency edges to member operators/functions and
     * sort families record on `id`.
     */
    private async opclassMembers(conn: SqlConnection, family: number,
            intype: number, id: ObjectId): Promise<string[]> {
        const out: string[] = [];
        for (const row of await conn.query<Row>(
                `SELECT m.amopstrategy, m.amoppurpose,
                        m.amopopr::regoperator::text AS opref,
                        m.amopopr::oid AS amopopr_oid,
                        sn.nspname || '.' || sf.opfname AS sortref,
                        m.amopsortfamily
                 FROM pg_amop m
                 LEFT JOIN pg_opfamily sf ON sf.oid = m.amopsortfamily
                 LEFT JOIN pg_namespace sn ON sn.oid = sf.opfnamespace
                 WHERE m.amopfamily = $1 AND m.amoplefttype = $2
                 ORDER BY m.amopstrategy, m.amoprighttype, m.oid`,
                [family, intype])) {
            let spec = "OPERATOR " + num(row.amopstrategy) + " "
                    + str(row.opref);
            if (str(row.amoppurpose) === "o") {
                spec += " FOR ORDER BY " + str(row.sortref);
            }
            out.push(spec);
            this.dep(id, this.oprByOid.get(num(row.amopopr_oid)));
            this.dep(id, this.opfByOid.get(num(row.amopsortfamily)));
        }
        for (const row of await conn.query<Row>(
                `SELECT m.amprocnum,
                        format_type(m.amproclefttype, NULL) AS lt,
                        format_type(m.amprocrighttype, NULL) AS rt,
                        m.amproc::regprocedure::text AS fn,
                        m.amproc::oid AS amproc_oid
                 FROM pg_amproc m
                 WHERE m.amprocfamily = $1 AND m.amproclefttype = $2
                 ORDER BY m.amprocnum, m.oid`, [family, intype])) {
            out.push("FUNCTION " + num(row.amprocnum) + " ("
                    + str(row.lt) + "," + str(row.rt)
                    + ") " + str(row.fn));
            this.dep(id, this.procByOid.get(num(row.amproc_oid)));
        }
        return out;
    }

    /**
     * Security labels (`pg_seclabel`) on managed targets: the id
     * mirrors the target like a `COMMENT`, prefixed with the
     * provider — `SECURITY_LABEL selinux:t`. The canonical is the
     * label text; re-issuing `SECURITY LABEL FOR ... ON ... IS`
     * is both the create and the in-place drift fix, and
     * `IS NULL` the drop. Labels on targets the dialect does not
     * manage stay `OTHER` (provider-qualified so two providers
     * cannot collapse into one entry).
     */
    async loadSecLabels(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT o.name AS target, d.objoid, o.cls::text AS cls, d.objsubid,
                        a.attname, d.provider, d.label
                 FROM pg_seclabel d
                 JOIN (${NS_OBJECTS}
                 ) o ON o.cls = d.classoid AND o.obj = d.objoid
                 LEFT JOIN pg_attribute a ON d.classoid = 'pg_class'::regclass
                         AND a.attrelid = d.objoid AND a.attnum = d.objsubid
                 ORDER BY 1, 2, 3`, nsParams(this.ns, 17))) {
            if (this.extMember(num(row.objoid))) {
                continue;
            }
            const target = this.annotatedTarget(str(row.cls),
                    num(row.objoid), num(row.objsubid),
                    strOrNull(row.attname));
            const provider = str(row.provider);
            if (target !== null && target.kind !== ObjectKind.OTHER
                    && this.objects.has(target)) {
                let lid = seclabelId(target, provider, "");
                if (this.objects.has(lid)) {
                    // mirrors the COMMENT collision escape (A.5)
                    lid = seclabelId(target, provider, " ("
                            + target.kind.toLowerCase() + ")");
                }
                const label = str(row.label);
                this.define(lid, label, new Map([
                        [CREATE_SQL, "SECURITY LABEL FOR "
                                + PgIdentifiers.quote(provider) + " ON "
                                + this.annotationTarget(target)
                                + " IS " + sqlLiteral(label)],
                        [DROP_SQL, "SECURITY LABEL FOR "
                                + PgIdentifiers.quote(provider) + " ON "
                                + this.annotationTarget(target) + " IS NULL"]]));
                this.dep(lid, target);
            } else {
                this.define(new ObjectId(ObjectKind.OTHER,
                        "seclabel:" + provider
                        + "." + str(row.target)
                        + (row.attname == null
                                ? "" : "." + str(row.attname))),
                        "security-label", new Map());
            }
        }
    }

    /** Loads every catalog object a desired script can create in the
     *  namespace that has no managed `ObjectKind`, as OTHER, so the
     *  diff reports it UNSUPPORTED instead of ignoring it (5.4). */
    async loadOther(conn: SqlConnection): Promise<void> {
        // user-defined types that stay unsupported: base types (array
        // types carry typelem != 0 and are not standalone; enums,
        // composites and ranges are managed TYPE objects, domains
        // managed DOMAIN, and a range's multirange folds into it)
        for (const row of await conn.query<Row>(
                `SELECT t.oid, t.typname, t.typtype
                 FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                 WHERE n.nspname = $1 AND t.typtype = 'b' AND t.typelem = 0
                 ORDER BY t.oid`, [this.ns])) {
            if (this.extMember(num(row.oid))) {
                continue;
            }
            const name = str(row.typname);
            const id = new ObjectId(ObjectKind.OTHER, "type:" + name);
            this.typeByOid.set(num(row.oid), id);
            this.otherOid("pg_type", num(row.oid), id);
            this.define(id, "typtype=" + str(row.typtype), new Map());
        }
        // extensions anchored to this namespace are managed EXTENSION
        // objects — presence-only canonical, so a version drift can
        // never surface as DRIFTED (A.5: ALTER EXTENSION UPDATE is out
        // of scope). The name keeps the extension: prefix so the id
        // stays stable across the promotion from OTHER.
        for (const row of await conn.query<Row>(
                `SELECT e.oid, e.extname
                 FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
                 WHERE n.nspname = $1
                 ORDER BY e.oid`, [this.ns])) {
            const extname = str(row.extname);
            this.define(new ObjectId(ObjectKind.EXTENSION, "extension:" + extname),
                    "extension",
                    new Map([[CREATE_SQL, "CREATE EXTENSION IF NOT EXISTS "
                            + PgIdentifiers.quote(extname)
                            + " WITH SCHEMA ${namespace}"]]));
        }
        // policies on our tables load as managed POLICY objects in
        // loadPolicies — nothing to detect here
        // encoding conversions a desired script cannot express
        // (DEFAULT CONVERSION is a database-wide object word)
        for (const row of await conn.query<Row>(
                `SELECT cv.oid, cv.conname
                 FROM pg_conversion cv JOIN pg_namespace n ON n.oid = cv.connamespace
                 WHERE n.nspname = $1 AND cv.condefault
                 ORDER BY cv.oid`, [this.ns])) {
            if (this.extMember(num(row.oid))) {
                continue;
            }
            const id = new ObjectId(ObjectKind.OTHER,
                    "conversion:" + str(row.conname));
            this.otherOid("pg_conversion", num(row.oid), id);
            this.define(id, "conversion", new Map());
        }
        // constraint rows the managed loader did not claim and which are
        // not followers of another object surface as OTHER — a contype
        // this build does not model cannot silently disappear. Trigger
        // constraints ('t') back a managed pg_trigger row and column
        // NOT NULL constraints ('n') fold into their COLUMN; non-local
        // rows are partition/inheritance copies of the parent.
        for (const row of await conn.query<Row>(
                `SELECT c.oid, rel.relname AS parent, c.conname
                 FROM pg_constraint c
                 JOIN pg_class rel ON rel.oid = c.conrelid
                 JOIN pg_namespace n ON n.oid = rel.relnamespace
                 WHERE n.nspname = $1 AND c.conislocal
                   AND c.contype NOT IN ('p','u','f','c','x','n')
                   AND NOT EXISTS (SELECT 1 FROM pg_trigger t
                                   WHERE t.tgconstraint = c.oid)
                 ORDER BY c.oid`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const id = new ObjectId(ObjectKind.OTHER,
                    "constraint:" + str(row.parent)
                            + "." + str(row.conname));
            this.conByOid.set(oid, id);
            this.otherOid("pg_constraint", oid, id);
            this.define(id, "constraint", new Map());
        }
        // collations the managed loader cannot express (default provider,
        // bound encoding) stay OTHER
        for (const row of await conn.query<Row>(
                `SELECT cl.oid, cl.collname
                 FROM pg_collation cl JOIN pg_namespace n ON n.oid = cl.collnamespace
                 WHERE n.nspname = $1
                   AND (cl.collprovider NOT IN ('c', 'i')
                        OR cl.collencoding NOT IN (-1,
                            (SELECT encoding FROM pg_database
                             WHERE datname = current_database())))
                 ORDER BY cl.oid`, [this.ns])) {
            if (this.extMember(num(row.oid))) {
                continue;
            }
            const id = new ObjectId(ObjectKind.OTHER,
                    "collation:" + str(row.collname));
            this.collByOid.set(num(row.oid), id);
            this.define(id, "collation", new Map());
        }
        // granted privileges: object acl columns are NULL until a
        // GRANT. On a managed target the acl becomes a managed
        // PRIVILEGES object mirroring the target like OWNERSHIP —
        // grantee roles are cluster-object references (4.1), whose
        // existence is a preflight concern. On an unmanaged target the
        // acl stays privileges: OTHER — still content-compared, so a
        // target holding the same grants reports nothing (5.4). The
        // definition is the sorted grantee:PRIVILEGE[*] token list with
        // the owner written <owner> and the grantor dropped.
        for (const row of await conn.query<Row>(
                `SELECT 'pg_class' AS cls, 'privileges:' || c.relname AS name,
                        c.oid, '' AS attname,
                        ${aclTokens("c.relacl", "c.relowner")}
                        AS acl
                 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = $1 AND c.relacl IS NOT NULL
                 UNION ALL
                 SELECT 'pg_proc', 'privileges:' || p.proname || '('
                         || pg_get_function_identity_arguments(p.oid) || ')',
                         p.oid, '',
                        ${aclTokens("p.proacl", "p.proowner")}
                        AS acl
                 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = $2 AND p.proacl IS NOT NULL
                 UNION ALL
                 SELECT 'pg_type', 'privileges:' || t.typname, t.oid, '',
                        ${aclTokens("t.typacl", "t.typowner")}
                        AS acl
                 FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                 WHERE n.nspname = $3 AND t.typacl IS NOT NULL
                 UNION ALL
                 SELECT 'pg_attribute', 'privileges:' || c.relname
                         || '.' || a.attname, a.attrelid, a.attname,
                        ${aclTokens("a.attacl", "c.relowner")}
                        AS acl
                 FROM pg_attribute a
                 JOIN pg_class c ON c.oid = a.attrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = $4 AND a.attacl IS NOT NULL
                   AND a.attnum > 0 AND NOT a.attisdropped
                 ORDER BY 2, 1`, nsParams(this.ns, 4))) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const acl = strOrNull(row.acl);
            if (acl === null) {
                // an acl of '{}' unnests to no rows — no grants
                // held, so nothing to compare
                continue;
            }
            const attname = str(row.attname);
            if (str(row.cls) === "pg_attribute") {
                this.columnPrivileges(oid, str(row.name), attname, acl);
                continue;
            }
            const clsName = str(row.cls);
            const target = clsName === "pg_class" ? this.relByOid.get(oid)
                    : clsName === "pg_proc" ? this.procByOid.get(oid)
                    : this.typeByOid.get(oid);
            if (target === undefined || target.kind === ObjectKind.OTHER
                    || this.foldedMembers.has(oid)) {
                this.define(new ObjectId(ObjectKind.OTHER,
                        str(row.name)), "privileges=" + acl,
                        new Map([[Dialect.CONTENT_COMPARED_ATTRIBUTE,
                                "true"]]));
                continue;
            }
            let pid = privilegesId(target, "");
            if (this.objects.has(pid)) {
                pid = privilegesId(target, " (" + target.kind
                        .toLowerCase() + ")");
            }
            this.define(pid, "privileges=" + acl,
                    new Map([[GRANT_TARGET, this.grantTarget(target)]]));
            this.dep(pid, target);
        }
        // schema-scoped default privileges (pg_default_acl) — future
        // grants the namespace applies to every new object of a class.
        // Global rows (defaclnamespace = 0) belong to no namespace and
        // stay invisible like every other cluster object (A.5).
        for (const row of await conn.query<Row>(
                `SELECT d.oid, r.rolname AS role, d.defaclobjtype AS objtype,
                        ${aclTokens("d.defaclacl", "d.defaclrole")}
                        AS acl
                 FROM pg_default_acl d
                 JOIN pg_roles r ON r.oid = d.defaclrole
                 JOIN pg_namespace n ON n.oid = d.defaclnamespace
                 WHERE n.nspname = $1
                 ORDER BY 2, 3`, [this.ns])) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const acl = strOrNull(row.acl);
            const classWord = defaultAclClass(str(row.objtype));
            if (acl === null || classWord === null) {
                continue;
            }
            const role = str(row.role);
            const id = new ObjectId(ObjectKind.DEFAULT_PRIVILEGES,
                    classWord + " for " + role);
            this.define(id, "privileges=" + acl, new Map([
                    [GRANT_TARGET, classWord],
                    [GRANT_PREFIX, "ALTER DEFAULT PRIVILEGES FOR ROLE "
                            + PgIdentifiers.quote(role)
                            + " IN SCHEMA ${namespace}"]]));
        }
        // ownership: the role that owns each object, filtered by the
        // session role — a script that assigns no owner leaves the
        // object session-owned, so an absent OWNER TO means "don't
        // care", not "claim". On a managed target the role is a
        // cluster-object reference (4.1): the ownership binding becomes
        // a managed OWNERSHIP object named and parented like the target
        // (mirroring COMMENT); on an unmanaged target it stays OTHER.
        // An index's relowner always follows its table's — it cannot
        // diverge, so index rows are skipped outright.
        for (const row of await conn.query<Row>(
                `-- an index's relowner always follows its table's — it
                 -- cannot diverge — and a composite's 'c' or a toast 't'
                 -- row follows its type/table, so 'i'/'I'/'c'/'t' are out
                 SELECT 'pg_class' AS cls, 'ownership:' || c.relname AS name,
                        c.oid, c.relowner::regrole::text AS owner
                 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = $1 AND c.relkind NOT IN ('i', 'I', 'c', 't')
                   AND c.relowner::regrole::text <> current_user
                 UNION ALL
                 SELECT 'pg_proc', 'ownership:' || p.proname || '('
                         || pg_get_function_identity_arguments(p.oid) || ')',
                        p.oid, p.proowner::regrole::text
                 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = $2 AND p.proowner::regrole::text <> current_user
                 UNION ALL
                 SELECT 'pg_type', 'ownership:' || t.typname,
                        t.oid, t.typowner::regrole::text
                 FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                 WHERE n.nspname = $3 AND t.typowner::regrole::text <> current_user
                   -- a table's implicit rowtype and its array type flip
                   -- owner with the table — neither is a type object of
                   -- its own; only a standalone composite's 'c' relation
                   -- counts. A multirange follows its range (ALTER TYPE on
                   -- it is rejected outright), so 'm' is out too
                   AND t.typtype <> 'm'
                   AND t.typelem = 0
                   AND NOT EXISTS (SELECT 1 FROM pg_class rt
                                   WHERE rt.oid = t.typrelid
                                     AND rt.relkind <> 'c')
                 ORDER BY 2`, nsParams(this.ns, 3))) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const clsName = str(row.cls);
            const target = clsName === "pg_class" ? this.relByOid.get(oid)
                    : clsName === "pg_proc" ? this.procByOid.get(oid)
                    : this.typeByOid.get(oid);
            if (target !== undefined && target.kind === ObjectKind.INDEX) {
                continue;
            }
            const owner = str(row.owner);
            if (target === undefined || target.kind === ObjectKind.OTHER
                    || this.foldedMembers.has(oid)) {
                this.define(new ObjectId(ObjectKind.OTHER,
                        str(row.name)), "ownership", new Map());
                continue;
            }
            let wid = ownershipId(target, "");
            if (this.objects.has(wid)) {
                wid = ownershipId(target, " (" + target.kind
                        .toLowerCase() + ")");
            }
            this.define(wid, "owner=" + owner,
                    new Map([[CREATE_SQL, "ALTER " + this.annotationTarget(target)
                            + " OWNER TO " + PgIdentifiers.quote(owner)]]));
            this.dep(wid, target);
        }
        // publication bindings on namespace tables (the publication itself
        // is a cluster-level reference, 4.1; the FOR TABLE edge is the
        // namespace-visible part). The binding's parent is the bound
        // table, so it orders after it; filter/column lists ride in the
        // canonical (pg_publication_rel.prqual/.prattrs, PG15+)
        const relBindingCols = this.serverMajor >= 15;
        for (const row of await conn.query<Row>(
                `SELECT p.pubname, c.relname, c.oid,
                        ${relBindingCols ? `
                        pg_get_expr(pr.prqual, pr.prrelid) AS qual,
                        (SELECT string_agg(a.attname, ',' ORDER BY u.ord)
                           FROM unnest(string_to_array(pr.prattrs::text, ' ')::smallint[])
                                WITH ORDINALITY u(attnum, ord)
                           JOIN pg_attribute a
                             ON a.attrelid = pr.prrelid AND a.attnum = u.attnum) AS cols
                        ` : "NULL AS qual, NULL AS cols\n"}
                 FROM pg_publication_rel pr
                 JOIN pg_class c ON c.oid = pr.prrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = $1
                 JOIN pg_publication p ON p.oid = pr.prpubid
                 ORDER BY 1, 2`, [this.ns])) {
            if (this.extMember(num(row.oid))) {
                continue;
            }
            this.publicationBinding(row, "TABLE", "table");
        }
        // FOR TABLES IN SCHEMA bindings — pg_publication_namespace anchors
        // the publication to the schema itself (PostgreSQL 15+)
        if (this.serverMajor >= 15) {
            for (const row of await conn.query<Row>(
                    `SELECT p.pubname, NULL AS relname,
                            NULL AS qual, NULL AS cols
                     FROM pg_publication_namespace pn
                     JOIN pg_namespace n ON n.oid = pn.pnnspid AND n.nspname = $1
                     JOIN pg_publication p ON p.oid = pn.pnpubid
                     ORDER BY 1`, [this.ns])) {
                this.publicationBinding(row, "TABLES IN SCHEMA", "schema");
            }
        }
        // casts have no namespace; they are visible through a namespace
        // source/target type or cast function (a cast between two foreign
        // types has no namespace anchor and stays outside the model).
        // A cast anchored to a managed object becomes a managed CAST —
        // its identity is the (source AS target) pair and foreign types
        // or the cast function stay cluster/namespace references in the
        // canonical; a cast anchored only to unmanaged objects stays
        // cast: OTHER
        for (const row of await conn.query<Row>(
                `SELECT c.oid, c.castsource, c.casttarget, c.castfunc,
                        'cast:' || format_type(c.castsource, NULL)
                         || '->' || format_type(c.casttarget, NULL) AS name,
                        format_type(c.castsource, NULL)
                         || '->' || format_type(c.casttarget, NULL) AS bare,
                        c.castcontext::text || ' ' || c.castmethod::text || ' '
                            || CASE WHEN c.castfunc = 0 THEN 'none'
                                    ELSE c.castfunc::regprocedure::text
                                    END AS canon,
                        'CREATE CAST (' || format_type(c.castsource, NULL)
                            || ' AS ' || format_type(c.casttarget, NULL) || ') '
                            || CASE c.castmethod
                                   WHEN 'f' THEN 'WITH FUNCTION '
                                           || c.castfunc::regprocedure::text
                                   WHEN 'i' THEN 'WITH INOUT' END
                            || CASE c.castcontext
                                   WHEN 'a' THEN ' AS ASSIGNMENT'
                                   WHEN 'i' THEN ' AS IMPLICIT'
                                   ELSE '' END AS create_sql,
                        'DROP CAST (' || format_type(c.castsource, NULL)
                            || ' AS ' || format_type(c.casttarget, NULL)
                            || ')' AS drop_sql
                 FROM pg_cast c
                 WHERE EXISTS (SELECT 1 FROM pg_type t
                               JOIN pg_namespace n ON n.oid = t.typnamespace
                               WHERE n.nspname = $1
                                 AND t.oid IN (c.castsource, c.casttarget))
                    OR EXISTS (SELECT 1 FROM pg_proc f
                               JOIN pg_namespace n ON n.oid = f.pronamespace
                               WHERE n.nspname = $2 AND f.oid = c.castfunc)
                 ORDER BY c.oid`, nsParams(this.ns, 2))) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            if (this.autoCasts.has(oid)) {
                // the range→multirange cast folds into the range
                // type — its oid remaps so dependents resolve
                this.otherOid("pg_cast", oid,
                        this.typeByOid.get(this.autoCasts.get(oid)!)!);
                continue;
            }
            const source = this.typeByOid.get(num(row.castsource));
            const target = this.typeByOid.get(num(row.casttarget));
            const func = this.procByOid.get(num(row.castfunc));
            // managed only when every anchor visible in the
            // namespace resolves managed — a cast on an
            // unsupported rowtype cannot be created, so it
            // stays OTHER instead of failing at apply
            const managed = (managedAnchor(source)
                    || managedAnchor(target) || managedAnchor(func))
                    && !(otherAnchor(source) || otherAnchor(target)
                            || otherAnchor(func));
            if (!managed || row.create_sql == null) {
                // unanchored, or a castmethod the profile cannot
                // render (binary casts were removed in PG10)
                const id = new ObjectId(ObjectKind.OTHER, str(row.name));
                this.otherOid("pg_cast", oid, id);
                this.define(id, "cast", new Map());
                continue;
            }
            const id = new ObjectId(ObjectKind.CAST, str(row.bare));
            this.otherOid("pg_cast", oid, id);
            this.dep(id, managedAnchor(source) ? source : null);
            this.dep(id, managedAnchor(target) ? target : null);
            this.dep(id, managedAnchor(func) ? func : null);
            this.define(id, str(row.canon),
                    new Map([[CREATE_SQL, str(row.create_sql)],
                            [DROP_SQL, str(row.drop_sql)]]));
        }
        // transforms have no namespace; anchor on the type or the
        // from/to-SQL functions. A transform's only expressible anchor
        // is the FOR type (3.2) — a transform on a managed type becomes
        // a managed TRANSFORM, its identity the type/language pair and
        // the language itself a cluster reference; anything else stays
        // transform: OTHER
        for (const row of await conn.query<Row>(
                `SELECT t.oid, t.trftype, t.trffromsql, t.trftosql,
                        'transform:' || format_type(t.trftype, NULL)
                         || '/' || l.lanname AS name,
                        format_type(t.trftype, NULL)
                         || '/' || l.lanname AS bare,
                        CASE WHEN t.trffromsql = 0 THEN 'none'
                             ELSE t.trffromsql::regprocedure::text END || ' '
                         || CASE WHEN t.trftosql = 0 THEN 'none'
                                 ELSE t.trftosql::regprocedure::text
                                 END AS canon,
                        'CREATE TRANSFORM FOR '
                         || format_type(t.trftype, NULL) || ' LANGUAGE '
                         || quote_ident(l.lanname)
                         || ' ('
                         || CASE WHEN t.trffromsql = 0 THEN ''
                                 ELSE 'FROM SQL WITH FUNCTION '
                                      || t.trffromsql::regprocedure::text
                                      || CASE WHEN t.trftosql <> 0
                                              THEN ', ' ELSE '' END
                                 END
                         || CASE WHEN t.trftosql = 0 THEN ''
                                 ELSE 'TO SQL WITH FUNCTION '
                                      || t.trftosql::regprocedure::text
                                 END
                         || ')' AS create_sql,
                        'DROP TRANSFORM FOR '
                         || format_type(t.trftype, NULL) || ' LANGUAGE '
                         || quote_ident(l.lanname) AS drop_sql
                 FROM pg_transform t JOIN pg_language l ON l.oid = t.trflang
                 WHERE EXISTS (SELECT 1 FROM pg_type ty
                               JOIN pg_namespace n ON n.oid = ty.typnamespace
                               WHERE n.nspname = $1 AND ty.oid = t.trftype)
                    OR EXISTS (SELECT 1 FROM pg_proc f
                               JOIN pg_namespace n ON n.oid = f.pronamespace
                               WHERE n.nspname = $2
                                 AND f.oid IN (t.trffromsql, t.trftosql))
                 ORDER BY t.oid`, nsParams(this.ns, 2))) {
            const oid = num(row.oid);
            if (this.extMember(oid)) {
                continue;
            }
            const type = this.typeByOid.get(num(row.trftype));
            if (!managedAnchor(type)) {
                const id = new ObjectId(ObjectKind.OTHER, str(row.name));
                this.otherOid("pg_transform", oid, id);
                this.define(id, "transform", new Map());
                continue;
            }
            const id = new ObjectId(ObjectKind.TRANSFORM, str(row.bare));
            this.otherOid("pg_transform", oid, id);
            this.dep(id, type);
            this.dep(id, this.procByOid.get(num(row.trffromsql)));
            this.dep(id, this.procByOid.get(num(row.trftosql)));
            this.define(id, str(row.canon),
                    new Map([[CREATE_SQL, str(row.create_sql)],
                            [DROP_SQL, str(row.drop_sql)]]));
        }
        // languages are database-level; a language whose handlers live in
        // the namespace is part of the desired state
        for (const row of await conn.query<Row>(
                `SELECT l.oid, 'language:' || l.lanname AS name
                 FROM pg_language l
                 WHERE EXISTS (SELECT 1 FROM pg_proc h
                               JOIN pg_namespace n ON n.oid = h.pronamespace
                               WHERE n.nspname = $1
                                 AND h.oid IN (l.lanplcallfoid, l.laninline,
                                               l.lanvalidator))
                 ORDER BY l.oid`, [this.ns])) {
            if (this.extMember(num(row.oid))) {
                continue;
            }
            const id = new ObjectId(ObjectKind.OTHER, str(row.name));
            this.langByOid.set(num(row.oid), id);
            this.otherOid("pg_language", num(row.oid), id);
            this.define(id, "language", new Map());
        }
        // sequence publication bindings — the pg_publication_sequences
        // view exists from PostgreSQL 19 (pg_publication_rel covers
        // tables) and also expands FOR ALL SEQUENCES flags, which have no
        // per-sequence binding to reconcile — those pubs are filtered out
        if (this.serverMajor >= 19) {
            for (const row of await conn.query<Row>(
                    `SELECT v.pubname, v.sequencename AS relname,
                            NULL AS qual, NULL AS cols
                     FROM pg_publication_sequences v
                     JOIN pg_publication p ON p.pubname = v.pubname
                      WHERE v.schemaname = $1 AND NOT p.puballsequences
                     ORDER BY 1`, [this.ns])) {
                this.publicationBinding(row, "SEQUENCE", "sequence");
            }
        }
    }

    /**
     * One managed `PUBLICATION` binding: name the
     * publication (a cluster-object reference, 4.1), parent the bound
     * relation, and render the `ALTER PUBLICATION` add/drop pair.
     * `verb` is the statement's object word — `TABLE`,
     * `TABLES IN SCHEMA` or `SEQUENCE`; `shape` is the
     * canonical's binding discriminator.
     */
    private publicationBinding(row: Row, verb: string, shape: string): void {
        const pub = str(row.pubname);
        const rel = strOrNull(row.relname);
        const qual = strOrNull(row.qual);
        const cols = strOrNull(row.cols);
        const id = rel === null
                ? new ObjectId(ObjectKind.PUBLICATION, pub)
                : new ObjectId(ObjectKind.PUBLICATION, pub, rel);
        const qpub = PgIdentifiers.quote(pub);
        let create = "ALTER PUBLICATION " + qpub + " ADD " + verb + " ";
        if (rel === null) {
            create += "${namespace}";
        } else {
            create += this.tableRef(rel);
        }
        if (cols !== null) {
            create += " (" + cols.split(",")
                    .map(c => PgIdentifiers.quote(c)).join(",") + ")";
        }
        if (qual !== null) {
            create += " WHERE (" + qual + ")";
        }
        const drop = "ALTER PUBLICATION " + qpub + " DROP " + verb + " "
                + (rel === null ? "${namespace}" : this.tableRef(rel));
        this.define(id, "binding=" + shape
                + (qual !== null ? ";filter=" + qual : "")
                + (cols !== null ? ";columns=" + cols : ""),
                new Map([[CREATE_SQL, create], [DROP_SQL, drop]]));
    }

    /**
     * A column-level grant — `pg_attribute.attacl` on a relation in
     * the namespace. On a managed relation it mirrors the column like a
     * comment does (`PRIVILEGES <col>` parented on the relation)
     * and renders through the same token diff, the column name
     * parenthesized after the privilege word; on an unmanaged relation it
     * stays `privileges:<rel>.<col>` OTHER, content-compared.
     */
    private columnPrivileges(relOid: number, name: string, attname: string,
            acl: string): void {
        const rel = this.relByOid.get(relOid);
        if (rel === undefined || rel.kind === ObjectKind.OTHER
                || this.foldedMembers.has(relOid)) {
            this.define(new ObjectId(ObjectKind.OTHER, name),
                    "privileges=" + acl,
                    new Map([[Dialect.CONTENT_COMPARED_ATTRIBUTE, "true"]]));
            return;
        }
        const pid = new ObjectId(ObjectKind.PRIVILEGES, attname, rel.name);
        const attrs = new Map<string, string>();
        attrs.set(GRANT_TARGET, this.grantTarget(rel));
        attrs.set(GRANT_COLUMN, PgIdentifiers.quote(attname));
        this.define(pid, "privileges=" + acl, attrs);
        // order after the column when it is a modeled object (tables and
        // partitions carry COLUMN objects; views and materialized views
        // do not) — otherwise the relation itself suffices
        const column = new ObjectId(ObjectKind.COLUMN, attname, rel.name);
        this.dep(pid, this.objects.has(column) ? column : rel);
    }

    /**
     * Descriptions on namespace objects (pg_description). A description on
     * a managed object becomes a managed `COMMENT` object
     * whose `ObjectId` mirrors the target (its name, and its parent
     * for child objects); a description on anything else stays the OTHER
     * `comment:<name>` object, undeclarable and reported on every
     * run. pg_extension is not in NS_OBJECTS — an extension's shipped
     * description is database-wide metadata (COMMENT ON EXTENSION is
     * rejected), and an anchored object here would poison desired state
     * for the managed EXTENSION object; the extension row itself reports.
     * Comments sharing the mirrored id (a column and a policy with the
     * same name on one table, say) merge into one COMMENT object, as on
     * both sides of the diff, so they still converge — documented in A.5.
     */
    async loadComments(conn: SqlConnection): Promise<void> {
        for (const row of await conn.query<Row>(
                `SELECT o.cls::text AS cls, d.objoid, d.objsubid,
                        o.name AS target, a.attname, d.description
                 FROM pg_description d
                 JOIN (${NS_OBJECTS}
                 ) o ON o.cls = d.classoid AND o.obj = d.objoid
                 LEFT JOIN pg_attribute a ON d.classoid = 'pg_class'::regclass
                         AND a.attrelid = d.objoid AND a.attnum = d.objsubid
                 ORDER BY 1, 2, 3`, nsParams(this.ns, 17))) {
            if (this.extMember(num(row.objoid))) {
                continue;
            }
            const attname = strOrNull(row.attname);
            const target = this.annotatedTarget(str(row.cls),
                    num(row.objoid), num(row.objsubid), attname);
            if (target !== null && target.kind !== ObjectKind.OTHER
                    && this.objects.has(target)) {
                let cid = commentId(target, "");
                if (this.objects.has(cid)) {
                    // two targets share the mirrored id (a column
                    // and a same-named policy, say): the later one
                    // gains a (kind) suffix so both surface (A.5)
                    cid = commentId(target, " (" + target.kind
                            .toLowerCase() + ")");
                }
                const text = str(row.description);
                this.define(cid, text,
                        new Map([[CREATE_SQL, this.commentSql(target, text)]]));
                this.dep(cid, target);
            } else {
                this.define(new ObjectId(ObjectKind.OTHER, "comment:"
                        + str(row.target)
                        + (attname === null ? "" : "." + attname)),
                        "comment", new Map());
            }
        }
    }

    /**
     * The `<KEYWORD> ${namespace}.<name>` target phrase of a
     * `GRANT|REVOKE ... ON` statement for a managed target — the
     * relation kinds grant through `TABLE` except foreign tables,
     * which use their own keyword; routines reuse the annotation phrase
     * (`FUNCTION|PROCEDURE name(sigargs)`).
     */
    private grantTarget(target: ObjectId): string {
        const n = "${namespace}";
        const name = PgIdentifiers.quote(target.name);
        switch (target.kind) {
            case ObjectKind.TABLE:
            case ObjectKind.PARTITION:
            case ObjectKind.VIEW:
            case ObjectKind.MATERIALIZED_VIEW:
                return "TABLE " + n + "." + name;
            case ObjectKind.FOREIGN_TABLE:
                return "FOREIGN TABLE " + n + "." + name;
            case ObjectKind.SEQUENCE:
                return "SEQUENCE " + n + "." + name;
            case ObjectKind.TYPE:
                return "TYPE " + n + "." + name;
            case ObjectKind.DOMAIN:
                return "DOMAIN " + n + "." + name;
            case ObjectKind.ROUTINE:
                return this.routineAnnotationTarget(target);
            case ObjectKind.AGGREGATE:
                return this.routineAnnotationTarget(target, "FUNCTION");
            default:
                throw new Error("no grant form for " + target.kind);
        }
    }

    /** The managed object an annotation row (comment or security label)
     *  targets, or null when the annotated catalog entry maps to no
     *  managed object. */
    private annotatedTarget(cls: string, objoid: number, objsubid: number,
            attname: string | null): ObjectId | null {
        switch (cls) {
            case "pg_class": {
                if (objsubid === 0) {
                    return this.relByOid.get(objoid) ?? null;
                }
                // column annotations resolve where columns are managed
                // objects — tables, partitions and foreign tables all
                // carry them parented on the relation
                const rel = this.relByOid.get(objoid);
                return attname !== null && rel !== undefined
                        && (rel.kind === ObjectKind.TABLE
                                || rel.kind === ObjectKind.PARTITION
                                || rel.kind === ObjectKind.FOREIGN_TABLE)
                        ? new ObjectId(ObjectKind.COLUMN, attname, rel.name)
                        : null;
            }
            case "pg_proc":
                // a folded constructor has no annotation target of its
                // own — a comment or label on it stays OTHER
                return this.foldedMembers.has(objoid) ? null
                        : this.procByOid.get(objoid) ?? null;
            case "pg_constraint":
                return this.conByOid.get(objoid) ?? null;
            case "pg_policy":
                return this.polByOid.get(objoid) ?? null;
            case "pg_statistic_ext":
                return this.stxByOid.get(objoid) ?? null;
            case "pg_rewrite":
                return this.ruleByOid.get(objoid) ?? null;
            case "pg_collation":
                return this.collByOid.get(objoid) ?? null;
            case "pg_conversion":
                return this.convByOid.get(objoid) ?? null;
            case "pg_type":
                // a folded multirange likewise stays OTHER
                return this.foldedMembers.has(objoid) ? null
                        : this.typeByOid.get(objoid) ?? null;
            case "pg_trigger":
                return this.tgByOid.get(objoid) ?? null;
            case "pg_ts_template":
                return this.tmplByOid.get(objoid) ?? null;
            case "pg_ts_parser":
                return this.prsByOid.get(objoid) ?? null;
            case "pg_ts_dict":
                return this.dictByOid.get(objoid) ?? null;
            case "pg_ts_config":
                return this.cfgByOid.get(objoid) ?? null;
            case "pg_operator":
                return this.oprByOid.get(objoid) ?? null;
            case "pg_opclass":
                return this.opcByOid.get(objoid) ?? null;
            case "pg_opfamily":
                return this.opfByOid.get(objoid) ?? null;
            default:
                return null;
        }
    }

    /** The `COMMENT ON <target> IS 'text'` statement for a managed target. */
    private commentSql(target: ObjectId, text: string): string {
        return "COMMENT ON " + this.annotationTarget(target) + " IS "
                + sqlLiteral(text);
    }

    /**
     * The `<KEYWORD> <name>` target phrase shared by
     * `COMMENT ON` and `SECURITY LABEL ... ON`.
     */
    private annotationTarget(target: ObjectId): string {
        const n = "${namespace}";
        const name = PgIdentifiers.quote(target.name);
        const parent = target.parent;
        const qParent = parent === undefined
                ? null : n + "." + PgIdentifiers.quote(parent);
        switch (target.kind) {
            case ObjectKind.TABLE:
            case ObjectKind.PARTITION:
                return "TABLE " + n + "." + name;
            case ObjectKind.FOREIGN_TABLE:
                return "FOREIGN TABLE " + n + "." + name;
            case ObjectKind.VIEW:
                return "VIEW " + n + "." + name;
            case ObjectKind.MATERIALIZED_VIEW:
                return "MATERIALIZED VIEW " + n + "." + name;
            case ObjectKind.SEQUENCE:
                return "SEQUENCE " + n + "." + name;
            case ObjectKind.INDEX:
                return "INDEX " + n + "." + name;
            case ObjectKind.COLUMN:
                return "COLUMN " + qParent + "." + name;
            case ObjectKind.PRIMARY_KEY:
            case ObjectKind.UNIQUE:
            case ObjectKind.FOREIGN_KEY:
            case ObjectKind.CHECK:
            case ObjectKind.EXCLUSION:
                return "CONSTRAINT " + name + " ON " + qParent;
            case ObjectKind.TRIGGER:
                return "TRIGGER " + name + " ON " + qParent;
            case ObjectKind.POLICY:
                return "POLICY " + name + " ON " + qParent;
            case ObjectKind.STATISTICS:
                return "STATISTICS " + n + "." + name;
            case ObjectKind.RULE:
                return "RULE " + name + " ON " + qParent;
            case ObjectKind.COLLATION:
                return "COLLATION " + n + "." + name;
            case ObjectKind.CONVERSION:
                return "CONVERSION " + n + "." + name;
            case ObjectKind.TYPE:
                return "TYPE " + n + "." + name;
            case ObjectKind.DOMAIN:
                return "DOMAIN " + n + "." + name;
            case ObjectKind.ROUTINE:
                return this.routineAnnotationTarget(target);
            case ObjectKind.AGGREGATE:
                return this.routineAnnotationTarget(target, "AGGREGATE");
            case ObjectKind.TEXT_SEARCH_TEMPLATE:
                return "TEXT SEARCH TEMPLATE " + n + "." + name;
            case ObjectKind.TEXT_SEARCH_PARSER:
                return "TEXT SEARCH PARSER " + n + "." + name;
            case ObjectKind.TEXT_SEARCH_DICTIONARY:
                return "TEXT SEARCH DICTIONARY " + n + "." + name;
            case ObjectKind.TEXT_SEARCH_CONFIGURATION:
                return "TEXT SEARCH CONFIGURATION " + n + "." + name;
            // an operator name is a symbol plus signature — never an
            // identifier to quote: OPERATOR ns.=(integer,integer)
            case ObjectKind.OPERATOR:
                return "OPERATOR " + n + "." + target.name;
            case ObjectKind.OPERATOR_CLASS:
            case ObjectKind.OPERATOR_FAMILY: {
                // the id name is "<name> USING <am>" — only the name
                // part is schema-qualified and quoted
                const i = target.name.indexOf(" USING ");
                return (target.kind === ObjectKind.OPERATOR_CLASS
                        ? "OPERATOR CLASS " : "OPERATOR FAMILY ")
                        + n + "." + PgIdentifiers.quote(target.name.substring(0, i))
                        + target.name.substring(i);
            }
            default:
                throw new Error("no annotation form for " + target.kind);
        }
    }

    /** `<word> <name>(<sigargs>)` — `FUNCTION` for a grant
     *  on an aggregate, `AGGREGATE` for its annotation/owner
     *  forms; no `kind` reads the routine's own `PROKIND`. */
    private routineAnnotationTarget(target: ObjectId, kind?: string): string {
        if (kind === undefined) {
            const o0 = this.objects.get(target);
            kind = (o0?.attrs.get(PROKIND) ?? "f") === "p"
                    ? "PROCEDURE" : "FUNCTION";
        }
        const o = this.objects.get(target);
        // the id name was built as proname + "(" + sigargs + ")" — strip
        // that exact suffix so a quoted proname containing '(' survives
        const sigargs = o?.attrs.get(SIG_ARGS) ?? "";
        const suffix = "(" + sigargs + ")";
        const fname = target.name.endsWith(suffix)
                ? target.name.substring(0, target.name.length - suffix.length)
                : target.name;
        return kind + " ${namespace}."
                + PgIdentifiers.quote(fname) + "(" + sigargs + ")";
    }

    async loadDependencies(conn: SqlConnection): Promise<void> {
        // view dependencies via the _RETURN rewrite rule
        for (const row of await conn.query<Row>(
                `SELECT DISTINCT v.oid AS viewer, d.refobjid AS dep
                 FROM pg_rewrite r
                 JOIN pg_depend d ON d.classid = 'pg_rewrite'::regclass AND d.objid = r.oid
                     AND d.refclassid = 'pg_class'::regclass
                 JOIN pg_class v ON v.oid = r.ev_class AND v.relkind IN ('v','m')
                 JOIN pg_namespace vn ON vn.oid = v.relnamespace AND vn.nspname = $1
                 JOIN pg_class rc ON rc.oid = d.refobjid
                 JOIN pg_namespace rn ON rn.oid = rc.relnamespace AND rn.nspname = $2
                 WHERE r.rulename = '_RETURN' AND rc.oid <> v.oid`,
                [this.ns, this.ns])) {
            const viewer = this.relByOid.get(num(row.viewer));
            this.depOid(viewer!, num(row.dep));
        }
        // view-on-routine and view-on-type dependencies
        for (const row of await conn.query<Row>(
                `SELECT DISTINCT v.oid AS viewer, d.refobjid AS dep
                 FROM pg_rewrite r
                 JOIN pg_depend d ON d.classid = 'pg_rewrite'::regclass AND d.objid = r.oid
                     AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                        'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                        'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)
                 JOIN pg_class v ON v.oid = r.ev_class AND v.relkind IN ('v','m')
                 JOIN pg_namespace vn ON vn.oid = v.relnamespace AND vn.nspname = $1
                 WHERE r.rulename = '_RETURN'`, [this.ns])) {
            const viewer = this.relByOid.get(num(row.viewer));
            this.depOid(viewer!, num(row.dep));
        }
        // routine dependencies on relations, routines and types
        // (SQL-language bodies and argument/return types)
        for (const row of await conn.query<Row>(
                `SELECT DISTINCT d.objid AS caller, d.refobjid AS dep
                 FROM pg_depend d
                 JOIN pg_proc p ON p.oid = d.objid
                 JOIN pg_namespace pn ON pn.oid = p.pronamespace AND pn.nspname = $1
                 WHERE d.classid = 'pg_proc'::regclass
                   AND d.refclassid IN ('pg_class'::regclass, 'pg_proc'::regclass,
                                        'pg_type'::regclass, 'pg_language'::regclass,
                                        'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                        'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                        'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)`,
                [this.ns])) {
            const caller = this.procByOid.get(num(row.caller));
            if (caller !== undefined) {
                this.depOid(caller, num(row.dep));
            }
        }
        // index-on-routine dependencies (expression indexes)
        for (const row of await conn.query<Row>(
                `SELECT DISTINCT i.indexrelid AS idx, d.refobjid AS dep
                 FROM pg_depend d
                 JOIN pg_class ic ON ic.oid = d.objid AND ic.relkind = 'i'
                 JOIN pg_index i ON i.indexrelid = ic.oid
                 JOIN pg_namespace n ON n.oid = ic.relnamespace AND n.nspname = $1
                 JOIN pg_proc p ON p.oid = d.refobjid
                 JOIN pg_namespace pn ON pn.oid = p.pronamespace AND pn.nspname = $2
                 WHERE d.classid = 'pg_class'::regclass AND d.refclassid = 'pg_proc'::regclass`,
                [this.ns, this.ns])) {
            const idx = this.relByOid.get(num(row.idx));
            const proc = this.procByOid.get(num(row.dep));
            this.dep(idx, proc);
        }
        // column defaults on our tables -> sequences, routines and types
        // (the edge lands on the column and on its parent table, so that a
        // covered column inside a CREATE TABLE still orders after the ref)
        for (const row of await conn.query<Row>(
                `SELECT ad.adrelid AS tbl, a.attname AS col, d.refobjid AS dep
                 FROM pg_depend d
                 JOIN pg_attrdef ad ON ad.oid = d.objid
                 JOIN pg_class tc ON tc.oid = ad.adrelid
                 JOIN pg_namespace tn ON tn.oid = tc.relnamespace AND tn.nspname = $1
                 JOIN pg_attribute a ON a.attrelid = ad.adrelid AND a.attnum = ad.adnum
                     AND NOT a.attisdropped
                 WHERE d.classid = 'pg_attrdef'::regclass AND d.deptype = 'n'
                   AND d.refclassid IN ('pg_class'::regclass, 'pg_proc'::regclass,
                                        'pg_type'::regclass,
                                        'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                        'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                        'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)`,
                [this.ns])) {
            const table = this.relByOid.get(num(row.tbl));
            if (table === undefined) {
                continue;
            }
            const column = new ObjectId(ObjectKind.COLUMN,
                    str(row.col), table.name);
            const ref = this.byOid(num(row.dep));
            this.dep(column, ref);
            this.depTableForCoveredChild(table, ref);
        }
        // constraints on our tables -> routines, types, the unique
        // constraint backing an FK (a pg_class ref to its index), and
        // other constraints
        for (const row of await conn.query<Row>(
                `SELECT d.objid AS con, d.refclassid::regclass::text AS refcls,
                        d.refobjid AS dep
                 FROM pg_depend d
                 JOIN pg_constraint co ON co.oid = d.objid
                 JOIN pg_class cr ON cr.oid = co.conrelid
                 JOIN pg_namespace cn ON cn.oid = cr.relnamespace AND cn.nspname = $1
                 WHERE d.classid = 'pg_constraint'::regclass
                   AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,
                                        'pg_class'::regclass, 'pg_constraint'::regclass,
                                        'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                        'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                        'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)`,
                [this.ns])) {
            const from = this.conByOid.get(num(row.con));
            const ref = num(row.dep);
            const refcls = str(row.refcls);
            const to = refcls === "pg_constraint" ? this.conByOid.get(ref)
                    : refcls === "pg_class"
                    ? (this.conByIndexOid.get(ref) ?? this.relByOid.get(ref))
                    : this.byOid(ref);
            this.dep(from, to);
        }
        // domains and ranges -> their base/subtype, canonical and
        // subtype-diff routines; domain check constraints -> the
        // routines, types and relations their expressions use
        for (const row of await conn.query<Row>(
                `SELECT d.objid AS dom, d.refobjid AS dep
                 FROM pg_depend d
                 JOIN pg_type t ON t.oid = d.objid AND t.typtype IN ('d','r')
                 JOIN pg_namespace n ON n.oid = t.typnamespace AND n.nspname = $1
                 WHERE d.classid = 'pg_type'::regclass
                   AND d.refclassid IN ('pg_type'::regclass, 'pg_proc'::regclass,'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                        'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                        'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)
                 UNION
                 SELECT c.contypid, d.refobjid
                 FROM pg_depend d
                 JOIN pg_constraint c ON c.oid = d.objid AND c.contypid <> 0
                 JOIN pg_type t ON t.oid = c.contypid
                 JOIN pg_namespace n ON n.oid = t.typnamespace AND n.nspname = $2
                 WHERE d.classid = 'pg_constraint'::regclass
                   AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,
                                        'pg_class'::regclass,
                                        'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                        'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                        'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)`,
                [this.ns, this.ns])) {
            this.depOid(this.typeByOid.get(num(row.dom))!, num(row.dep));
        }
        // policies -> the routines, types and relations their
        // USING / WITH CHECK expressions reference
        for (const row of await conn.query<Row>(
                `SELECT d.objid AS pol, d.refobjid AS dep
                 FROM pg_depend d
                 JOIN pg_policy po ON po.oid = d.objid
                 JOIN pg_class c ON c.oid = po.polrelid
                 JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = $1
                 WHERE d.classid = 'pg_policy'::regclass
                   AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,
                                        'pg_class'::regclass,
                                        'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                        'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                        'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)`,
                [this.ns])) {
            this.depOid(this.polByOid.get(num(row.pol))!, num(row.dep));
        }
        // column types and collations on our tables -> our domains, enums
        // and collations (objsubid is the column's attnum; a zero objsubid
        // is an object-level edge)
        for (const row of await conn.query<Row>(
                `SELECT d.objid AS tbl, d.objsubid AS attnum, a.attname AS col,
                        d.refobjid AS dep
                 FROM pg_depend d
                 JOIN pg_class tc ON tc.oid = d.objid
                 JOIN pg_namespace tn ON tn.oid = tc.relnamespace AND tn.nspname = $1
                 LEFT JOIN pg_attribute a ON a.attrelid = d.objid AND a.attnum = d.objsubid
                     AND NOT a.attisdropped
                 WHERE d.classid = 'pg_class'::regclass AND d.deptype = 'n'
                   AND d.refclassid IN ('pg_type'::regclass, 'pg_collation'::regclass,'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                        'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                        'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)`,
                [this.ns])) {
            const table = this.relByOid.get(num(row.tbl));
            if (table === undefined) {
                continue;
            }
            const depOidV = num(row.dep);
            let ref = this.byOid(depOidV) ?? null; // resolved twice: collByOid is the fallback
            if (ref === null) {
                ref = this.collByOid.get(depOidV) ?? null;
            }
            const col = strOrNull(row.col);
            if (col !== null) {
                this.dep(new ObjectId(ObjectKind.COLUMN, col, table.name), ref);
            }
            this.depTableForCoveredChild(table, ref);
        }
        // partition children (tables and indexes) -> their parents
        for (const row of await conn.query<Row>(
                `SELECT d.objid AS child, d.refobjid AS dep
                 FROM pg_depend d
                 JOIN pg_class cc ON cc.oid = d.objid
                 JOIN pg_namespace cn ON cn.oid = cc.relnamespace AND cn.nspname = $1
                 WHERE d.classid = 'pg_class'::regclass AND d.refclassid = 'pg_class'::regclass
                   AND d.deptype IN ('a', 'P')`, [this.ns])) {
            this.depOid(this.relByOid.get(num(row.child))!, num(row.dep));
        }
        // unsupported objects -> the managed objects they depend on.
        // An OTHER object can never be recreated, so a dependent one must
        // block a drop or recreate of what it uses (5.8.3): a rule or an
        // exclusion constraint dies with its table, a cast or a range on
        // our type blocks dropping it, a language blocks its handlers.
        for (const row of await conn.query<Row>(
                `SELECT d.classid::regclass::text AS cls, d.objid,
                        d.refclassid::regclass::text AS refcls, d.refobjid AS dep
                 FROM pg_depend d
                 WHERE d.classid IN ('pg_type'::regclass,'pg_language'::regclass,
                                     'pg_rewrite'::regclass,'pg_statistic_ext'::regclass,
                                     'pg_cast'::regclass,'pg_transform'::regclass,
                                     'pg_conversion'::regclass,'pg_operator'::regclass,
                                     'pg_opclass'::regclass,'pg_opfamily'::regclass,
                                     'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                     'pg_ts_parser'::regclass,'pg_ts_template'::regclass)
                   AND d.refclassid IN ('pg_class'::regclass,'pg_proc'::regclass,
                                        'pg_type'::regclass,'pg_collation'::regclass,
                                        'pg_language'::regclass,'pg_constraint'::regclass,
                                        'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                        'pg_ts_parser'::regclass,'pg_ts_template'::regclass)`)) {
            const map = this.otherByOid.get(str(row.cls));
            const from = map?.get(num(row.objid));
            if (from === undefined || from === null) {
                continue;
            }
            const ref = num(row.dep);
            const refcls = str(row.refcls);
            const to = refcls === "pg_constraint" ? this.conByOid.get(ref)
                    : refcls === "pg_collation" ? this.collByOid.get(ref)
                    : refcls === "pg_ts_config" ? this.cfgByOid.get(ref)
                    : refcls === "pg_ts_dict" ? this.dictByOid.get(ref)
                    : refcls === "pg_ts_parser" ? this.prsByOid.get(ref)
                    : refcls === "pg_ts_template" ? this.tmplByOid.get(ref)
                    : this.byOid(ref);
            this.dep(from, to);
        }
    }

    async loadExternalDependents(conn: SqlConnection): Promise<void> {
        const sql = `
            SELECT DISTINCT d.refobjid AS tgt, rn.nspname AS depns, rc.relname AS depname
            FROM pg_depend d
            JOIN pg_rewrite r ON r.oid = d.objid
            JOIN pg_class rc ON rc.oid = r.ev_class
            JOIN pg_namespace rn ON rn.oid = rc.relnamespace
            JOIN pg_class tgt ON tgt.oid = d.refobjid
            JOIN pg_namespace tn ON tn.oid = tgt.relnamespace AND tn.nspname = $1
            WHERE d.classid = 'pg_rewrite'::regclass AND d.refclassid = 'pg_class'::regclass
              AND rn.nspname <> $2
            UNION
            SELECT DISTINCT d.refobjid, rn.nspname, rc.relname
            FROM pg_depend d
            JOIN pg_constraint co ON co.oid = d.objid
            JOIN pg_class rc ON rc.oid = co.conrelid
            JOIN pg_namespace rn ON rn.oid = rc.relnamespace
            JOIN pg_class tgt ON tgt.oid = d.refobjid
            JOIN pg_namespace tn ON tn.oid = tgt.relnamespace AND tn.nspname = $3
            WHERE d.classid = 'pg_constraint'::regclass AND d.refclassid = 'pg_class'::regclass
              AND rn.nspname <> $4
            UNION
            SELECT DISTINCT d.refobjid, sn.nspname, sq.relname
            FROM pg_depend d
            JOIN pg_class sq ON sq.oid = d.objid AND sq.relkind = 'S'
            JOIN pg_namespace sn ON sn.oid = sq.relnamespace
            JOIN pg_class tgt ON tgt.oid = d.refobjid
            JOIN pg_namespace tn ON tn.oid = tgt.relnamespace AND tn.nspname = $5
            WHERE d.classid = 'pg_class'::regclass AND d.refclassid = 'pg_class'::regclass
              AND d.deptype = 'a' AND sn.nspname <> $6
            UNION
            SELECT DISTINCT d.refobjid, pn.nspname, p.proname
            FROM pg_depend d
            JOIN pg_proc p ON p.oid = d.objid
            JOIN pg_namespace pn ON pn.oid = p.pronamespace
            WHERE d.classid = 'pg_proc'::regclass
              AND d.refclassid IN ('pg_class'::regclass, 'pg_proc'::regclass,
                                   'pg_type'::regclass, 'pg_language'::regclass)
              AND pn.nspname <> $7
            UNION
            SELECT DISTINCT d.refobjid, rn.nspname, rc.relname
            FROM pg_depend d
            JOIN pg_rewrite r ON r.oid = d.objid
            JOIN pg_class rc ON rc.oid = r.ev_class
            JOIN pg_namespace rn ON rn.oid = rc.relnamespace
            WHERE d.classid = 'pg_rewrite'::regclass
              AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass)
              AND rn.nspname <> $8
            UNION
            SELECT DISTINCT d.refobjid, rn.nspname, rc.relname || '.' || t.tgname
            FROM pg_depend d
            JOIN pg_trigger t ON t.oid = d.objid
            JOIN pg_class rc ON rc.oid = t.tgrelid
            JOIN pg_namespace rn ON rn.oid = rc.relnamespace
            WHERE d.classid = 'pg_trigger'::regclass
              AND d.refclassid = 'pg_proc'::regclass AND rn.nspname <> $9
            UNION
            SELECT DISTINCT d.refobjid, rn.nspname, rc.relname || '.' || a.attname
            FROM pg_depend d
            JOIN pg_attrdef ad ON ad.oid = d.objid
            JOIN pg_attribute a ON a.attrelid = ad.adrelid AND a.attnum = ad.adnum
                AND NOT a.attisdropped
            JOIN pg_class rc ON rc.oid = ad.adrelid
            JOIN pg_namespace rn ON rn.oid = rc.relnamespace
            WHERE d.classid = 'pg_attrdef'::regclass AND d.deptype = 'n'
              AND d.refclassid IN ('pg_class'::regclass, 'pg_proc'::regclass,
                                   'pg_type'::regclass)
              AND rn.nspname <> $10
            UNION
            SELECT DISTINCT d.refobjid, rn.nspname, co.conname
            FROM pg_depend d
            JOIN pg_constraint co ON co.oid = d.objid
            JOIN pg_class rc ON rc.oid = co.conrelid
            JOIN pg_namespace rn ON rn.oid = rc.relnamespace
            WHERE d.classid = 'pg_constraint'::regclass
              AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass)
              AND rn.nspname <> $11
            UNION
            SELECT DISTINCT d.refobjid, rn.nspname, ic.relname
            FROM pg_depend d
            JOIN pg_class ic ON ic.oid = d.objid AND ic.relkind = 'i'
            JOIN pg_namespace rn ON rn.oid = ic.relnamespace
            WHERE d.classid = 'pg_class'::regclass
              AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass)
              AND rn.nspname <> $12
            UNION
            -- columns (objsubid > 0) and typed tables (objsubid = 0) in
            -- other namespaces typed by or collated with our objects
            SELECT DISTINCT d.refobjid, rn.nspname,
                   rc.relname || CASE WHEN a.attname IS NULL THEN ''
                                      ELSE '.' || a.attname END
            FROM pg_depend d
            JOIN pg_class rc ON rc.oid = d.objid
            JOIN pg_namespace rn ON rn.oid = rc.relnamespace AND rn.nspname <> $13
            LEFT JOIN pg_attribute a ON a.attrelid = d.objid
                AND a.attnum = d.objsubid AND a.attnum > 0
                AND NOT a.attisdropped
            WHERE d.classid = 'pg_class'::regclass AND d.deptype = 'n'
              AND d.refclassid IN ('pg_type'::regclass, 'pg_collation'::regclass)
            UNION
            -- types (domains, ranges, base types) in other namespaces
            -- built on ours or calling our routines in defaults
            SELECT DISTINCT d.refobjid, tn.nspname, t.typname
            FROM pg_depend d
            JOIN pg_type t ON t.oid = d.objid
            JOIN pg_namespace tn ON tn.oid = t.typnamespace AND tn.nspname <> $14
            WHERE d.classid = 'pg_type'::regclass
              AND d.refclassid IN ('pg_type'::regclass, 'pg_proc'::regclass,
                                   'pg_collation'::regclass)
            UNION
            -- check constraints on other namespaces' domains whose
            -- expressions use our objects
            SELECT DISTINCT d.refobjid, dn.nspname,
                   dt.typname || '.' || c.conname
            FROM pg_depend d
            JOIN pg_constraint c ON c.oid = d.objid AND c.contypid <> 0
            JOIN pg_type dt ON dt.oid = c.contypid
            JOIN pg_namespace dn ON dn.oid = dt.typnamespace AND dn.nspname <> $15
            WHERE d.classid = 'pg_constraint'::regclass
              AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,
                                   'pg_class'::regclass)
            UNION
            -- policies on other namespaces' tables whose expressions
            -- use our objects
            SELECT DISTINCT d.refobjid, rn.nspname,
                   rc.relname || '.' || po.polname
            FROM pg_depend d
            JOIN pg_policy po ON po.oid = d.objid
            JOIN pg_class rc ON rc.oid = po.polrelid
            JOIN pg_namespace rn ON rn.oid = rc.relnamespace AND rn.nspname <> $16
            WHERE d.classid = 'pg_policy'::regclass
              AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,
                                   'pg_class'::regclass)
            UNION
            -- dependents in other namespaces on our text-search and
            -- operator objects — views, column defaults, and the
            -- families' own member rows (an external dictionary on our
            -- template, an external family member on our operator);
            -- no tgt join: byOid resolves in-namespace refs only
            SELECT DISTINCT d.refobjid, rn.nspname, rc.relname
            FROM pg_depend d
            JOIN pg_rewrite r ON r.oid = d.objid
            JOIN pg_class rc ON rc.oid = r.ev_class
            JOIN pg_namespace rn ON rn.oid = rc.relnamespace AND rn.nspname <> $17
            WHERE d.classid = 'pg_rewrite'::regclass
              AND d.refclassid IN ('pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                   'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                   'pg_operator'::regclass,'pg_opclass'::regclass,
                                   'pg_opfamily'::regclass)
            UNION
            SELECT DISTINCT d.refobjid, rn.nspname,
                   rc.relname || '.' || a.attname
            FROM pg_depend d
            JOIN pg_attrdef ad ON ad.oid = d.objid
            JOIN pg_attribute a ON a.attrelid = ad.adrelid AND a.attnum = ad.adnum
                AND NOT a.attisdropped
            JOIN pg_class rc ON rc.oid = ad.adrelid
            JOIN pg_namespace rn ON rn.oid = rc.relnamespace AND rn.nspname <> $18
            WHERE d.classid = 'pg_attrdef'::regclass AND d.deptype = 'n'
              AND d.refclassid IN ('pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                   'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                                   'pg_operator'::regclass,'pg_opclass'::regclass,
                                   'pg_opfamily'::regclass)
            UNION
            SELECT DISTINCT d.refobjid, tdn.nspname, td.dictname
            FROM pg_depend d
            JOIN pg_ts_dict td ON td.oid = d.objid
            JOIN pg_namespace tdn ON tdn.oid = td.dictnamespace AND tdn.nspname <> $19
            WHERE d.classid = 'pg_ts_dict'::regclass
              AND d.refclassid IN ('pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                                   'pg_ts_parser'::regclass,'pg_ts_template'::regclass)
            UNION
            -- config mappings record as pg_ts_config -> pg_ts_dict
            SELECT DISTINCT d.refobjid, cn.nspname, c.cfgname
            FROM pg_depend d
            JOIN pg_ts_config c ON c.oid = d.objid
            JOIN pg_namespace cn ON cn.oid = c.cfgnamespace AND cn.nspname <> $20
            WHERE d.classid = 'pg_ts_config'::regclass
              AND d.refclassid IN ('pg_ts_dict'::regclass,'pg_ts_parser'::regclass)
            UNION
            SELECT DISTINCT d.refobjid, ocn.nspname, oc.opcname
            FROM pg_depend d
            JOIN pg_opclass oc ON oc.oid = d.objid
            JOIN pg_namespace ocn ON ocn.oid = oc.opcnamespace AND ocn.nspname <> $21
            WHERE d.classid = 'pg_opclass'::regclass
              AND d.refclassid IN ('pg_opfamily'::regclass,'pg_operator'::regclass,
                                   'pg_type'::regclass)
            UNION
            SELECT DISTINCT d.refobjid, fn.nspname, f.opfname
            FROM pg_depend d
            JOIN pg_amop m ON m.oid = d.objid
            JOIN pg_opfamily f ON f.oid = m.amopfamily
            JOIN pg_namespace fn ON fn.oid = f.opfnamespace AND fn.nspname <> $22
            WHERE d.classid = 'pg_amop'::regclass
              AND d.refclassid IN ('pg_opfamily'::regclass,'pg_operator'::regclass)
            UNION
            SELECT DISTINCT d.refobjid, fn.nspname, f.opfname
            FROM pg_depend d
            JOIN pg_amproc m ON m.oid = d.objid
            JOIN pg_opfamily f ON f.oid = m.amprocfamily
            JOIN pg_namespace fn ON fn.oid = f.opfnamespace AND fn.nspname <> $23
            WHERE d.classid = 'pg_amproc'::regclass
              AND d.refclassid IN ('pg_opfamily'::regclass,'pg_proc'::regclass)
            UNION
            SELECT DISTINCT d.refobjid, ons.nspname, o.oprname
            FROM pg_depend d
            JOIN pg_operator o ON o.oid = d.objid
            JOIN pg_namespace ons ON ons.oid = o.oprnamespace AND ons.nspname <> $24
            WHERE d.classid = 'pg_operator'::regclass
              AND d.refclassid IN ('pg_operator'::regclass,'pg_proc'::regclass,
                                   'pg_type'::regclass)
            UNION
            SELECT DISTINCT d.refobjid, ofn.nspname, of.opfname
            FROM pg_depend d
            JOIN pg_opfamily of ON of.oid = d.objid
            JOIN pg_namespace ofn ON ofn.oid = of.opfnamespace AND ofn.nspname <> $25
            WHERE d.classid = 'pg_opfamily'::regclass
              AND d.refclassid = 'pg_opfamily'::regclass
            UNION
            -- casts not anchored to this namespace (by function or by
            -- either type — the loadOther anchor rule) that still use
            -- our types or routines
            SELECT DISTINCT d.refobjid,
                   coalesce(pn.nspname, sn.nspname, dtn.nspname, '-'),
                   'cast(' || ca.castsource::regtype::text || ' AS '
                          || ca.casttarget::regtype::text || ')'
            FROM pg_depend d
            JOIN pg_cast ca ON ca.oid = d.objid
            LEFT JOIN pg_proc p ON p.oid = ca.castfunc
            LEFT JOIN pg_namespace pn ON pn.oid = p.pronamespace
            JOIN pg_type st ON st.oid = ca.castsource
            JOIN pg_namespace sn ON sn.oid = st.typnamespace
            JOIN pg_type tt ON tt.oid = ca.casttarget
            JOIN pg_namespace dtn ON dtn.oid = tt.typnamespace
            WHERE d.classid = 'pg_cast'::regclass
              AND pn.nspname IS DISTINCT FROM $26
              AND sn.nspname IS DISTINCT FROM $27
              AND dtn.nspname IS DISTINCT FROM $28
            `;
        for (const row of await conn.query<Row>(sql, nsParams(this.ns, 28))) {
            const target = this.byOid(num(row.tgt));
            if (target === undefined) {
                continue;
            }
            const phantom = new ObjectId(ObjectKind.OTHER,
                    str(row.depns) + "." + str(row.depname));
            const o = this.obj(phantom);
            if (o.def === undefined) {
                o.def = new Definition("");
                o.attrs.set(Dialect.EXTERNAL_DEPENDENT_ATTRIBUTE, "true");
            }
            this.dep(phantom, target);
        }
    }

    /**
     * 3.1 dump(): fills `DUMP_SQL` — each object's complete
     * `${namespace}`-token create fragment list, joined by U+0000
     * (impossible in PostgreSQL text). A table's fragment folds its
     * serial-owned sequences and columns the way
     * `PgRender.createTableStatements` does; a sequence owned by a
     * table column of this snapshot and every object without a create
     * fragment get none — they surface as comment lines in the dump.
     */
    computeDumpSql(): void {
        for (const [id, o] of this.objects) {
            let statements: string[];
            switch (id.kind) {
                case ObjectKind.TABLE:
                    statements = this.tableDumpStatements(id, o);
                    break;
                case ObjectKind.FOREIGN_TABLE:
                    statements = this.foreignTableDumpStatements(id, o);
                    break;
                case ObjectKind.MATERIALIZED_VIEW: {
                    const create = o.attrs.get(CREATE_SQL);
                    statements = create === undefined ? []
                            : [o.attrs.get(POPULATED) !== "false"
                                    ? create
                                    // CREATE_SQL embeds pg_get_viewdef's
                                    // trailing ';' — WITH NO DATA must
                                    // follow the query, not the ';'
                                    : PgSql.dropTerminator(create)
                                            + " WITH NO DATA"];
                    break;
                }
                case ObjectKind.SEQUENCE:
                    statements = this.foldedSequence(id)
                            ? []
                            : fragments(o, CREATE_SQL, OWNED_SQL);
                    break;
                case ObjectKind.PRIVILEGES:
                    statements = privilegeDumpStatements(o);
                    break;
                default:
                    statements = fragments(o, CREATE_SQL, POST_SQL, OWNED_SQL);
                    break;
            }
            if (statements.length > 0) {
                o.attrs.set(DUMP_SQL, statements.join("\u0000"));
            }
        }
    }

    /**
     * The CREATE FOREIGN TABLE fragment of
     * `PgRender.createForeignTableStatements` in unbound token
     * form: columns folded, then `SERVER`/`OPTIONS`.
     */
    private foreignTableDumpStatements(id: ObjectId, table: Obj): string[] {
        const columns: [ObjectId, Obj][] = [];
        for (const [k, v] of this.objects) {
            if (k.kind === ObjectKind.COLUMN && k.parent === id.name) {
                columns.push([k, v]);
            }
        }
        columns.sort((a, b) => Number(a[1].attrs.get(ATTNUM) ?? "0")
                - Number(b[1].attrs.get(ATTNUM) ?? "0"));
        let create = "CREATE FOREIGN TABLE "
                + this.tableRef(id.name) + " (\n"
                + columns.map(c => "    " + c[1].attrs.get(COLUMN_SQL))
                        .join(",\n")
                + "\n) SERVER "
                + PgIdentifiers.quote(table.attrs.get(FT_SERVER)!);
        const options = table.attrs.get(FT_OPTIONS) ?? "";
        if (options !== "") {
            create += " OPTIONS (" + options + ")";
        }
        return [create];
    }

    /** A sequence folded into its owner table's create fragment (A.5). */
    private foldedSequence(id: ObjectId): boolean {
        for (const dep of this.deps.get(id) ?? []) {
            if (dep.kind === ObjectKind.TABLE && this.objects.has(dep)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The folded CREATE TABLE fragment list of `createTableStatements`
     * in unbound token form: owned sequences first, the CREATE TABLE with
     * every column, then replica-identity/row-security and OWNED BY alters.
     */
    private tableDumpStatements(id: ObjectId, table: Obj): string[] {
        const foldedSeqs: [ObjectId, Obj][] = [];
        for (const [k, v] of this.objects) {
            if (k.kind === ObjectKind.SEQUENCE
                    && (this.deps.get(k)?.has(id) ?? false)) {
                foldedSeqs.push([k, v]);
            }
        }
        foldedSeqs.sort((a, b) => ObjectIds.ORDER(a[0], b[0]));
        const columns: Obj[] = [];
        for (const [k, v] of this.objects) {
            if (k.kind === ObjectKind.COLUMN && k.parent === id.name) {
                columns.push(v);
            }
        }
        columns.sort((a, b) => Number(a.attrs.get(ATTNUM) ?? "0")
                - Number(b.attrs.get(ATTNUM) ?? "0"));
        const statements: string[] = [];
        for (const [, seq] of foldedSeqs) {
            statements.push(seq.attrs.get(CREATE_SQL)!);
        }
        const canonical = table.def?.canonical ?? "";
        let create = "CREATE "
                + (canonicalField(canonical, "relpersistence") === "u"
                        ? "UNLOGGED " : "")
                + "TABLE " + this.tableRef(id.name) + " (\n"
                + columns.map(c => "    " + c.attrs.get(COLUMN_SQL))
                        .join(",\n")
                + "\n)";
        const options = table.attrs.get(TABLE_OPTIONS) ?? "";
        if (options !== "") {
            create += " WITH (" + options + ")";
        }
        statements.push(create);
        const replident = table.attrs.get(REPLIDENT) === "f" ? "FULL"
                : table.attrs.get(REPLIDENT) === "n" ? "NOTHING" : null;
        if (replident !== null) {
            statements.push("ALTER TABLE " + this.tableRef(id.name)
                    + " REPLICA IDENTITY " + replident);
        }
        if (canonicalField(canonical, "rls") === "true") {
            statements.push("ALTER TABLE " + this.tableRef(id.name)
                    + " ENABLE ROW LEVEL SECURITY");
        }
        if (canonicalField(canonical, "forcerls") === "true") {
            statements.push("ALTER TABLE " + this.tableRef(id.name)
                    + " FORCE ROW LEVEL SECURITY");
        }
        for (const [, seq] of foldedSeqs) {
            const owned = seq.attrs.get(OWNED_SQL);
            if (owned !== undefined) {
                statements.push(owned);
            }
        }
        return statements;
    }

    private tableRef(name: string): string {
        return "${namespace}." + PgIdentifiers.quote(name);
    }

    private seqRef(id: ObjectId): string {
        return "${namespace}." + PgIdentifiers.quote(id.name);
    }

    snapshot(): SchemaSnapshot {
        const out = new ObjectMap<SchemaObject>();
        for (const [id, o] of this.objects) {
            out.set(id, new SchemaObject(id,
                    o.def ?? new Definition(""),
                    this.deps.get(id) ?? new ObjectSet(), o.attrs));
        }
        return SchemaSnapshot.of(out);
    }
}
