import { RunTimings } from "./RunTimings.ts";

/** Base class of all exceptions thrown by the reconciler. */
export class SchemaReconcilerException extends Error {

    private timings_ = RunTimings.unmeasured();

    constructor(message: string, cause?: unknown) {
        super(message, cause === undefined ? undefined : { cause });
        this.name = new.target.name;
    }

    /**
     * The phases the failed run completed before throwing; zero for phases it
     * never reached. Always present — {@link RunTimings.unmeasured} when
     * the failure predates the run's instrumentation.
     */
    get timings(): RunTimings {
        return this.timings_;
    }

    /** Attaches run timings to this failure; returns the same exception. */
    withTimings(timings: RunTimings): this {
        this.timings_ = timings;
        return this;
    }
}

/** The message of any thrown value, for exception wrapping. */
export function messageOf(e: unknown): string {
    return e instanceof Error ? (e.message ?? String(e)) : String(e);
}
