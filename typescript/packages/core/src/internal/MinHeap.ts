/** A binary min-heap — the TypeScript stand-in for {@code java.util.PriorityQueue}. */
export class MinHeap<T> {
    private readonly items: T[] = [];
    private readonly compare: (a: T, b: T) => number;

    constructor(compare: (a: T, b: T) => number) {
        this.compare = compare;
    }

    get size(): number {
        return this.items.length;
    }

    get isEmpty(): boolean {
        return this.items.length === 0;
    }

    add(item: T): void {
        this.items.push(item);
        let i = this.items.length - 1;
        while (i > 0) {
            const parent = (i - 1) >> 1;
            if (this.compare(this.items[i]!, this.items[parent]!) >= 0) {
                break;
            }
            const tmp = this.items[i]!;
            this.items[i] = this.items[parent]!;
            this.items[parent] = tmp;
            i = parent;
        }
    }

    /** Removes and returns the smallest element; undefined when empty. */
    poll(): T | undefined {
        const top = this.items[0];
        const last = this.items.pop()!;
        if (this.items.length > 0) {
            this.items[0] = last;
            let i = 0;
            for (;;) {
                let smallest = i;
                const left = 2 * i + 1;
                const right = left + 1;
                if (left < this.items.length
                        && this.compare(this.items[left]!, this.items[smallest]!) < 0) {
                    smallest = left;
                }
                if (right < this.items.length
                        && this.compare(this.items[right]!, this.items[smallest]!) < 0) {
                    smallest = right;
                }
                if (smallest === i) {
                    break;
                }
                const tmp = this.items[i]!;
                this.items[i] = this.items[smallest]!;
                this.items[smallest] = tmp;
                i = smallest;
            }
        }
        return top;
    }
}
