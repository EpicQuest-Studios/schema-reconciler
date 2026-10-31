import { IllegalArgumentError } from "../IllegalArgumentError.ts";
import { ObjectKind } from "./ObjectKind.ts";

const PARENTED: ReadonlySet<ObjectKind> = new Set([
    ObjectKind.COLUMN, ObjectKind.PRIMARY_KEY, ObjectKind.UNIQUE,
    ObjectKind.FOREIGN_KEY, ObjectKind.CHECK, ObjectKind.INDEX,
    ObjectKind.TRIGGER, ObjectKind.POLICY, ObjectKind.EXCLUSION,
    ObjectKind.RULE, ObjectKind.PARTITION]);

/** Kinds whose parent mirrors their target's parent: optional, never a bare parent of its own. */
const PARENT_MIRRORED: ReadonlySet<ObjectKind> = new Set([
    ObjectKind.COMMENT, ObjectKind.PUBLICATION, ObjectKind.OWNERSHIP,
    ObjectKind.PRIVILEGES, ObjectKind.SECURITY_LABEL]);

/**
 * Namespace-neutral identity of a schema object (spec 4.1).
 *
 * @param kind the object kind
 * @param name the identifier as stored by the engine; for {@link ObjectKind.ROUTINE}
 *        it includes the identity signature, for example {@code "ts(text)"}
 * @param parent the owning object name for child kinds, undefined otherwise
 */
export class ObjectId {
    readonly kind: ObjectKind;
    readonly name: string;
    readonly parent: string | undefined;

    constructor(kind: ObjectKind, name: string, parent?: string) {
        if (kind == null) {
            throw new TypeError("kind is required");
        }
        if (name == null) {
            throw new TypeError("name is required");
        }
        if (name === "") {
            throw new IllegalArgumentError("name must not be empty");
        }
        if ((PARENTED.has(kind) && parent === undefined)
                || (!PARENTED.has(kind) && !PARENT_MIRRORED.has(kind) && parent !== undefined)) {
            throw new IllegalArgumentError(
                    "parent is required for " + kind + " and must be empty for other kinds");
        }
        if (parent === "") {
            throw new IllegalArgumentError("parent must not be empty");
        }
        this.kind = kind;
        this.name = name;
        this.parent = parent;
        Object.freeze(this);
    }

    /** The canonical map key — NUL-separated, so distinct ids can never collide. */
    toKey(): string {
        return this.kind + "\0" + (this.parent ?? "") + "\0" + this.name;
    }

    equals(other: unknown): boolean {
        return other instanceof ObjectId && other.toKey() === this.toKey();
    }

    toString(): string {
        return this.parent !== undefined
                ? this.kind + " " + this.parent + "." + this.name
                : this.kind + " " + this.name;
    }
}
