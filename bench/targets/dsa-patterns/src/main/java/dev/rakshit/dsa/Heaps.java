package dev.rakshit.dsa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * PATTERN: heaps / priority queues ("I only ever need the current best, or the k best").
 *
 * <p><b>Recognise it when:</b> the problem says "k-th largest", "top k", "merge k sorted things", or "median of a
 * stream". A full sort is O(n log n); a heap of size k is O(n log k) and works on streams too.
 *
 * <p><b>Trap:</b> for "k largest" keep a <em>min</em>-heap of size k. The smallest of the k survivors sits at the root,
 * so it is the one to evict when something better arrives. A max-heap would be the wrong way round.
 */
public final class Heaps {
    private Heaps() {}

    /** k-th largest element (k = 1 is the maximum). O(n log k) time, O(k) space. */
    public static int kthLargest(int[] nums, int k) {
        if (k < 1 || k > nums.length) throw new IllegalArgumentException("k out of range: " + k);
        PriorityQueue<Integer> minHeap = new PriorityQueue<>();
        for (int x : nums) {
            minHeap.add(x);
            if (minHeap.size() > k) minHeap.poll();   // drop the smallest: it cannot be among the k largest
        }
        return minHeap.peek();
    }

    /**
     * The k most frequent values, most frequent first; ties broken by smaller value so the result is deterministic.
     * Count with a map, then keep a min-heap of size k ordered by (frequency, then larger value is "worse").
     */
    public static int[] topKFrequent(int[] nums, int k) {
        Map<Integer, Integer> freq = new HashMap<>();
        for (int x : nums) freq.merge(x, 1, Integer::sum);
        // "worst" element at the root: lowest frequency, and among equals the larger value.
        PriorityQueue<int[]> heap = new PriorityQueue<>((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(b[0], a[0]));
        for (Map.Entry<Integer, Integer> e : freq.entrySet()) {
            heap.add(new int[] {e.getKey(), e.getValue()});
            if (heap.size() > k) heap.poll();
        }
        int[] out = new int[heap.size()];
        for (int i = out.length - 1; i >= 0; i--) out[i] = heap.poll()[0];
        return out;
    }

    /** Merges k sorted lists with a min-heap of the current head of each list: O(N log k) for N total nodes. */
    public static ListNode mergeKLists(List<ListNode> lists) {
        PriorityQueue<ListNode> heap = new PriorityQueue<>((a, b) -> Integer.compare(a.val, b.val));
        for (ListNode head : lists) if (head != null) heap.add(head);
        ListNode dummy = new ListNode(0), tail = dummy;
        while (!heap.isEmpty()) {
            ListNode smallest = heap.poll();
            tail.next = smallest;
            tail = smallest;
            if (smallest.next != null) heap.add(smallest.next);
        }
        tail.next = null;
        return dummy.next;
    }

    /**
     * Running median of a stream using two heaps: {@code low} (max-heap) holds the smaller half and {@code high}
     * (min-heap) the larger half. Invariant: low.size() == high.size() or low.size() == high.size() + 1, and every
     * element of low is &lt;= every element of high. Then the median is low's root, or the mean of both roots.
     * add is O(log n); median is O(1).
     */
    public static final class MedianFinder {
        private final PriorityQueue<Integer> low = new PriorityQueue<>(Collections.reverseOrder());
        private final PriorityQueue<Integer> high = new PriorityQueue<>();

        public void add(int x) {
            low.add(x);
            high.add(low.poll());                  // push the largest of the small half across: keeps the order invariant
            if (high.size() > low.size()) low.add(high.poll()); // rebalance sizes
        }

        public double median() {
            if (low.isEmpty()) throw new IllegalStateException("no elements");
            return low.size() > high.size() ? low.peek() : ((double) low.peek() + high.peek()) / 2.0;
        }

        public int size() {
            return low.size() + high.size();
        }
    }

    /** Convenience for tests: all values of a list in order. */
    static List<Integer> values(ListNode head) {
        List<Integer> out = new ArrayList<>();
        for (ListNode n = head; n != null; n = n.next) out.add(n.val);
        return out;
    }
}
