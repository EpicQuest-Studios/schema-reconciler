import type { HookContext } from "./HookContext.ts";
import type { HookPhase } from "./HookPhase.ts";

/** An ordered lifecycle hook of spec 3.8. */
export interface SchemaHook {
    readonly id: string;
    readonly phase: HookPhase;
    readonly order?: number;
    readonly runOnce?: boolean;
    readonly checksum?: string;
    run(ctx: HookContext): Promise<void> | void;
}
