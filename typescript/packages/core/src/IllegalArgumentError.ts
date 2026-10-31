/**
 * The TypeScript analog of {@code IllegalArgumentException}: an argument
 * violated a method's contract (bad identifier, empty name, negative order).
 * Distinct from {@link TypeError} (null/wrong-type checks, the
 * {@code NullPointerException} analog) so call sites can classify like Java.
 */
export class IllegalArgumentError extends Error {
    constructor(message: string) {
        super(message);
        this.name = "IllegalArgumentError";
    }
}
