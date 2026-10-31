import type { ObjectId } from "./ObjectId.ts";

/**
 * A map keyed by {@link ObjectId} *value* — Java records compare structurally;
 * JavaScript {@link Map} keys compare by reference, so id-keyed collections
 * would silently miss lookups on equal-but-distinct instances. Internally a
 * {@link Map} over {@link ObjectId#toKey} that keeps the original id for
 * iteration, giving back exact Java-map semantics.
 */
export class ObjectMap<V> implements Iterable<[ObjectId, V]> {
    private readonly entries_: Map<string, [ObjectId, V]> = new Map();

    constructor(entries?: Iterable<[ObjectId, V]>) {
        if (entries !== undefined) {
            for (const [id, v] of entries) {
                this.set(id, v);
            }
        }
    }

    get size(): number {
        return this.entries_.size;
    }

    get(id: ObjectId): V | undefined {
        return this.entries_.get(id.toKey())?.[1];
    }

    has(id: ObjectId): boolean {
        return this.entries_.has(id.toKey());
    }

    set(id: ObjectId, value: V): this {
        this.entries_.set(id.toKey(), [id, value]);
        return this;
    }

    delete(id: ObjectId): boolean {
        return this.entries_.delete(id.toKey());
    }

    clear(): void {
        this.entries_.clear();
    }

    keys(): IterableIterator<ObjectId> {
        const it = this.entries_.values();
        return (function* () {
            for (const e of it) {
                yield e[0];
            }
        })();
    }

    values(): IterableIterator<V> {
        const it = this.entries_.values();
        return (function* () {
            for (const e of it) {
                yield e[1];
            }
        })();
    }

    entries(): IterableIterator<[ObjectId, V]> {
        return this.entries_.values();
    }

    [Symbol.iterator](): IterableIterator<[ObjectId, V]> {
        return this.entries();
    }
}
