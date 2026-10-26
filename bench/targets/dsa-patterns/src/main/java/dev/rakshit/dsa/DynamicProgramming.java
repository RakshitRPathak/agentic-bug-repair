package dev.rakshit.dsa;

import java.util.Arrays;

/**
 * PATTERN: dynamic programming ("the answer for the whole is built from answers for smaller parts").
 *
 * <p><b>Recognise it when:</b> a brute force would recompute the same sub-question many times
 * (overlapping sub-problems) and the best answer is made of best answers to smaller ones (optimal substructure).
 *
 * <p><b>Four steps that work every time:</b>
 * <ol>
 *   <li>State: what do the indices of the table mean? ("dp[i] = best answer using the first i items")</li>
 *   <li>Transition: how does dp[i] follow from smaller states?</li>
 *   <li>Base cases: what is the answer for the smallest inputs?</li>
 *   <li>Order: fill so every dependency is computed first; then see if you only need the last row, to save space.</li>
 * </ol>
 */
public final class DynamicProgramming {
    private DynamicProgramming() {}

    /** Ways to climb n steps taking 1 or 2 at a time (Fibonacci). Only the last two values are needed. O(n) time, O(1) space. */
    public static long climbStairs(int n) {
        if (n < 0) throw new IllegalArgumentException("n < 0");
        long prev = 1, cur = 1;                        // ways(0) = 1, ways(1) = 1
        for (int i = 2; i <= n; i++) {
            long next = prev + cur;
            prev = cur;
            cur = next;
        }
        return cur;
    }

    /** Max money robbing non-adjacent houses. dp: best(i) = max(best(i-1) skip, best(i-2) + money[i] rob). */
    public static int houseRobber(int[] money) {
        int skip = 0, take = 0;                        // best up to i-2, best up to i-1
        for (int m : money) {
            int best = Math.max(take, skip + m);
            skip = take;
            take = best;
        }
        return take;
    }

    /**
     * Fewest coins to make {@code amount}, or -1. UNBOUNDED knapsack: dp[a] = 1 + min(dp[a - coin]).
     * Greedy (largest coin first) is wrong in general: coins {1,3,4} for 6 needs 2 coins (3+3), greedy gives 3 (4+1+1).
     */
    public static int coinChange(int[] coins, int amount) {
        if (amount < 0) return -1;
        int inf = amount + 1;                          // larger than any real answer
        int[] dp = new int[amount + 1];
        Arrays.fill(dp, inf);
        dp[0] = 0;
        for (int a = 1; a <= amount; a++) {
            for (int coin : coins) {
                if (coin > 0 && coin <= a && dp[a - coin] + 1 < dp[a]) dp[a] = dp[a - coin] + 1;
            }
        }
        return dp[amount] >= inf ? -1 : dp[amount];
    }

    /**
     * 0/1 knapsack: max value with total weight at most {@code capacity}, each item used at most once.
     * One-dimensional table; the capacity loop runs <em>downwards</em> so an item cannot be counted twice
     * (an upward loop would turn this into the unbounded version).
     */
    public static int knapsack01(int[] weights, int[] values, int capacity) {
        if (weights.length != values.length) throw new IllegalArgumentException("length mismatch");
        int[] dp = new int[capacity + 1];
        for (int i = 0; i < weights.length; i++) {
            for (int c = capacity; c >= weights[i]; c--) {
                dp[c] = Math.max(dp[c], dp[c - weights[i]] + values[i]);
            }
        }
        return dp[capacity];
    }

    /**
     * Length of the longest strictly increasing subsequence in O(n log n). {@code tails[k]} is the smallest possible
     * last value of an increasing subsequence of length k+1; it stays sorted, so binary search finds where each number goes.
     * (tails is NOT itself the subsequence, only its length is meaningful.)
     */
    public static int longestIncreasingSubsequence(int[] nums) {
        int[] tails = new int[nums.length];
        int size = 0;
        for (int x : nums) {
            int lo = 0, hi = size;                      // first tail >= x: replace it (strict increase)
            while (lo < hi) {
                int mid = lo + (hi - lo) / 2;
                if (tails[mid] >= x) hi = mid;
                else lo = mid + 1;
            }
            int pos = lo;
            tails[pos] = x;
            if (pos == size) size++;
        }
        return size;
    }

    /** Length of the longest common subsequence of two strings. dp[i][j] over prefixes; two rolling rows are enough. */
    public static int longestCommonSubsequence(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = a.charAt(i - 1) == b.charAt(j - 1) ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** Minimum single-character inserts, deletes and replaces to turn {@code a} into {@code b} (Levenshtein distance). */
    public static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;          // empty a -> b: j inserts
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;                                              // a[0..i) -> empty: i deletes
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) cur[j] = prev[j - 1];
                else cur[j] = 1 + Math.min(prev[j - 1], Math.min(prev[j], cur[j - 1])); // replace, delete, insert
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** Number of paths from the top-left to the bottom-right of a grid moving only right or down, avoiding obstacles (1 = blocked). */
    public static long uniquePathsWithObstacles(int[][] grid) {
        if (grid.length == 0 || grid[0].length == 0) return 0;
        long[] dp = new long[grid[0].length];
        dp[0] = grid[0][0] == 1 ? 0 : 1;
        for (int[] row : grid) {
            for (int c = 0; c < row.length; c++) {
                if (row[c] == 1) dp[c] = 0;
                else if (c > 0) dp[c] += dp[c - 1];                   // from above (dp[c] already) plus from the left
            }
        }
        return dp[dp.length - 1];
    }
}
