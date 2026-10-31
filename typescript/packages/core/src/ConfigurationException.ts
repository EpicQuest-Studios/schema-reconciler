import { SchemaReconcilerException } from "./SchemaReconcilerException.ts";

/** Invalid builder input, or no or ambiguous dialect (spec 7). */
export class ConfigurationException extends SchemaReconcilerException {
}
