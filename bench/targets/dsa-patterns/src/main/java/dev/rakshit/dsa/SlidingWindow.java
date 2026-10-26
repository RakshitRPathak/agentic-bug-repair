package dev.rakshit.dsa;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * PATTERN: sliding window.
 *
 * <p><b>Recognise it when:</b> you need the best <em>contiguous</em> subarray or substring under a constraint
 * ("longest with at most K distinct", "shortest containing all of T", "max of every window of size K").
 *
 * <p><b>Why it works:</b> instead of testing every (start, end) pair, keep one window [l, r] and grow r
 * every step. When the window becomes invalid, shrink from l until it is valid again. Each index enters and
 * leaves the window at most once, so the total work is O(n), not O(n^2).
 *
 * <p><b>Two shapes:</b> (1) <i>longest valid window</i>: expand always, shrink only while invalid, record the
 * length after fixing. (2) <i>shortest valid window</i>: expand until valid, then shrink while still valid,
 * recording the length at each valid state.
 */
public final class SlidingWindow {
    private SlidingWindow() {}

    /** Length of the longest substring with no repeated character. Shape 1; the window holds distinct chars. */
    public static int longestUniqueSubstring(String s) {
        Map<Character, Integer> lastSeen = new HashMap<>();
        int best = 0, l = 0;
        for (int r = 0; r < s.length(); r++) {
            Integer prev = lastSeen.put(s.charAt(r), r);
            if (prev != null && prev >= l) l = prev + 1; // jump l past the earlier copy instead of shrinking one by one
            best = Math.max(best, r - l + 1);
        }
        return best;
    }

    /** Length of the longest substring with at most {@code k} distinct characters. Shape 1. */
    public static int longestAtMostKDistinct(String s, int k) {
        if (k <= 0) return 0;
        Map<Character, Integer> counts = new HashMap<>();
        int best = 0, l = 0;
        for (int r = 0; r < s.length(); r++) {
            counts.merge(s.charAt(r), 1, Integer::sum);
            while (counts.size() > k) {                   // invalid: shrink until valid again
                char c = s.charAt(l++);
                if (counts.merge(c, -1, Integer::sum) == 0) counts.remove(c);
            }
            best = Math.max(best, r - l + 1);
        }
        return best;
    }

    /**
     * Shortest (leftmost on ties) substring of {@code s} containing every character of {@code t} with
     * multiplicity, or "" if none. Shape 2. {@code missing} counts how many required characters are still absent
     * from the window, so "is the window valid" is a constant-time check.
     */
    public static String minWindow(String s, String t) {
        if (t.isEmpty() || s.length() < t.length()) return "";
        Map<Character, Integer> need = new HashMap<>();
        for (char c : t.toCharArray()) need.merge(c, 1, Integer::sum);
        int missing = t.length();
        int bestStart = 0, bestLen = Integer.MAX_VALUE, l = 0;
        for (int r = 0; r < s.length(); r++) {
            char c = s.charAt(r);
            Integer n = need.get(c);
            if (n != null) {
                if (n > 0) missing--;
                need.put(c, n - 1);                        // may go negative: a surplus copy
            }
            while (missing == 0) {                         // valid: record, then shrink while it stays valid
                if (r - l + 1 < bestLen) {
                    bestLen = r - l + 1;
                    bestStart = l;
                }
                char lc = s.charAt(l++);
                Integer ln = need.get(lc);
                if (ln != null) {
                    need.put(lc, ln + 1);
                    if (ln + 1 > 0) missing++;
                }
            }
        }
        return bestLen == Integer.MAX_VALUE ? "" : s.substring(bestStart, bestStart + bestLen);
    }

    /**
     * Length of the shortest subarray with sum &gt;= target, or 0. Works because all values are positive:
     * growing the window only increases the sum, shrinking only decreases it. (With negatives this fails; use
     * prefix sums and a monotonic deque instead.)
     */
    public static int minSubArrayLen(int target, int[] nums) {
        int best = Integer.MAX_VALUE, l = 0;
        long sum = 0;
        for (int r = 0; r < nums.length; r++) {
            sum += nums[r];
            while (sum >= target) {
                best = Math.min(best, r - l + 1);
                sum -= nums[l++];
            }
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }

    /**
     * Maximum of every window of size {@code k}.
     *
     * <p>Monotonic deque of <em>indices</em> whose values are decreasing. The front is always the maximum of the
     * current window. A new value makes every smaller value before it useless (it is both newer and larger, so it
     * will outlive them and beat them), so those are popped from the back. Indices that left the window are
     * dropped from the front. Each index is pushed and popped once: O(n).
     */
    public static int[] maxSlidingWindow(int[] nums, int k) {
        if (k <= 0 || nums.length < k) return new int[0];
        int[] out = new int[nums.length - k + 1];
        Deque<Integer> dq = new ArrayDeque<>();
        for (int i = 0; i < nums.length; i++) {
            while (!dq.isEmpty() && dq.peekFirst() <= i - k) dq.pollFirst();
            while (!dq.isEmpty() && nums[dq.peekLast()] <= nums[i]) dq.pollLast();
            dq.addLast(i);
            if (i >= k - 1) out[i - k + 1] = nums[dq.peekFirst()];
        }
        return out;
    }
}
