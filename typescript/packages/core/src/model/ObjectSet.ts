import type { ObjectId } from "./ObjectId.ts";

/**
 * A set of {@link ObjectId}s compared by *value* — see {@link ObjectMap} for
 * why a plain {@link Set} cannot hold ids.
 */
export class ObjectSet implements Iterable<ObjectId> {
    private readonly entries_: Map<string, ObjectId> = new Map();

    constructor(values?: Iterable<ObjectId>) {
        if (values !== undefined) {
            for (const id of values) {
                this.add(id);
            }
        }
    }

    get size(): number {
        return this.entries_.size;
    }

    get isEmpty(): boolean {
        return this.entries_.size === 0;
    }

    has(id: ObjectId): boolean {
        return this.entries_.has(id.toKey());
    }

    add(id: ObjectId): this {
        this.entries_.set(id.toKey(), id);
        return this;
    }

    delete(id: ObjectId): boolean {
        return this.entries_.delete(id.toKey());
    }

    clear(): void {
        this.entries_.clear();
    }

    /** Removes every element not in {@code keep}. */
    retainAll(keep: ObjectSet): void {
        for (const id of [...this.entries_.values()]) {
            if (!keep.has(id)) {
                this.entries_.delete(id.toKey());
            }
        }
    }

    values(): IterableIterator<ObjectId> {
        return this.entries_.values();
    }

    [Symbol.iterator](): IterableIterator<ObjectId> {
        return this.entries_.values();
    }
}
