import type { ObjectKind } from "../model/ObjectKind.ts";

/** What a dialect supports. */
export class DialectCapabilities {
    readonly transactionalDdl: boolean;
    readonly sessionDefaultNamespace: boolean;
    readonly supportedKinds: ReadonlySet<ObjectKind>;

    constructor(transactionalDdl: boolean, sessionDefaultNamespace: boolean,
                supportedKinds: Iterable<ObjectKind>) {
        this.transactionalDdl = transactionalDdl;
        this.sessionDefaultNamespace = sessionDefaultNamespace;
        this.supportedKinds = new Set(supportedKinds);
        Object.freeze(this);
    }
}
