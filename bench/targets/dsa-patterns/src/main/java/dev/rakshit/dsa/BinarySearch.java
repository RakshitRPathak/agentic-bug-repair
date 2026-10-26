package dev.rakshit.dsa;

/**
 * PATTERN: binary search, on an index or on the answer.
 *
 * <p><b>Recognise it when:</b> the data is sorted or rotated-sorted, or you are asked for the
 * "minimum X such that condition holds" and the condition is <em>monotonic</em>: once true for some X it stays
 * true for every larger X ("can we ship everything within D days with capacity C?" is true for all bigger C).
 *
 * <p><b>The one template to memorise</b> (find the first index in [lo, hi) where the predicate is true):
 * <pre>
 *   while (lo &lt; hi) { mid = lo + (hi - lo) / 2; if (predicate(mid)) hi = mid; else lo = mid + 1; }
 *   return lo;     // first true, or hi if none
 * </pre>
 * It never needs "mid +/- 1" juggling or special cases, and {@code lo + (hi - lo) / 2} cannot overflow.
 */
public final class BinarySearch {
    private BinarySearch() {}

    /** First index {@code i} with a[i] &gt;= x (a.length if none). Sorted ascending input. */
    public static int lowerBound(int[] a, int x) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (a[mid] >= x) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    /** First index {@code i} with a[i] &gt; x (a.length if none). {@code upperBound - lowerBound} counts occurrences of x. */
    public static int upperBound(int[] a, int x) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (a[mid] > x) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    /** {@code {first, last}} index of x in sorted {@code a}, or {-1, -1}. */
    public static int[] firstAndLast(int[] a, int x) {
        int first = lowerBound(a, x);
        if (first == a.length || a[first] != x) return new int[] {-1, -1};
        return new int[] {first, upperBound(a, x) - 1};
    }

    /**
     * Index of {@code target} in a sorted array of distinct values that was rotated, or -1.
     * At every step one half [lo..mid] or [mid..hi] is still properly sorted; check whether the target lies in the
     * sorted half and discard the other half.
     */
    public static int searchRotated(int[] a, int target) {
        int lo = 0, hi = a.length - 1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;
            if (a[mid] == target) return mid;
            if (a[lo] <= a[mid]) {                                   // left half is sorted
                if (a[lo] <= target && target < a[mid]) hi = mid - 1;
                else lo = mid + 1;
            } else {                                                 // right half is sorted
                if (a[mid] < target && target <= a[hi]) lo = mid + 1;
                else hi = mid - 1;
            }
        }
        return -1;
    }

    /**
     * Smallest value in a rotated sorted array of distinct values. Predicate: "a[mid] is less than or equal to
     * the last element" is false on the left (larger) part and true from the minimum onward.
     */
    public static int findMinRotated(int[] a) {
        int lo = 0, hi = a.length - 1;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (a[mid] <= a[hi]) hi = mid;
            else lo = mid + 1;
        }
        return a[lo];
    }

    /**
     * BINARY SEARCH ON THE ANSWER. Minimum ship capacity so that packages (in order) go out within {@code days}.
     * The answer lies between the heaviest single package and the total weight. {@link #daysNeeded} is monotonic
     * in capacity, so search for the first capacity that fits.
     */
    public static int shipWithinDays(int[] weights, int days) {
        int lo = 0, hi = 0;
        for (int w : weights) {
            lo = Math.max(lo, w);
            hi += w;
        }
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (daysNeeded(weights, mid) <= days) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    private static int daysNeeded(int[] weights, int capacity) {
        int days = 1, load = 0;
        for (int w : weights) {
            if (load + w > capacity) {
                days++;
                load = 0;
            }
            load += w;
        }
        return days;
    }

    /** Slowest eating speed (bananas/hour) that finishes every pile within {@code hours}. Each pile takes ceil(p/speed). */
    public static int minEatingSpeed(int[] piles, int hours) {
        int lo = 1, hi = 1;
        for (int p : piles) hi = Math.max(hi, p);
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            long needed = 0;
            for (int p : piles) needed += (p + mid - 1) / mid;
            if (needed <= hours) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    /** Integer square root (floor) without floating point: the largest r with r*r &lt;= x. */
    public static int sqrt(int x) {
        long lo = 0, hi = (long) x + 1;       // search for the first r where r*r > x, then subtract one
        while (lo < hi) {
            long mid = lo + (hi - lo) / 2;
            if (mid * mid > x) hi = mid;
            else lo = mid + 1;
        }
        return (int) (lo - 1);
    }
}
