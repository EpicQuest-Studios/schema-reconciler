package reconciler

// minHeap is a binary min-heap — the stand-in for the TypeScript binding's
// MinHeap and Java's java.util.PriorityQueue. It orders the ready set of the
// topological sorts in plan building and dump rendering.
type minHeap[T any] struct {
	items   []T
	compare func(a, b T) int
}

func newMinHeap[T any](compare func(a, b T) int) *minHeap[T] {
	return &minHeap[T]{compare: compare}
}

func (h *minHeap[T]) size() int { return len(h.items) }

func (h *minHeap[T]) add(item T) {
	h.items = append(h.items, item)
	i := len(h.items) - 1
	for i > 0 {
		parent := (i - 1) >> 1
		if h.compare(h.items[i], h.items[parent]) >= 0 {
			break
		}
		h.items[i], h.items[parent] = h.items[parent], h.items[i]
		i = parent
	}
}

// poll removes and returns the smallest element; ok is false when empty.
func (h *minHeap[T]) poll() (top T, ok bool) {
	if len(h.items) == 0 {
		return top, false
	}
	top = h.items[0]
	last := h.items[len(h.items)-1]
	h.items = h.items[:len(h.items)-1]
	if len(h.items) > 0 {
		h.items[0] = last
		i := 0
		for {
			smallest := i
			left := 2*i + 1
			right := left + 1
			if left < len(h.items) && h.compare(h.items[left], h.items[smallest]) < 0 {
				smallest = left
			}
			if right < len(h.items) && h.compare(h.items[right], h.items[smallest]) < 0 {
				smallest = right
			}
			if smallest == i {
				break
			}
			h.items[i], h.items[smallest] = h.items[smallest], h.items[i]
			i = smallest
		}
	}
	return top, true
}
