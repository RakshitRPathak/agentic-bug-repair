package dev.rakshit.dsa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PATTERN: two pointers.
 *
 * <p><b>Recognise it when:</b> the input is a sorted array (or can be sorted), or you are asked for a pair or
 * triple with some property, or to partition or compact an array in place.
 *
 * <p><b>Why it works:</b> sortedness gives each pointer move a meaning. Moving the left pointer right can only
 * make the sum larger; moving the right pointer left can only make it smaller. So at every step one move is
 * provably useless, and we never have to try every pair. That turns O(n^2) into O(n).
 *
 * <p><b>Template:</b> {@code l = 0, r = n-1; while (l < r) { decide; move exactly one pointer }}.
 */
public final class TwoPointers {
    private TwoPointers() {}

    /**
     * Indices {@code {i, j}} (i &lt; j) of two values in sorted array {@code a} summing to {@code target}, or null.
     * Sum too small: only a larger left value can help, so l++. Too large: r--.
     */
    public static int[] twoSumSorted(int[] a, int target) {
        int l = 0, r = a.length - 1;
        while (l < r) {
            long sum = (long) a[l] + a[r];
            if (sum == target) return new int[] {l, r};
            if (sum < target) l++;
            else r--;
        }
        return null;
    }

    /**
     * All unique triplets (as sorted lists) that sum to zero.
     * Sort, fix the first value, then run twoSumSorted on the rest. Skipping equal neighbours avoids duplicates
     * without needing a set.
     */
    public static List<List<Integer>> threeSum(int[] input) {
        int[] a = input.clone();
        Arrays.sort(a);
        List<List<Integer>> out = new ArrayList<>();
        for (int i = 0; i < a.length - 2; i++) {
            if (a[i] > 0) break;                          // everything after is positive: no more zero sums
            if (i > 0 && a[i] == a[i - 1]) continue;      // same first value as before: same triplets
            int l = i + 1, r = a.length - 1;
            while (l < r) {
                long sum = (long) a[i] + a[l] + a[r];
                if (sum < 0) {
                    l++;
                } else if (sum > 0) {
                    r--;
                } else {
                    out.add(List.of(a[i], a[l], a[r]));
                    l++;
                    r--;
                    while (l < r && a[l] == a[l - 1]) l++;
                    while (l < r && a[r] == a[r + 1]) r--;
                }
            }
        }
        return out;
    }

    /**
     * Largest water container: max over i&lt;j of min(h[i], h[j]) * (j - i).
     * Start with the widest pair. The shorter wall limits the height, and moving the taller wall inward can only
     * shrink the width without raising the limit, so the only move that might improve things is the shorter wall.
     */
    public static int maxArea(int[] h) {
        int l = 0, r = h.length - 1, best = 0;
        while (l < r) {
            best = Math.max(best, Math.min(h[l], h[r]) * (r - l));
            if (h[l] < h[r]) l++;
            else r--;
        }
        return best;
    }

    /**
     * Rain water trapped between bars. Water above bar i is min(maxLeft, maxRight) - h[i].
     * The side with the smaller running maximum already knows its bound is the limiting one, so we can finish
     * that side without knowing the other side's exact maximum.
     */
    public static int trap(int[] h) {
        int l = 0, r = h.length - 1, leftMax = 0, rightMax = 0, water = 0;
        while (l < r) {
            if (h[l] < h[r]) {
                leftMax = Math.max(leftMax, h[l]);
                water += leftMax - h[l];
                l++;
            } else {
                rightMax = Math.max(rightMax, h[r]);
                water += rightMax - h[r];
                r--;
            }
        }
        return water;
    }

    /**
     * Removes duplicates from a sorted array in place and returns the new length (slow/fast pointers).
     * {@code slow} marks the end of the deduplicated prefix; {@code fast} scans ahead.
     */
    public static int removeDuplicatesSorted(int[] a) {
        if (a.length == 0) return 0;
        int slow = 0;
        for (int fast = 1; fast < a.length; fast++) {
            if (a[fast] != a[slow]) a[++slow] = a[fast];
        }
        return slow + 1;
    }

    /**
     * Sorts an array of 0s, 1s and 2s in one pass (Dutch national flag).
     * Invariant: a[0..lo) are 0s, a[lo..i) are 1s, a(hi..end] are 2s, a[i..hi] unknown.
     */
    public static void sortColors(int[] a) {
        int lo = 0, i = 0, hi = a.length - 1;
        while (i <= hi) {
            if (a[i] == 0) swap(a, lo++, i++);
            else if (a[i] == 2) swap(a, i, hi--);   // do not advance i: the swapped-in value is unexamined
            else i++;
        }
    }

    private static void swap(int[] a, int i, int j) {
        int t = a[i];
        a[i] = a[j];
        a[j] = t;
    }
}
