package dev.rakshit.dsa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * PATTERN: intervals ("sort by start, then sweep").
 *
 * <p><b>Recognise it when:</b> the input is a list of [start, end] ranges (meetings, bookings, jobs) and you
 * must merge them, count overlaps, or pick the most that fit.
 *
 * <p><b>Why it works:</b> once sorted by start time, any interval can only overlap the ones immediately before it
 * in the sweep, so a single pass with one piece of running state (current merged interval, a heap of end times)
 * is enough. The sort costs O(n log n) and dominates.
 *
 * <p>Convention used here: intervals are closed [start, end] for merging (so [1,2] and [2,3] merge), and
 * half-open [start, end) for scheduling (a meeting ending at 5 does not conflict with one starting at 5).
 */
public final class Intervals {
    private Intervals() {}

    /** Merges all overlapping or touching intervals. */
    public static int[][] merge(int[][] intervals) {
        if (intervals.length == 0) return new int[0][];
        int[][] sorted = intervals.clone();
        Arrays.sort(sorted, Comparator.comparingInt(iv -> iv[0]));
        List<int[]> out = new ArrayList<>();
        int[] current = sorted[0].clone();
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i][0] <= current[1]) current[1] = Math.max(current[1], sorted[i][1]); // overlaps: extend
            else {
                out.add(current);
                current = sorted[i].clone();
            }
        }
        out.add(current);
        return out.toArray(new int[0][]);
    }

    /**
     * Inserts {@code added} into a sorted list of non-overlapping intervals and merges. Three phases in one pass:
     * copy everything that ends before it, absorb everything that overlaps it, copy the rest.
     */
    public static int[][] insert(int[][] sortedIntervals, int[] added) {
        List<int[]> out = new ArrayList<>();
        int i = 0, n = sortedIntervals.length;
        while (i < n && sortedIntervals[i][1] < added[0]) out.add(sortedIntervals[i++].clone());
        int[] merged = added.clone();
        while (i < n && sortedIntervals[i][0] <= merged[1]) {
            merged[0] = Math.min(merged[0], sortedIntervals[i][0]);
            merged[1] = Math.max(merged[1], sortedIntervals[i][1]);
            i++;
        }
        out.add(merged);
        while (i < n) out.add(sortedIntervals[i++].clone());
        return out.toArray(new int[0][]);
    }

    /**
     * Minimum number of meeting rooms so that no two overlapping meetings share one (half-open meetings).
     * Sweep meetings by start time; a min-heap holds the end times of rooms in use. If the earliest-ending room is
     * free by the time this meeting starts, reuse it (pop); then occupy a room (push). The heap's final size is the
     * maximum number of rooms ever busy at once.
     */
    public static int minMeetingRooms(int[][] meetings) {
        int[][] sorted = meetings.clone();
        Arrays.sort(sorted, Comparator.comparingInt(m -> m[0]));
        PriorityQueue<Integer> endTimes = new PriorityQueue<>();
        for (int[] m : sorted) {
            if (!endTimes.isEmpty() && endTimes.peek() <= m[0]) endTimes.poll();
            endTimes.add(m[1]);
        }
        return endTimes.size();
    }

    /**
     * Minimum number of half-open intervals to remove so the rest do not overlap. GREEDY: this equals
     * n minus the largest set of non-overlapping intervals, and that set is built by always keeping the interval that
     * <em>ends earliest</em>, since it leaves the most room for the rest. Sort by end time.
     */
    public static int minRemovalsToAvoidOverlap(int[][] intervals) {
        if (intervals.length == 0) return 0;
        int[][] sorted = intervals.clone();
        Arrays.sort(sorted, Comparator.comparingInt(iv -> iv[1]));
        int kept = 1;
        int lastEnd = sorted[0][1];
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i][0] >= lastEnd) {
                kept++;
                lastEnd = sorted[i][1];
            }
        }
        return intervals.length - kept;
    }
}
