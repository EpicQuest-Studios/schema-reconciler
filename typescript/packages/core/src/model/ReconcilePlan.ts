import { IllegalArgumentError } from "../IllegalArgumentError.ts";
import { PlanRenderer } from "../internal/PlanRenderer.ts";
import { ChangeAction } from "./ChangeAction.ts";
import { ChangeHazard } from "./ChangeHazard.ts";
import { ChangeSafety } from "./ChangeSafety.ts";
import { Definition } from "./Definition.ts";
import { Difference } from "./Difference.ts";
import { DifferenceType } from "./DifferenceType.ts";
import { LockLevel } from "./LockLevel.ts";
import { ObjectId } from "./ObjectId.ts";
import { ObjectKind } from "./ObjectKind.ts";
import { PlannedChange } from "./PlannedChange.ts";

/**
 * An inspectable plan (spec 4.4). {@link toSql} serialises it as plain text:
 * a deterministic header plus one comment block per change; the output is
 * byte-identical for identical input (spec 5.2). {@link toJson} serialises
 * it as machine-readable JSON for later {@link SchemaReconciler.applyJson}.
 */
export class ReconcilePlan {
    readonly targetNamespace: string;
    readonly dialectId: string;
    readonly actualFingerprint: string;
    readonly desiredFingerprint: string;
    readonly differences: readonly Difference[];
    readonly changes: readonly PlannedChange[];

    constructor(targetNamespace: string, dialectId: string,
                actualFingerprint: string, desiredFingerprint: string,
                differences: readonly Difference[], changes: readonly PlannedChange[]) {
        if (targetNamespace == null) {
            throw new TypeError("targetNamespace is required");
        }
        if (dialectId == null) {
            throw new TypeError("dialectId is required");
        }
        if (actualFingerprint == null) {
            throw new TypeError("actualFingerprint is required");
        }
        if (desiredFingerprint == null) {
            throw new TypeError("desiredFingerprint is required");
        }
        if (differences == null) {
            throw new TypeError("differences is required");
        }
        if (changes == null) {
            throw new TypeError("changes is required");
        }
        this.targetNamespace = targetNamespace;
        this.dialectId = dialectId;
        this.actualFingerprint = actualFingerprint;
        this.desiredFingerprint = desiredFingerprint;
        this.differences = Object.freeze([...differences]);
        this.changes = Object.freeze([...changes]);
        Object.freeze(this);
    }

    get isEmpty(): boolean {
        return this.changes.length === 0;
    }

    toSql(): string {
        return PlanRenderer.render(this);
    }

    /**
     * The machine-readable serialisation (schemaVersion 1): keys in a fixed
     * order, changes in plan order — byte-identical for identical input.
     */
    toJson(): string {
        return JSON.stringify({
            schemaVersion: 1,
            targetNamespace: this.targetNamespace,
            dialectId: this.dialectId,
            actualFingerprint: this.actualFingerprint,
            desiredFingerprint: this.desiredFingerprint,
            differences: this.differences.map(d => ({
                id: idJson(d.id),
                type: d.type,
                expected: d.expected === undefined ? null : d.expected.canonical,
                actual: d.actual === undefined ? null : d.actual.canonical,
            })),
            changes: this.changes.map(c => ({
                order: c.order,
                target: idJson(c.target),
                action: c.action,
                safety: c.safety,
                statements: [...c.statements],
                expected: c.expected === undefined ? null : c.expected.canonical,
                reason: c.reason,
                nonTransactional: c.nonTransactional,
                hazard: {
                    lockLevel: c.hazard.lockLevel,
                    rewritesTable: c.hazard.rewritesTable,
                    validatesRows: c.hazard.validatesRows,
                    notes: [...c.hazard.notes],
                },
            })),
        });
    }

    /**
     * Rebuilds a plan from {@link toJson} output.
     * @throws IllegalArgumentError on malformed JSON, a wrong schema
     *         version, or missing/invalid fields
     */
    static fromJson(json: string): ReconcilePlan {
        if (json == null) {
            throw new TypeError("json is required");
        }
        let raw: unknown;
        try {
            raw = JSON.parse(json);
        } catch (e) {
            throw new IllegalArgumentError("invalid plan JSON: " + e);
        }
        const o = raw as Record<string, unknown>;
        if (o === null || typeof o !== "object" || Array.isArray(o)) {
            throw new IllegalArgumentError("invalid plan JSON: not an object");
        }
        if (o["schemaVersion"] !== 1) {
            throw new IllegalArgumentError("invalid plan JSON: schemaVersion "
                    + String(o["schemaVersion"]));
        }
        const differences = requireArray(o["differences"], "differences").map((d, i) => {
            const dd = requireObject(d, "differences[" + i + "]");
            return new Difference(requireId(dd["id"], "differences[" + i + "].id"),
                    requireEnum(dd["type"], DifferenceType,
                            "differences[" + i + "].type"),
                    optDef(dd["expected"]), optDef(dd["actual"]));
        });
        const changes = requireArray(o["changes"], "changes").map((c, i) => {
            const cc = requireObject(c, "changes[" + i + "]");
            const hz = requireObject(cc["hazard"], "changes[" + i + "].hazard");
            const order = requireNumber(cc["order"], "changes[" + i + "].order");
            if (order !== i + 1) {
                throw new IllegalArgumentError("invalid plan JSON: changes[" + i
                        + "].order must be " + (i + 1));
            }
            return new PlannedChange(
                    order,
                    requireId(cc["target"], "changes[" + i + "].target"),
                    requireEnum(cc["action"], ChangeAction, "changes[" + i + "].action"),
                    requireEnum(cc["safety"], ChangeSafety, "changes[" + i + "].safety"),
                    requireStrings(cc["statements"], "changes[" + i + "].statements"),
                    optDef(cc["expected"]),
                    requireString(cc["reason"], "changes[" + i + "].reason"),
                    cc["nonTransactional"] === true,
                    new ChangeHazard(
                            requireEnum(hz["lockLevel"] ?? LockLevel.NONE, LockLevel,
                                    "changes[" + i + "].hazard.lockLevel"),
                            hz["rewritesTable"] === true,
                            hz["validatesRows"] === true,
                            requireStrings(hz["notes"] ?? [],
                                    "changes[" + i + "].hazard.notes")));
        });
        return new ReconcilePlan(
                requireString(o["targetNamespace"], "targetNamespace"),
                requireString(o["dialectId"], "dialectId"),
                requireString(o["actualFingerprint"], "actualFingerprint"),
                requireString(o["desiredFingerprint"], "desiredFingerprint"),
                differences, changes);
    }
}

function idJson(id: ObjectId): object {
    return { kind: id.kind, name: id.name, parent: id.parent ?? null };
}

function optDef(v: unknown): Definition | undefined {
    if (v === undefined || v === null) {
        return undefined;
    }
    if (typeof v !== "string") {
        throw new IllegalArgumentError(
                "invalid plan JSON: expected/actual is not a string or null");
    }
    return new Definition(v);
}

function requireObject(v: unknown, field: string): Record<string, unknown> {
    if (v === null || typeof v !== "object" || Array.isArray(v)) {
        throw new IllegalArgumentError("invalid plan JSON: " + field
                + " is not an object");
    }
    return v as Record<string, unknown>;
}

function requireArray(v: unknown, field: string): unknown[] {
    if (!Array.isArray(v)) {
        throw new IllegalArgumentError("invalid plan JSON: " + field
                + " is not an array");
    }
    return v;
}

function requireString(v: unknown, field: string): string {
    if (typeof v !== "string") {
        throw new IllegalArgumentError("invalid plan JSON: " + field
                + " is not a string");
    }
    return v;
}

function requireNumber(v: unknown, field: string): number {
    if (typeof v !== "number" || !Number.isFinite(v)) {
        throw new IllegalArgumentError("invalid plan JSON: " + field
                + " is not a number");
    }
    return v;
}

function requireStrings(v: unknown, field: string): string[] {
    if (!Array.isArray(v) || v.some(e => typeof e !== "string")) {
        throw new IllegalArgumentError("invalid plan JSON: " + field
                + " is not a string array");
    }
    return v as string[];
}

function requireEnum<V>(v: unknown, table: Record<string, V>,
        field: string): V {
    if (typeof v !== "string" || !Object.values(table).includes(v as V)) {
        throw new IllegalArgumentError("invalid plan JSON: " + field
                + " has unknown value " + String(v));
    }
    return v as V;
}

function requireId(v: unknown, field: string): ObjectId {
    const o = requireObject(v, field);
    const kind = requireEnum(o["kind"], ObjectKind, field + ".kind");
    const name = requireString(o["name"], field + ".name");
    const parent = o["parent"];
    if (parent !== undefined && parent !== null && typeof parent !== "string") {
        throw new IllegalArgumentError("invalid plan JSON: " + field
                + ".parent is not a string");
    }
    return new ObjectId(kind, name, parent ?? undefined);
}
