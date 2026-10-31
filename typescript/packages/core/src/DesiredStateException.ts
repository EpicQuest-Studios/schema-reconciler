import { SchemaReconcilerException } from "./SchemaReconcilerException.ts";

/**
 * The desired script could not be read, substituted or executed in the scratch
 * namespace (spec 7). Includes the engine's error message and position when
 * the driver provides them.
 */
export class DesiredStateException extends SchemaReconcilerException {
}
