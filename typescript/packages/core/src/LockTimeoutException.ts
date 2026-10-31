import { SchemaReconcilerException } from "./SchemaReconcilerException.ts";

/** The namespace lock was not acquired within {@code lockTimeout} (spec 7). */
export class LockTimeoutException extends SchemaReconcilerException {
}
