import type { Definition } from "../model/Definition.ts";
import { Difference } from "../model/Difference.ts";
import { DifferenceType } from "../model/DifferenceType.ts";
import { ObjectIds } from "../model/ObjectIds.ts";
import type { SchemaObject } from "../model/SchemaObject.ts";
import type { SchemaSnapshot } from "../model/SchemaSnapshot.ts";
import { Dialect } from "../spi/Dialect.ts";

/**
 * Computes the differences of spec 5.4 and 5.5 between the actual and desired
 * snapshots, sorted by {@link ObjectIds#ORDER}.
 */
export const Differ = {
    diff(actual: SchemaSnapshot, desired: SchemaSnapshot, dialect: Dialect): Difference[] {
        const out: Difference[] = [];
        for (const want of sorted(desired)) {
            const id = want.id;
            const have = actual.objects.get(id);
            if (!dialect.capabilities().supportedKinds.has(id.kind)) {
                if (isContentCompared(want) && have !== undefined
                        && have.definition.equals(want.definition)) {
                    continue;
                }
                out.push(new Difference(id, DifferenceType.UNSUPPORTED,
                        want.definition, definitionOf(have)));
            } else if (have === undefined) {
                out.push(new Difference(id, DifferenceType.MISSING,
                        want.definition, undefined));
            } else if (!have.definition.equals(want.definition)) {
                out.push(new Difference(id, DifferenceType.DRIFTED,
                        want.definition, have.definition));
            }
        }
        for (const have of sorted(actual)) {
            if (!desired.objects.has(have.id) && !isExternalDependent(have)) {
                out.push(new Difference(have.id, DifferenceType.UNEXPECTED,
                        undefined, have.definition));
            }
        }
        out.sort((a, b) => ObjectIds.ORDER(a.id, b.id));
        return out;
    },
} as const;

function isContentCompared(object: SchemaObject): boolean {
    return object.attributes.get(Dialect.CONTENT_COMPARED_ATTRIBUTE) === "true";
}

function isExternalDependent(object: SchemaObject): boolean {
    return object.attributes.get(Dialect.EXTERNAL_DEPENDENT_ATTRIBUTE) === "true";
}

function definitionOf(o: SchemaObject | undefined): Definition | undefined {
    return o?.definition;
}

function sorted(snapshot: SchemaSnapshot): SchemaObject[] {
    return [...snapshot.objects.values()]
            .sort((a, b) => ObjectIds.ORDER(a.id, b.id));
}
