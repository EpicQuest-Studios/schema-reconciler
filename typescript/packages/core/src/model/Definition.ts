/**
 * An engine-rendered canonical definition of a schema object (spec 4.2, 5.3).
 */
export class Definition {
    readonly canonical: string;

    constructor(canonical: string) {
        if (canonical == null) {
            throw new TypeError("canonical is required");
        }
        this.canonical = canonical;
        Object.freeze(this);
    }

    equals(other: unknown): boolean {
        return other instanceof Definition && other.canonical === this.canonical;
    }

    toString(): string {
        return this.canonical;
    }
}
