import { Decision } from "./Decision.ts";
import { IllegalArgumentError } from "./IllegalArgumentError.ts";
import { ChangeSafety } from "./model/ChangeSafety.ts";
import type { Difference } from "./model/Difference.ts";
import { ObjectKind } from "./model/ObjectKind.ts";

/**
 * Policy governing what a plan may do with each safety class of change, and
 * with {@code UNSUPPORTED} differences (spec 3.3). {@code unsupported} governs
 * differences, not changes; {@code APPLY} is not permitted for it.
 * {@code unsupportedCategories} overrides {@code unsupported} per difference
 * category: for {@link ObjectKind#OTHER} objects the category is the dialect's
 * name prefix before {@code ':'} (for example {@code "extension"},
 * {@code "comment"}, {@code "policy"}; a name without a prefix is
 * {@code "relation"}), for other kinds it is the lowercased
 * {@link ObjectKind} name.
 */
export class ReconcilePolicy {
    readonly additive: Decision;
    readonly rebuild: Decision;
    readonly destructive: Decision;
    readonly unsupported: Decision;
    readonly unsupportedCategories: ReadonlyMap<string, Decision>;

    constructor(additive: Decision, rebuild: Decision, destructive: Decision,
                unsupported: Decision,
                unsupportedCategories?: ReadonlyMap<string, Decision>) {
        for (const [name, v] of Object.entries({ additive, rebuild, destructive, unsupported })) {
            if (v == null) {
                throw new TypeError(name + " is required");
            }
        }
        if (unsupported === Decision.APPLY) {
            throw new IllegalArgumentError("unsupported must not be APPLY");
        }
        const categories = new Map(unsupportedCategories ?? []);
        for (const v of categories.values()) {
            if (v === Decision.APPLY) {
                throw new IllegalArgumentError("unsupported categories must not be APPLY");
            }
        }
        this.additive = additive;
        this.rebuild = rebuild;
        this.destructive = destructive;
        this.unsupported = unsupported;
        this.unsupportedCategories = categories;
        Object.freeze(this);
    }

    static defaults(): ReconcilePolicy {
        return new ReconcilePolicy(Decision.APPLY, Decision.APPLY, Decision.FAIL, Decision.FAIL);
    }

    static reportOnly(): ReconcilePolicy {
        return new ReconcilePolicy(Decision.REPORT, Decision.REPORT,
                Decision.REPORT, Decision.REPORT);
    }

    withAdditive(decision: Decision): ReconcilePolicy {
        return new ReconcilePolicy(decision, this.rebuild, this.destructive,
                this.unsupported, this.unsupportedCategories);
    }

    withRebuild(decision: Decision): ReconcilePolicy {
        return new ReconcilePolicy(this.additive, decision, this.destructive,
                this.unsupported, this.unsupportedCategories);
    }

    withDestructive(decision: Decision): ReconcilePolicy {
        return new ReconcilePolicy(this.additive, this.rebuild, decision,
                this.unsupported, this.unsupportedCategories);
    }

    withUnsupported(decision: Decision): ReconcilePolicy {
        return new ReconcilePolicy(this.additive, this.rebuild, this.destructive,
                decision, this.unsupportedCategories);
    }

    /** Overrides the {@code unsupported} decision for one difference category. */
    withUnsupportedCategory(category: string, decision: Decision): ReconcilePolicy {
        if (category == null) {
            throw new TypeError("category is required");
        }
        if (decision == null) {
            throw new TypeError("decision is required");
        }
        const categories = new Map(this.unsupportedCategories);
        categories.set(category, decision);
        return new ReconcilePolicy(this.additive, this.rebuild, this.destructive,
                this.unsupported, categories);
    }

    /** The decision governing a change of the given safety class. */
    decisionFor(safety: ChangeSafety): Decision {
        switch (safety) {
            case ChangeSafety.ADDITIVE: return this.additive;
            case ChangeSafety.REBUILD: return this.rebuild;
            case ChangeSafety.DESTRUCTIVE: return this.destructive;
        }
    }

    /** The decision governing an {@code UNSUPPORTED} difference, per category override. */
    unsupportedDecision(difference: Difference): Decision {
        return this.unsupportedCategories.get(categoryOf(difference)) ?? this.unsupported;
    }
}

/** The policy category of a difference's object: OTHER name prefix, else kind name. */
function categoryOf(difference: Difference): string {
    const id = difference.id;
    if (id.kind === ObjectKind.OTHER) {
        const colon = id.name.indexOf(":");
        return colon < 0 ? "relation" : id.name.substring(0, colon);
    }
    return id.kind.toLowerCase();
}
