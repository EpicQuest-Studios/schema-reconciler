import type { HookPhase } from "./HookPhase.ts";
import type { HookSqlExecutor } from "./HookContext.ts";
import type { ObjectId } from "./model/ObjectId.ts";

/** Durable execution records for checksum-protected run-once hooks (spec 3.8). */
export interface HookStore {
    recordedChecksum(conn: HookSqlExecutor, namespace: string, hookId: string):
            Promise<string | undefined>;
    record(conn: HookSqlExecutor, namespace: string, hookId: string,
           phase: HookPhase, checksum: string): Promise<void>;
    managedTable?(targetNamespace: string): ObjectId | undefined;
}
