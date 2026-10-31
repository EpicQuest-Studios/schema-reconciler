import { SchemaReconcilerException } from "./SchemaReconcilerException.ts";

/**
 * A dependency cycle, external or unexpected dependent, name collision, or an
 * unrenderable difference (spec 7).
 */
export class PlanningException extends SchemaReconcilerException {
}
