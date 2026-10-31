import { PlanningException } from "@schema-reconciler/core";
import { ChangeAction, ChangeSafety, Definition } from "@schema-reconciler/core";
import type { Difference, ObjectId } from "@schema-reconciler/core";
import { ObjectIds, ObjectKind, PlannedChange } from "@schema-reconciler/core";
import type { SchemaObject, SchemaSnapshot } from "@schema-reconciler/core";
import * as PgCatalog from "./PgCatalog.ts";
import * as PgIdentifiers from "./PgIdentifiers.ts";
import * as PgSql from "./PgSql.ts";

const TOKEN = "${namespace}";

export function render(difference: Difference, desired: SchemaSnapshot,
        targetNamespace: string): PlannedChange[] {
    const id = difference.id;
    const want = desired.objects.get(id)!;
    switch (difference.type) {
        case "MISSING":
            return missing(id, want, desired, targetNamespace);
        case "DRIFTED":
            return drifted(id, want, difference, desired, targetNamespace);
        default:
            throw new PlanningException("difference " + difference.id
                    + " of type " + difference.type + " is not renderable");
    }
}

// ---- MISSING ---------------------------------------------------------

function missing(id: ObjectId, want: SchemaObject,
        desired: SchemaSnapshot, ns: string): PlannedChange[] {
    switch (id.kind) {
        case ObjectKind.TABLE:
            return [new PlannedChange(0, id, ChangeAction.CREATE,
                    ChangeSafety.ADDITIVE, createTableStatements(id, desired, ns),
                    want.definition, "missing " + id)];
        case ObjectKind.FOREIGN_TABLE:
            return [new PlannedChange(0, id, ChangeAction.CREATE,
                    ChangeSafety.ADDITIVE, createForeignTableStatements(id, desired, ns),
                    want.definition, "missing " + id)];
        case ObjectKind.COLUMN:
            return missingColumn(id, want, desired, ns);
        case ObjectKind.PRIMARY_KEY:
        case ObjectKind.UNIQUE:
        case ObjectKind.FOREIGN_KEY:
        case ObjectKind.CHECK:
        case ObjectKind.EXCLUSION:
        case ObjectKind.INDEX:
        case ObjectKind.VIEW:
        case ObjectKind.TRIGGER:
        case ObjectKind.TYPE:
        case ObjectKind.DOMAIN:
        case ObjectKind.POLICY:
        case ObjectKind.EXTENSION:
        case ObjectKind.STATISTICS:
        case ObjectKind.RULE:
        case ObjectKind.COLLATION:
        case ObjectKind.CONVERSION:
        case ObjectKind.PARTITION:
        case ObjectKind.PUBLICATION:
        case ObjectKind.CAST:
        case ObjectKind.TRANSFORM:
            return [additive(id, want, ns, "missing " + id)];
        case ObjectKind.MATERIALIZED_VIEW:
            return [new PlannedChange(0, id, ChangeAction.CREATE,
                    ChangeSafety.ADDITIVE, matviewCreateStatements(want, ns),
                    want.definition, "missing " + id)];
        case ObjectKind.COMMENT:
            return [new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.REBUILD, [bindObj(want, PgCatalog.CREATE_SQL, ns)],
                    want.definition, "missing comment on " + id)];
        case ObjectKind.SECURITY_LABEL:
            return [new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.REBUILD, [bindObj(want, PgCatalog.CREATE_SQL, ns)],
                    want.definition, "missing label on " + id)];
        case ObjectKind.OWNERSHIP:
            return [new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.REBUILD, [bindObj(want, PgCatalog.CREATE_SQL, ns)],
                    want.definition, "missing ownership on " + id)];
        case ObjectKind.PRIVILEGES:
        case ObjectKind.DEFAULT_PRIVILEGES:
            return privilegesChanges(id, want, [], ns, "missing privileges on " + id);
        case ObjectKind.SEQUENCE: {
            const statements: string[] = [];
            statements.push(bindObj(want, PgCatalog.CREATE_SQL, ns));
            const owned = want.attributes.get(PgCatalog.OWNED_SQL);
            if (owned !== undefined) {
                statements.push(bind(owned, ns));
            }
            return [new PlannedChange(0, id, ChangeAction.CREATE,
                    ChangeSafety.ADDITIVE, statements, want.definition,
                    "missing " + id)];
        }
        case ObjectKind.ROUTINE:
        case ObjectKind.AGGREGATE:
        case ObjectKind.TEXT_SEARCH_TEMPLATE:
        case ObjectKind.TEXT_SEARCH_PARSER:
        case ObjectKind.TEXT_SEARCH_DICTIONARY:
        case ObjectKind.TEXT_SEARCH_CONFIGURATION:
        case ObjectKind.OPERATOR:
        case ObjectKind.OPERATOR_CLASS:
        case ObjectKind.OPERATOR_FAMILY:
            return [additive(id, want, ns, "missing " + id)];
        default:
            return [];
    }
}

/** CREATE TABLE folding in every desired column plus serial-owned sequences (A.6, 5.8.4). */
function createTableStatements(table: ObjectId, desired: SchemaSnapshot,
        ns: string): string[] {
    const columns = [...desired.objects.values()]
            .filter(o => o.id.kind === ObjectKind.COLUMN
                    && o.id.parent === table.name)
            .sort((a, b) => Number(a.attributes.get(PgCatalog.ATTNUM) ?? "0")
                    - Number(b.attributes.get(PgCatalog.ATTNUM) ?? "0"));
    const sequences = [...desired.objects.values()]
            .filter(o => o.id.kind === ObjectKind.SEQUENCE
                    && o.dependsOn.has(table))
            .sort((a, b) => ObjectIds.ORDER(a.id, b.id));

    const statements: string[] = [];
    for (const s of sequences) {
        statements.push(bindObj(s, PgCatalog.CREATE_SQL, ns));
    }
    let create = "CREATE "
            + (tablePersistence(desired, table) === "u" ? "UNLOGGED " : "")
            + "TABLE "
            + PgIdentifiers.quote(ns) + "."
            + PgIdentifiers.quote(table.name) + " (\n"
            + columns.map(c => "    " + c.attributes.get(PgCatalog.COLUMN_SQL))
                    .join(",\n")
            + "\n)";
    const tableAttrs = desired.objects.get(table)!.attributes;
    const partkey = tableAttrs.get(PgCatalog.PARTKEY);
    if (partkey !== undefined) {
        create += " PARTITION BY " + partkey;
    }
    const options = tableAttrs.get(PgCatalog.TABLE_OPTIONS) ?? "";
    if (options !== "") {
        create += " WITH (" + options + ")";
    }
    statements.push(bind(create, ns));
    // 'i' (USING INDEX) is applied after the index exists, see PgCatalog POST_SQL
    const replident = tableAttrs.get(PgCatalog.REPLIDENT) === "f" ? "FULL"
            : tableAttrs.get(PgCatalog.REPLIDENT) === "n" ? "NOTHING" : null;
    if (replident !== null) {
        statements.push(bind("ALTER TABLE " + TOKEN + "." + PgIdentifiers.quote(table.name)
                + " REPLICA IDENTITY " + replident, ns));
    }
    // CREATE TABLE cannot set row-level security; enabled flags follow
    const tableDef = columnFields(
            desired.objects.get(table)!.definition.canonical);
    if (tableDef.get("rls") === "true") {
        statements.push(bind("ALTER TABLE " + TOKEN + "." + PgIdentifiers.quote(table.name)
                + " ENABLE ROW LEVEL SECURITY", ns));
    }
    if (tableDef.get("forcerls") === "true") {
        statements.push(bind("ALTER TABLE " + TOKEN + "." + PgIdentifiers.quote(table.name)
                + " FORCE ROW LEVEL SECURITY", ns));
    }
    for (const s of sequences) {
        const owned = s.attributes.get(PgCatalog.OWNED_SQL);
        if (owned !== undefined) {
            statements.push(bind(owned, ns));
        }
    }
    return statements;
}

/** CREATE FOREIGN TABLE folding in every desired column, then SERVER/OPTIONS (A.6, 5.8.4). */
function createForeignTableStatements(table: ObjectId,
        desired: SchemaSnapshot, ns: string): string[] {
    const columns = [...desired.objects.values()]
            .filter(o => o.id.kind === ObjectKind.COLUMN
                    && o.id.parent === table.name)
            .sort((a, b) => Number(a.attributes.get(PgCatalog.ATTNUM) ?? "0")
                    - Number(b.attributes.get(PgCatalog.ATTNUM) ?? "0"));
    const attrs = desired.objects.get(table)!.attributes;
    let create = "CREATE FOREIGN TABLE "
            + PgIdentifiers.quote(ns) + "."
            + PgIdentifiers.quote(table.name) + " (\n"
            + columns.map(c => "    " + c.attributes.get(PgCatalog.COLUMN_SQL))
                    .join(",\n")
            + "\n) SERVER "
            + PgIdentifiers.quote(attrs.get(PgCatalog.FT_SERVER)!);
    const options = attrs.get(PgCatalog.FT_OPTIONS) ?? "";
    if (options !== "") {
        create += " OPTIONS (" + options + ")";
    }
    return [bind(create, ns)];
}

/**
 * A.6: the materialized-view create — `WITH NO DATA` when the
 * desired object is unpopulated, so an intentional empty view converges
 * instead of being refreshed full.
 */
function matviewCreateStatements(want: SchemaObject, ns: string): string[] {
    const create = want.attributes.get(PgCatalog.CREATE_SQL)!;
    const populated = want.attributes.get(PgCatalog.POPULATED) !== "false";
    return [bind(populated ? create
            : PgSql.dropTerminator(create) + " WITH NO DATA", ns)];
}

function tablePersistence(desired: SchemaSnapshot, table: ObjectId): string {
    const t = desired.objects.get(table);
    if (t === undefined) {
        return "p";
    }
    const def = t.definition.canonical;
    const idx = def.indexOf("relpersistence=");
    return idx < 0 ? "p" : def.substring(idx + 15, idx + 16);
}

/** A.6: ADD COLUMN — additive for nullable or constant-default columns, manual otherwise. */
function missingColumn(id: ObjectId, want: SchemaObject,
        desired: SchemaSnapshot, ns: string): PlannedChange[] {
    const parent = id.parent ?? "";
    // a partition column is inherited — ADD COLUMN runs on the
    // partitioned root and propagates; IF NOT EXISTS absorbs the
    // parent's own identical change
    const routed = partitionParent(parent, desired);
    const alter = "ALTER TABLE " + PgIdentifiers.quote(ns) + "."
            + PgIdentifiers.quote(routed ?? parent)
            + " ADD COLUMN IF NOT EXISTS "
            + want.attributes.get(PgCatalog.COLUMN_SQL);
    const notnull = want.attributes.get(PgCatalog.NOTNULL) === "true";
    if (!notnull) {
        return [new PlannedChange(0, id, ChangeAction.CREATE,
                ChangeSafety.ADDITIVE, [bind(alter, ns)],
                want.definition, "missing nullable column " + id.name)];
    }
    const defexpr = want.attributes.get(PgCatalog.DEFAULT);
    if (defexpr !== undefined && PgIdentifiers.isConstantDefault(defexpr)) {
        return [new PlannedChange(0, id, ChangeAction.CREATE,
                ChangeSafety.ADDITIVE, [bind(alter, ns)],
                want.definition,
                "missing NOT NULL column " + id.name + " with a constant default")];
    }
    return [new PlannedChange(0, id, ChangeAction.ALTER,
            ChangeSafety.DESTRUCTIVE, [], undefined,
            "missing NOT NULL column " + id.name + " requires a manual change")];
}

// ---- DRIFTED ----------------------------------------------------------

/**
 * A.6: a materialized view holds stored rows, so its recreate is
 * `DESTRUCTIVE` (5.6): `DROP`, `CREATE` and, unless
 * the desired object is unpopulated, a `REFRESH`.
 */
function driftedMatview(id: ObjectId, want: SchemaObject, ns: string): PlannedChange[] {
    const statements = [...matviewCreateStatements(want, ns)];
    if (want.attributes.get(PgCatalog.POPULATED) !== "false") {
        statements.push(bind("REFRESH MATERIALIZED VIEW " + TOKEN + "."
                + PgIdentifiers.quote(id.name), ns));
    }
    const reason = "drifted " + id + " (stored rows are dropped)";
    return [
        new PlannedChange(0, id, ChangeAction.DROP, ChangeSafety.DESTRUCTIVE,
                [bindObj(want, PgCatalog.DROP_SQL, ns)], undefined, reason),
        new PlannedChange(0, id, ChangeAction.CREATE, ChangeSafety.DESTRUCTIVE,
                statements, want.definition, reason)];
}

/**
 * A.6: an enum's labels append in place — a pure in-order suffix
 * addition renders one `ALTER TYPE ... ADD VALUE` per new label
 * (`ADDITIVE`); a removed or reordered label cannot be altered
 * in place and stays a manual change (`DESTRUCTIVE`).
 */
function driftedEnum(id: ObjectId, want: SchemaObject,
        difference: Difference, ns: string): PlannedChange[] {
    const have = enumLabels(difference.actual?.canonical ?? "");
    const wantLabels = enumLabels(want.definition.canonical);
    if (wantLabels.length > have.length
            && arrayEquals(have, wantLabels.slice(0, have.length))) {
        const qName = TOKEN + "." + PgIdentifiers.quote(id.name);
        const statements = wantLabels.slice(have.length)
                .map(l => "ALTER TYPE " + qName + " ADD VALUE " + literal(l))
                .map(s => bind(s, ns));
        return [new PlannedChange(0, id, ChangeAction.ALTER, ChangeSafety.ADDITIVE,
                statements, want.definition,
                "drifted " + id + " (labels appended in order)")];
    }
    return [new PlannedChange(0, id, ChangeAction.ALTER,
            ChangeSafety.DESTRUCTIVE, [], undefined,
            "drifted " + id + " (removed or reordered labels) requires a manual change")];
}

/** A single-quoted SQL literal for an enum label. */
function literal(value: string): string {
    return "'" + value.replaceAll("'", "''") + "'";
}

/**
 * A.6: a `TABLE` drift limited to the row-level-security flags —
 * `relrowsecurity` and `relforcerowsecurity` — renders an
 * in-place `ALTER TABLE ... ENABLE|DISABLE|FORCE|NO FORCE ROW
 * LEVEL SECURITY` (`REBUILD`); any other table definition change
 * stays a manual `DESTRUCTIVE` change as before.
 */
function driftedTable(id: ObjectId, want: SchemaObject,
        difference: Difference, ns: string): PlannedChange[] {
    const have = difference.actual?.canonical ?? null;
    if (have === null) {
        return manual(id, "drifted " + id + " requires a manual change");
    }
    const wantFields = columnFields(want.definition.canonical);
    const haveFields = columnFields(have);
    const qTable = "ALTER TABLE " + TOKEN + "." + PgIdentifiers.quote(id.name);
    const statements: string[] = [];
    const wantRls = wantFields.get("rls") === "true";
    const haveRls = haveFields.get("rls") === "true";
    if (wantRls !== haveRls) {
        statements.push(qTable + (wantRls ? " ENABLE" : " DISABLE") + " ROW LEVEL SECURITY");
    }
    const wantForce = wantFields.get("forcerls") === "true";
    const haveForce = haveFields.get("forcerls") === "true";
    if (wantForce !== haveForce) {
        statements.push(qTable + (wantForce ? " FORCE" : " NO FORCE") + " ROW LEVEL SECURITY");
    }
    const rest = new Map(wantFields);
    rest.delete("rls");
    rest.delete("forcerls");
    const haveRest = new Map(haveFields);
    haveRest.delete("rls");
    haveRest.delete("forcerls");
    if (!mapsEqual(rest, haveRest)) {
        return manual(id, "drifted " + id + " requires a manual change");
    }
    return [new PlannedChange(0, id, ChangeAction.ALTER, ChangeSafety.REBUILD,
            statements.map(s => bind(s, ns)),
            want.definition, "drifted " + id + " (row-level security flag)")];
}

function manual(id: ObjectId, reason: string): PlannedChange[] {
    return [new PlannedChange(0, id, ChangeAction.ALTER,
            ChangeSafety.DESTRUCTIVE, [], undefined, reason)];
}

/**
 * A.6: domain drift decomposed like column drift — `SET`/`DROP DEFAULT`, `DROP NOT NULL` and `DROP CONSTRAINT` are
 * safe in place (`REBUILD`); `SET NOT NULL` and `ADD
 * CONSTRAINT` validate existing values, so they render as a separate
 * `DESTRUCTIVE` `ALTER` change; a base-type or collation
 * change cannot alter in place and stays manual.
 */
function driftedDomain(id: ObjectId, want: SchemaObject,
        difference: Difference, ns: string): PlannedChange[] {
    const have = difference.actual?.canonical ?? null;
    if (have === null) {
        return manual(id, "drifted " + id + " requires a manual change");
    }
    const wantFields = columnFields(want.definition.canonical);
    const haveFields = columnFields(have);
    for (const key of ["basetype", "collation"]) {
        if (wantFields.get(key) !== haveFields.get(key)) {
            return manual(id, "drifted " + id + " (" + key + " changed)"
                    + " requires a manual change");
        }
    }
    const alter = "ALTER DOMAIN " + TOKEN + "." + PgIdentifiers.quote(id.name) + " ";
    const safe: string[] = [];
    const validating: string[] = [];
    const merged = new Map(wantFields);
    const wantDefault = wantFields.get("default");
    if (wantDefault !== haveFields.get("default")) {
        safe.push(alter + (wantDefault !== undefined
                ? "SET DEFAULT " + wantDefault : "DROP DEFAULT"));
    }
    const wantNotnull = wantFields.get("notnull") === "true";
    const haveNotnull = haveFields.get("notnull") === "true";
    if (haveNotnull && !wantNotnull) {
        safe.push(alter + "DROP NOT NULL");
    } else if (wantNotnull && !haveNotnull) {
        validating.push(alter + "SET NOT NULL");
        merged.set("notnull", haveFields.get("notnull")!);
    }
    const wantChecks = domainChecks(wantFields);
    const haveChecks = domainChecks(haveFields);
    for (const name of haveChecks.keys()) {
        if (!wantChecks.has(name)) {
            safe.push(alter + "DROP CONSTRAINT " + PgIdentifiers.quote(name));
        }
    }
    for (const [name, value] of wantChecks) {
        if (haveChecks.has(name) && haveChecks.get(name) !== value) {
            safe.push(alter + "DROP CONSTRAINT " + PgIdentifiers.quote(name));
        }
        if (haveChecks.get(name) !== value) {
            validating.push(alter + "ADD CONSTRAINT " + PgIdentifiers.quote(name)
                    + " " + value);
            merged.delete("check." + name);
        }
    }
    if (safe.length === 0 && validating.length === 0) {
        return manual(id, "drifted " + id + " requires a manual change");
    }
    const changes: PlannedChange[] = [];
    if (safe.length > 0) {
        const expected = validating.length === 0
                ? want.definition
                : new Definition([...merged.entries()]
                        .map(([k, v]) => k + "=" + v).join(";"));
        changes.push(new PlannedChange(0, id, ChangeAction.ALTER, ChangeSafety.REBUILD,
                safe.map(s => bind(s, ns)),
                expected, "drifted " + id));
    }
    if (validating.length > 0) {
        changes.push(new PlannedChange(0, id, ChangeAction.ALTER,
                ChangeSafety.DESTRUCTIVE,
                validating.map(s => bind(s, ns)),
                want.definition,
                "drifted " + id + " (validates existing values)"));
    }
    return changes;
}

/** The `check.<name>=<def>` entries of a domain canonical. */
function domainChecks(fields: Map<string, string>): Map<string, string> {
    const checks = new Map<string, string>();
    for (const [k, v] of fields) {
        if (k.startsWith("check.")) {
            checks.set(k.substring(6), v);
        }
    }
    return checks;
}

function drifted(id: ObjectId, want: SchemaObject,
        difference: Difference, desired: SchemaSnapshot,
        ns: string): PlannedChange[] {
    switch (id.kind) {
        case ObjectKind.COLUMN:
            return driftedColumn(id, want, difference, desired, ns);
        case ObjectKind.TABLE:
            return driftedTable(id, want, difference, ns);
        case ObjectKind.PRIMARY_KEY:
        case ObjectKind.UNIQUE:
        case ObjectKind.FOREIGN_KEY:
        case ObjectKind.CHECK:
        case ObjectKind.EXCLUSION:
        case ObjectKind.INDEX:
        case ObjectKind.VIEW:
        case ObjectKind.TRIGGER:
        case ObjectKind.POLICY:
        case ObjectKind.STATISTICS:
        case ObjectKind.RULE:
        case ObjectKind.COLLATION:
        case ObjectKind.CONVERSION:
        case ObjectKind.PUBLICATION:
        case ObjectKind.CAST:
        case ObjectKind.TRANSFORM:
            return recreate(id, want, ns, "drifted " + id);
        // a bound change re-homes rows — DESTRUCTIVE, not REBUILD
        case ObjectKind.PARTITION:
            return [
                new PlannedChange(0, id, ChangeAction.DROP, ChangeSafety.DESTRUCTIVE,
                        [bindObj(want, PgCatalog.DROP_SQL, ns)],
                        undefined, "drifted " + id),
                new PlannedChange(0, id, ChangeAction.CREATE, ChangeSafety.DESTRUCTIVE,
                        [bindObj(want, PgCatalog.CREATE_SQL, ns)],
                        want.definition, "drifted " + id)];
        // no local data — a server/options change is a plain drop+create
        case ObjectKind.FOREIGN_TABLE:
            return [
                new PlannedChange(0, id, ChangeAction.DROP, ChangeSafety.REBUILD,
                        [bindObj(want, PgCatalog.DROP_SQL, ns)],
                        undefined, "drifted " + id),
                new PlannedChange(0, id, ChangeAction.CREATE, ChangeSafety.REBUILD,
                        createForeignTableStatements(id, desired, ns),
                        want.definition, "drifted " + id)];
        case ObjectKind.MATERIALIZED_VIEW:
            return driftedMatview(id, want, ns);
        // a composite's attribute drift and a range's pg_range drift
        // drop and recreate the type — no in-place recipe exists;
        // enums keep the label-aware path
        case ObjectKind.TYPE:
            return want.definition.canonical.startsWith("typtype=e")
                    ? driftedEnum(id, want, difference, ns)
                    : recreate(id, want, ns, "drifted " + id);
        case ObjectKind.DOMAIN:
            return driftedDomain(id, want, difference, ns);
        case ObjectKind.COMMENT:
            return [new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.REBUILD, [bindObj(want, PgCatalog.CREATE_SQL, ns)],
                    want.definition, "drifted comment on " + id)];
        case ObjectKind.SECURITY_LABEL:
            return [new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.REBUILD, [bindObj(want, PgCatalog.CREATE_SQL, ns)],
                    want.definition, "drifted label on " + id)];
        case ObjectKind.OWNERSHIP:
            return [new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.REBUILD, [bindObj(want, PgCatalog.CREATE_SQL, ns)],
                    want.definition, "drifted ownership on " + id)];
        case ObjectKind.PRIVILEGES:
        case ObjectKind.DEFAULT_PRIVILEGES:
            return privilegesChanges(id, want,
                    aclTokens(difference.actual?.canonical ?? ""),
                    ns, "drifted privileges on " + id);
        case ObjectKind.SEQUENCE: {
            const statements: string[] = [];
            const options = want.attributes.get(PgCatalog.OPTIONS_SQL);
            statements.push(bind("ALTER SEQUENCE " + seqRef(id) + " " + options, ns));
            const owned = want.attributes.get(PgCatalog.OWNED_SQL);
            if (owned !== undefined) {
                statements.push(bind(owned, ns));
            }
            // persistence is not an ALTER SEQUENCE option — it has its own
            // SET LOGGED/UNLOGGED form (since PostgreSQL 10)
            const wantPersist = columnFields(want.definition.canonical)
                    .get("relpersistence") ?? "p";
            const havePersist = columnFields(difference.actual?.canonical ?? "")
                    .get("relpersistence") ?? "p";
            if (wantPersist !== havePersist) {
                statements.push(bind("ALTER SEQUENCE " + seqRef(id)
                        + (wantPersist === "u" ? " SET UNLOGGED" : " SET LOGGED"), ns));
            }
            return [new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.DESTRUCTIVE, statements, want.definition,
                    "drifted " + id)];
        }
        case ObjectKind.ROUTINE: {
            const haveDef = difference.actual?.canonical ?? "";
            const wantProcedure =
                    (want.attributes.get(PgCatalog.PROKIND) ?? "f") === "p";
            const haveProcedure = haveDef.startsWith(
                    "CREATE OR REPLACE PROCEDURE");
            // a procedure has no RETURNS clause to compare; a function
            // alters in place only while its return type is unchanged
            const inPlace = wantProcedure
                    ? haveProcedure
                    : !haveProcedure &&
                            want.attributes.get(PgCatalog.RETURN_TYPE)
                                    === returnTypeOf(haveDef);
            if (inPlace) {
                return [new PlannedChange(0, id, ChangeAction.ALTER,
                        ChangeSafety.REBUILD, [bindObj(want, PgCatalog.CREATE_SQL, ns)],
                        want.definition, "drifted " + id)];
            }
            return recreate(id, want, ns, "drifted " + id + " (return type changed)");
        }
        // aggregates have no CREATE OR REPLACE form — every option
        // change recreates
        case ObjectKind.AGGREGATE:
        // text-search objects compose their canonical from the catalog
        // row — any field drift recreates (no OR REPLACE form exists)
        case ObjectKind.TEXT_SEARCH_TEMPLATE:
        case ObjectKind.TEXT_SEARCH_PARSER:
        case ObjectKind.TEXT_SEARCH_DICTIONARY:
        case ObjectKind.TEXT_SEARCH_CONFIGURATION:
        case ObjectKind.OPERATOR:
        case ObjectKind.OPERATOR_CLASS:
        case ObjectKind.OPERATOR_FAMILY:
            return recreate(id, want, ns, "drifted " + id);
        default:
            return [new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.DESTRUCTIVE, [], undefined,
                    "drifted " + id + " requires a manual change")];
    }
}

/**
 * The GRANT/REVOKE statement list converging a PRIVILEGES object's acl
 * token set onto the desired one (`grantee:PRIVILEGE[*]`, A.5).
 * Implicit owner rights (`<owner>` grantees) are skipped — they
 * are not grants and cannot be revoked. A grant-option-only difference
 * renders `REVOKE GRANT OPTION FOR` so the base privilege stays.
 */
function privilegesChanges(id: ObjectId, want: SchemaObject,
        actual: readonly string[], ns: string, reason: string): PlannedChange[] {
    const target = bindObj(want, PgCatalog.GRANT_TARGET, ns);
    // a column-level grant parenthesizes its column after the privilege
    // word: GRANT SELECT ("c") ON TABLE t — absent on object-level grants
    const column = want.attributes.get(PgCatalog.GRANT_COLUMN);
    const colClause = column === undefined ? "" : " (" + bind(column, ns) + ")";
    // a scoped default-privileges object prefixes every statement with
    // ALTER DEFAULT PRIVILEGES FOR ROLE "r" IN SCHEMA "ns"
    const prefix = want.attributes.get(PgCatalog.GRANT_PREFIX);
    const pfx = prefix === undefined ? "" : bind(prefix, ns) + " ";
    const desired = aclTokens(want.definition.canonical);
    const stmts: string[] = [];
    for (const tok of desired) {
        const g = grantee(tok);
        if (g === "<owner>" || actual.includes(tok)) {
            continue;
        }
        // the base privilege is held if the actual side has it with or
        // without the grant option
        const bare = tokenSansOption(tok);
        const held = actual.includes(bare) || actual.includes(bare + "*");
        // missing entirely, or held without the grant option — a GRANT
        // upgrades a bare grant to grantable either way
        if (!held || grantable(tok)) {
            stmts.push(pfx + "GRANT " + privilege(tok) + colClause + " ON " + target
                    + " TO " + granteeSql(g)
                    + (grantable(tok) ? " WITH GRANT OPTION" : ""));
        }
    }
    for (const tok of actual) {
        const g = grantee(tok);
        if (g === "<owner>" || desired.includes(tok)) {
            continue;
        }
        // the privilege is wanted when the desired side holds it in
        // either form — bare or grantable
        const bare = tokenSansOption(tok);
        const wanted = desired.includes(bare) || desired.includes(bare + "*");
        if (grantable(tok) && desired.includes(bare)) {
            // same grant, keep the base privilege — drop only the option
            stmts.push(pfx + "REVOKE GRANT OPTION FOR " + privilege(tok) + colClause
                    + " ON " + target + " FROM " + granteeSql(g));
        } else if (!wanted) {
            stmts.push(pfx + "REVOKE " + privilege(tok) + colClause + " ON " + target
                    + " FROM " + granteeSql(g));
        }
        // else: held bare, wanted grantable — the GRANT above upgrades it
    }
    if (stmts.length === 0) {
        return [];
    }
    return [new PlannedChange(0, id, ChangeAction.ALTER, ChangeSafety.REBUILD,
            [...stmts], want.definition, reason)];
}

/** The `grantee:PRIVILEGE[*]` token list of a `privileges=` canonical. */
function aclTokens(canonical: string): string[] {
    let s = canonical;
    if (s.startsWith("privileges=")) {
        s = s.substring("privileges=".length);
    }
    return s === "" ? [] : s.split(",");
}

/** The grantee of a `grantee:PRIVILEGE[*]` token — the last colon splits it. */
function grantee(token: string): string {
    return token.substring(0, token.lastIndexOf(":"));
}

/** The privilege word of a `grantee:PRIVILEGE[*]` token. */
function privilege(token: string): string {
    const p = token.substring(token.lastIndexOf(":") + 1);
    return p.endsWith("*") ? p.substring(0, p.length - 1) : p;
}

function grantable(token: string): boolean {
    return token.endsWith("*");
}

/** The same token without a grant-option marker. */
function tokenSansOption(token: string): string {
    return token.endsWith("*") ? token.substring(0, token.length - 1) : token;
}

/** `PUBLIC` stays a keyword; a role name is quoted. */
function granteeSql(g: string): string {
    return g === "PUBLIC" ? g : PgIdentifiers.quote(g);
}

/**
 * A.6: decomposes column drift by what each alteration can do to existing
 * rows (5.6 rule 1). Reversible metadata alterations — a widening `TYPE` from the allowlist, default changes and `DROP NOT NULL` —
 * become one `REBUILD` change. `SET NOT NULL` scans and fails
 * on violating rows, so it becomes a separate `DESTRUCTIVE` change
 * (rendered, gated by policy). Generated, identity and collation changes
 * and non-widening type changes stay manual (no statements).
 */
function driftedColumn(id: ObjectId, want: SchemaObject,
        difference: Difference, desired: SchemaSnapshot,
        ns: string): PlannedChange[] {
    const haveCanonical = difference.actual?.canonical ?? null;
    if (haveCanonical === null) {
        return manualColumn(id, "drifted " + id + " requires a manual change");
    }
    const wantFields = columnFields(want.definition.canonical);
    const haveFields = columnFields(haveCanonical);
    for (const key of ["generated", "identity", "identityOptions", "collation"]) {
        if (wantFields.get(key) !== haveFields.get(key)) {
            return manualColumn(id, "drifted " + id + " (" + key + " changed)");
        }
    }
    const alter = "ALTER TABLE " + TOKEN + "."
            + PgIdentifiers.quote(id.parent ?? "")
            + " ALTER COLUMN " + PgIdentifiers.quote(id.name) + " ";
    const safe: string[] = [];
    const wantType = wantFields.get("type");
    if (wantType !== haveFields.get("type")) {
        if (wantType === undefined || haveFields.get("type") === undefined
                || !widens(haveFields.get("type")!, wantType)) {
            return manualColumn(id, "drifted " + id + " (type change "
                    + haveFields.get("type") + " -> " + wantType
                    + " is not a widening)");
        }
        // an inherited column's TYPE runs on the partitioned root and
        // propagates — the parent's own identical change does it first
        const routed = partitionParent(id.parent ?? "", desired);
        safe.push(routed === null ? alter + "TYPE " + wantType
                : "ALTER TABLE " + TOKEN + "." + PgIdentifiers.quote(routed)
                        + " ALTER COLUMN " + PgIdentifiers.quote(id.name)
                        + " TYPE " + wantType);
    }
    const wantDefault = wantFields.get("default");
    if (wantDefault !== haveFields.get("default")) {
        safe.push(alter + (wantDefault !== undefined
                ? "SET DEFAULT " + wantDefault : "DROP DEFAULT"));
    }
    // storage strategy and compression are declarable (column storage
    // parameters in CREATE TABLE) and re-point only future writes —
    // in-place safe alterations like SET DEFAULT
    const wantStorage = wantFields.get("storage");
    if (wantStorage !== undefined && wantStorage !== haveFields.get("storage")) {
        safe.push(alter + "SET STORAGE "
                + (STORAGE_WORDS.get(wantStorage) ?? "EXTENDED"));
    }
    const wantCompression = wantFields.get("compression");
    if (wantCompression !== undefined
            && wantCompression !== haveFields.get("compression")) {
        safe.push(alter + "SET COMPRESSION "
                + (COMPRESSION_WORDS.get(wantCompression) ?? "default"));
    }
    const wantNotnull = wantFields.get("notnull") === "true";
    const haveNotnull = haveFields.get("notnull") === "true";
    const setNotNull = wantNotnull && !haveNotnull;
    if (haveNotnull && !wantNotnull) {
        safe.push(alter + "DROP NOT NULL");
    }
    if (safe.length === 0 && !setNotNull) {
        return manualColumn(id, "drifted " + id + " requires a manual change");
    }
    const changes: PlannedChange[] = [];
    if (safe.length > 0) {
        // with SET NOT NULL deferred to its own change, the applied
        // statements only converge the column to want-minus-notnull; the
        // expected definition must match that state exactly
        const expected = setNotNull
                ? new Definition(canonicalWith(wantFields, "notnull",
                        haveFields.get("notnull")!))
                : want.definition;
        changes.push(new PlannedChange(0, id, ChangeAction.ALTER, ChangeSafety.REBUILD,
                safe.map(s => bind(s, ns)),
                expected, "drifted " + id));
    }
    if (setNotNull) {
        changes.push(new PlannedChange(0, id, ChangeAction.ALTER,
                ChangeSafety.DESTRUCTIVE,
                [bind(alter + "SET NOT NULL", ns)],
                want.definition,
                "drifted " + id + " (SET NOT NULL validates existing rows)"));
    }
    return changes;
}

/** The canonical of `fields`, with `key` replaced by `value`. */
function canonicalWith(fields: Map<string, string>, key: string,
        value: string): string {
    const merged = new Map(fields);
    merged.set(key, value);
    return [...merged.entries()].map(([k, v]) => k + "=" + v).join(";");
}

/**
 * The name of the topmost partitioned ancestor a column's parent inherits
 * from — `null` when the parent is not a managed partition. PG
 * rejects inherited column alterations on every partition, including
 * intermediate ones in a multi-level hierarchy, so the ALTER runs on the
 * partitioned root and propagates down to every level.
 */
function partitionParent(parent: string, desired: SchemaSnapshot): string | null {
    const partitions = new Map<string, string>();
    for (const o of desired.objects.keys()) {
        if (o.kind === ObjectKind.PARTITION) {
            partitions.set(o.name, o.parent ?? "");
        }
    }
    const seen = new Set<string>();
    let current = parent;
    while (!seen.has(current) && partitions.has(current)) {
        seen.add(current);
        current = partitions.get(current)!;
    }
    return current === parent ? null : current;
}

function manualColumn(id: ObjectId, reason: string): PlannedChange[] {
    return [new PlannedChange(0, id, ChangeAction.ALTER,
            ChangeSafety.DESTRUCTIVE, [], undefined, reason)];
}

/** The `key=value` fields of a canonical definition — the
 *  `;` separator is honoured only outside quoted literals, so a
 *  `';'` inside a default expression or a label value cannot split
 *  a field. */
function columnFields(canonical: string): Map<string, string> {
    const fields = new Map<string, string>();
    for (const part of PgSql.splitOutsideLiterals(canonical, ";")) {
        const eq = part.indexOf("=");
        if (eq > 0) {
            fields.set(part.substring(0, eq), part.substring(eq + 1));
        }
    }
    return fields;
}

/**
 * The labels of the canonical `labels='a','b'` field — a
 * comma-split that honours `''` escapes inside each quoted label.
 */
function enumLabels(canonical: string): string[] {
    const labels = columnFields(canonical).get("labels");
    if (labels === undefined) {
        return [];
    }
    const out: string[] = [];
    for (const tok of PgSql.splitOutsideLiterals(labels, ",")) {
        if (tok.length >= 2 && tok.startsWith("'") && tok.endsWith("'")) {
            out.push(tok.substring(1, tok.length - 1).replaceAll("''", "'"));
        }
    }
    return out;
}

/** `attstorage` letter to the `SET STORAGE` keyword. */
const STORAGE_WORDS = new Map<string, string>([
        ["p", "PLAIN"], ["e", "EXTERNAL"], ["m", "MAIN"], ["x", "EXTENDED"]]);

/** `attcompression` letter to the `SET COMPRESSION` method. */
const COMPRESSION_WORDS = new Map<string, string>([
        ["p", "pglz"], ["l", "lz4"], ["", "default"]]);

const INT_TYPES = new Map<string, number>([
        ["smallint", 1], ["integer", 2], ["bigint", 3]]);
const TYPMOD = /\((\d+)(?:,(\d+))?\)/;

/**
 * Whether `have → want` is a widening: a total and lossless type
 * change — every stored value converts without error, truncation or
 * semantic change. The allowlist is the integer chain, `character
 * varying` growth or conversion to `text`, `character(n)`
 * growth, and `numeric` growth that keeps the integer digits and
 * does not shrink the scale. Several of these
 * rewrite the table (the integer chain, `character` growth,
 * scale-increasing `numeric`); that is a locking concern (OI-6),
 * not a data-safety one. Notably excluded: `character → character
 * varying/text` strips trailing spaces, and `→ character` changes
 * padding semantics.
 */
function widens(have: string, want: string): boolean {
    const hb = base(have);
    const wb = base(want);
    const hr = INT_TYPES.get(hb);
    const wr = INT_TYPES.get(wb);
    if (hr !== undefined && wr !== undefined
            && typmod(have).length === 0 && typmod(want).length === 0) {
        return wr > hr;
    }
    if (hb === "character varying"
            && (wb === "character varying" || wb === "text")) {
        const hm = typmod(have);
        const wm = typmod(want);
        return wb === "text" || wm.length === 0
                || (hm.length === 1 && wm[0]! >= hm[0]!);
    }
    if (hb === "character" && wb === "character") {
        const hm = typmod(have);
        const wm = typmod(want);
        return hm.length === 1 && wm.length === 1 && wm[0]! >= hm[0]!;
    }
    if (hb === "numeric" && wb === "numeric") {
        const hm = typmod(have);
        const wm = typmod(want);
        // integer digits (p - s) must not shrink, or existing values overflow
        return wm.length === 0
                || (hm.length === 2 && wm.length === 2
                        && wm[1]! >= hm[1]! && wm[0]! - wm[1]! >= hm[0]! - hm[1]!);
    }
    return false;
}

function base(type: string): string {
    const p = type.indexOf("(");
    return (p < 0 ? type : type.substring(0, p)).trim();
}

function typmod(type: string): number[] {
    const m = TYPMOD.exec(type);
    if (m === null) {
        return [];
    }
    return m[2] !== undefined
            ? [Number(m[1]), Number(m[2])]
            : [Number(m[1])];
}

function additive(id: ObjectId, want: SchemaObject, ns: string,
        reason: string): PlannedChange {
    return new PlannedChange(0, id, ChangeAction.CREATE, ChangeSafety.ADDITIVE,
            createStatements(want, ns), want.definition, reason);
}

/** The CREATE statement plus its post-create fragment (trigger enablement, replica identity). */
function createStatements(want: SchemaObject, ns: string): string[] {
    const statements: string[] = [];
    statements.push(bindObj(want, PgCatalog.CREATE_SQL, ns));
    const post = want.attributes.get(PgCatalog.POST_SQL);
    if (post !== undefined) {
        statements.push(bind(post, ns));
    }
    return statements;
}

/** A DROP change plus a CREATE change — the two halves of a recreate (4.4). */
function recreate(id: ObjectId, want: SchemaObject, ns: string,
        reason: string): PlannedChange[] {
    return [
        new PlannedChange(0, id, ChangeAction.DROP, ChangeSafety.REBUILD,
                [bindObj(want, PgCatalog.DROP_SQL, ns)], undefined, reason),
        new PlannedChange(0, id, ChangeAction.CREATE, ChangeSafety.REBUILD,
                createStatements(want, ns), want.definition, reason)];
}

function seqRef(id: ObjectId): string {
    return TOKEN + "." + PgIdentifiers.quote(id.name);
}

function bindObj(object: SchemaObject, attrKey: string, ns: string): string {
    return bind(object.attributes.get(attrKey)!, ns);
}

function bind(sql: string, ns: string): string {
    return sql.replaceAll(TOKEN, PgIdentifiers.quote(ns));
}

/** Extracts the `RETURNS <type>` clause from a normalised function definition. */
function returnTypeOf(functionDef: string): string | null {
    const m = /\bRETURNS (.+?) LANGUAGE\b/.exec(functionDef);
    return m !== null ? m[1]! : null;
}

// ---- SPI helpers -------------------------------------------------------

export function coveredChildren(created: Difference,
        desired: SchemaSnapshot): Iterable<ObjectId> {
    if (created.id.kind !== ObjectKind.TABLE
            && created.id.kind !== ObjectKind.PARTITION
            && created.id.kind !== ObjectKind.FOREIGN_TABLE) {
        return [];
    }
    const table = created.id;
    const out: ObjectId[] = [];
    for (const o of desired.objects.values()) {
        if ((o.id.kind === ObjectKind.COLUMN && o.id.parent === table.name)
                || (o.id.kind === ObjectKind.SEQUENCE
                        && o.dependsOn.has(table))) {
            out.push(o.id);
        }
    }
    return out;
}

/**
 * OTHER categories whose stripped names occupy the relation/type
 * namespace — the relkind-prefixed relations plus `type:` (a
 * standalone type collides with a table's rowtype). Must match the
 * prefixes `PgCatalog` assigns to those objects.
 */
const RELATION_NAMESPACE_OTHERS = new Set([
        "inherited", "partindex", "toast", "relation", "type"]);

export function nameScopes(id: ObjectId): Iterable<string> {
    switch (id.kind) {
        case ObjectKind.OTHER: {
            const name = id.name;
            const colon = name.indexOf(":");
            if (colon > 0) {
                const category = name.substring(0, colon);
                const stripped = name.substring(colon + 1);
                if (RELATION_NAMESPACE_OTHERS.has(category)) {
                    return ["relation:" + stripped];
                }
                // unsupported routines share pg_proc with managed
                // routines, keyed by name plus signature
                if (category === "routine") {
                    return ["routine:" + stripped];
                }
                // an unsupported constraint (a constraint trigger) is
                // named "<parent>.<name>" like a managed one
                if (category === "constraint") {
                    return ["constraint:" + stripped];
                }
            }
            return ["relation:" + name];
        }
        // types and domains share the type namespace, which collides
        // with the relation namespace through composite-type rowtypes
        case ObjectKind.TABLE:
        case ObjectKind.VIEW:
        case ObjectKind.MATERIALIZED_VIEW:
        case ObjectKind.SEQUENCE:
        case ObjectKind.INDEX:
        case ObjectKind.TYPE:
        case ObjectKind.DOMAIN:
        case ObjectKind.PARTITION:
        case ObjectKind.FOREIGN_TABLE:
            return ["relation:" + id.name];
        case ObjectKind.ROUTINE:
        case ObjectKind.AGGREGATE:
            return ["routine:" + id.name];
        case ObjectKind.PRIMARY_KEY:
        case ObjectKind.UNIQUE:
        case ObjectKind.EXCLUSION:
            return ["relation:" + id.name,
                    "constraint:" + (id.parent ?? "") + "." + id.name];
        case ObjectKind.FOREIGN_KEY:
        case ObjectKind.CHECK:
            return ["constraint:" + (id.parent ?? "") + "." + id.name];
        default:
            return [];
    }
}

function mapsEqual(a: ReadonlyMap<string, string>,
        b: ReadonlyMap<string, string>): boolean {
    if (a.size !== b.size) {
        return false;
    }
    for (const [k, v] of a) {
        if (!b.has(k) || b.get(k) !== v) {
            return false;
        }
    }
    return true;
}

function arrayEquals(a: readonly string[], b: readonly string[]): boolean {
    return a.length === b.length && a.every((v, i) => v === b[i]);
}
