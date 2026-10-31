/**
 * A duration value — the TypeScript binding for {@code java.time.Duration},
 * stored as fractional milliseconds. Factory names mirror the Java API so
 * call sites port verbatim.
 */
export class Duration {
    static readonly ZERO = new Duration(0);

    readonly millis: number;

    private constructor(millis: number) {
        this.millis = millis;
    }

    static ofMillis(millis: number): Duration {
        return millis === 0 ? Duration.ZERO : new Duration(millis);
    }

    static ofSeconds(seconds: number): Duration {
        return new Duration(seconds * 1000);
    }

    static ofNanos(nanos: number): Duration {
        return new Duration(nanos / 1e6);
    }

    get isZero(): boolean {
        return this.millis === 0;
    }

    get isNegative(): boolean {
        return this.millis < 0;
    }

    toMillis(): number {
        return this.millis;
    }

    toString(): string {
        return this.millis + "ms";
    }
}
