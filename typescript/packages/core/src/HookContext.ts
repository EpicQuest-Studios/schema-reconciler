import type { HookPhase } from "./HookPhase.ts";
import type { PlannedChange } from "./model/PlannedChange.ts";
import type { ReconcilePlan } from "./model/ReconcilePlan.ts";
import type { SqlConnection } from "./spi/Sql.ts";

/** The SQL surface a hook may use on the run's pinned connection. */
export type HookSqlExecutor = Pick<SqlConnection, "execute" | "query">;

/** The immutable context passed to a schema hook (spec 3.8). */
export interface HookContext {
    readonly namespace: string;
    readonly dialectId: string;
    readonly phase: HookPhase;
    readonly plan: ReconcilePlan;
    readonly applied: readonly PlannedChange[];
    readonly connection: HookSqlExecutor;
}
