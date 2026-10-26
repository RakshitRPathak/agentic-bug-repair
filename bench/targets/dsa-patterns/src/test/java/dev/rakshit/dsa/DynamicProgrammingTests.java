package dev.rakshit.dsa;

import static dev.rakshit.dsa.testkit.Assert.assertEquals;
import static dev.rakshit.dsa.testkit.Assert.assertThrows;

import dev.rakshit.dsa.testkit.Test;
import java.util.Arrays;
import java.util.Random;

/** Each DP is checked against the plain recursive or exhaustive definition of the same problem. */
public class DynamicProgrammingTests {
    private static final int TRIALS = 1500;

    @Test
    void climbStairsMatchesRecursionAndDoesNotOverflowAtNinetyThree() {
        for (int n = 0; n <= 20; n++) assertEquals(fib(n + 1), DynamicProgramming.climbStairs(n));
        assertEquals(7540113804746346429L, DynamicProgramming.climbStairs(91));   // F(92), the largest Fibonacci number that fits in a long
        assertThrows(IllegalArgumentException.class, () -> DynamicProgramming.climbStairs(-1));
    }

    private static long fib(int n) {
        return n < 2 ? n : fib(n - 1) + fib(n - 2);
    }

    @Test
    void houseRobberMatchesExhaustiveSubsets() {
        Random rnd = new Random(41);
        for (int t = 0; t < TRIALS; t++) {
            int[] m = Gen.ints(rnd, 10, 0, 9);
            int best = 0;
            for (int mask = 0; mask < (1 << m.length); mask++) {
                if ((mask & (mask >> 1)) != 0) continue;              // two adjacent houses chosen
                int sum = 0;
                for (int i = 0; i < m.length; i++) if ((mask >> i & 1) != 0) sum += m[i];
                best = Math.max(best, sum);
            }
            assertEquals(best, DynamicProgramming.houseRobber(m));
        }
    }

    @Test
    void coinChangeMatchesRecursionAndGreedyIsDemonstrablyWrong() {
        Random rnd = new Random(42);
        for (int t = 0; t < TRIALS; t++) {
            int[] coins = Arrays.stream(Gen.ints(rnd, 4, 1, 7)).distinct().toArray();
            int amount = rnd.nextInt(20);
            assertEquals(minCoins(coins, amount), DynamicProgramming.coinChange(coins, amount));
        }
        assertEquals(2, DynamicProgramming.coinChange(new int[] {1, 3, 4}, 6));     // 3 + 3; greedy would take 4 + 1 + 1
        assertEquals(-1, DynamicProgramming.coinChange(new int[] {2}, 3));
        assertEquals(0, DynamicProgramming.coinChange(new int[] {5}, 0));
        assertEquals(-1, DynamicProgramming.coinChange(new int[0], 5));
    }

    private static int minCoins(int[] coins, int amount) {
        if (amount == 0) return 0;
        int best = -1;
        for (int c : coins) {
            if (c > amount) continue;
            int sub = minCoins(coins, amount - c);
            if (sub >= 0 && (best < 0 || sub + 1 < best)) best = sub + 1;
        }
        return best;
    }

    @Test
    void knapsackMatchesExhaustiveSubsetsAndUsesEachItemOnce() {
        Random rnd = new Random(43);
        for (int t = 0; t < TRIALS; t++) {
            int n = rnd.nextInt(8);
            int[] w = new int[n], v = new int[n];
            for (int i = 0; i < n; i++) {
                w[i] = 1 + rnd.nextInt(6);
                v[i] = rnd.nextInt(10);
            }
            int cap = rnd.nextInt(15);
            int best = 0;
            for (int mask = 0; mask < (1 << n); mask++) {
                int ws = 0, vs = 0;
                for (int i = 0; i < n; i++) if ((mask >> i & 1) != 0) { ws += w[i]; vs += v[i]; }
                if (ws <= cap) best = Math.max(best, vs);
            }
            assertEquals(best, DynamicProgramming.knapsack01(w, v, cap));
        }
        // One item of weight 2, value 5, capacity 6: unbounded would give 15, 0/1 must give 5.
        assertEquals(5, DynamicProgramming.knapsack01(new int[] {2}, new int[] {5}, 6));
        assertThrows(IllegalArgumentException.class, () -> DynamicProgramming.knapsack01(new int[] {1}, new int[0], 3));
    }

    @Test
    void longestIncreasingSubsequenceMatchesQuadraticDp() {
        Random rnd = new Random(44);
        for (int t = 0; t < TRIALS; t++) {
            int[] a = Gen.ints(rnd, 14, 0, 6);
            int[] best = new int[a.length];
            int answer = 0;
            for (int i = 0; i < a.length; i++) {
                best[i] = 1;
                for (int j = 0; j < i; j++) if (a[j] < a[i]) best[i] = Math.max(best[i], best[j] + 1);
                answer = Math.max(answer, best[i]);
            }
            assertEquals(answer, DynamicProgramming.longestIncreasingSubsequence(a));
        }
        // Runs in O(n log n): a quadratic version would take minutes on this input.
        int[] big = new int[200_000];
        for (int i = 0; i < big.length; i++) big[i] = i;
        assertEquals(200_000, DynamicProgramming.longestIncreasingSubsequence(big));
    }

    @Test
    void lcsAndEditDistanceMatchMemoisedRecursion() {
        Random rnd = new Random(45);
        for (int t = 0; t < TRIALS; t++) {
            String a = Gen.string(rnd, 7, 3), b = Gen.string(rnd, 7, 3);
            assertEquals(lcs(a, b, a.length(), b.length()), DynamicProgramming.longestCommonSubsequence(a, b));
            assertEquals(edit(a, b, a.length(), b.length()), DynamicProgramming.editDistance(a, b));
        }
        assertEquals(3, DynamicProgramming.editDistance("kitten", "sitting"));
        assertEquals(5, DynamicProgramming.editDistance("", "abcde"));
        assertEquals(0, DynamicProgramming.editDistance("same", "same"));
    }

    private static int lcs(String a, String b, int i, int j) {
        if (i == 0 || j == 0) return 0;
        if (a.charAt(i - 1) == b.charAt(j - 1)) return 1 + lcs(a, b, i - 1, j - 1);
        return Math.max(lcs(a, b, i - 1, j), lcs(a, b, i, j - 1));
    }

    private static int edit(String a, String b, int i, int j) {
        if (i == 0) return j;
        if (j == 0) return i;
        if (a.charAt(i - 1) == b.charAt(j - 1)) return edit(a, b, i - 1, j - 1);
        return 1 + Math.min(edit(a, b, i - 1, j - 1), Math.min(edit(a, b, i - 1, j), edit(a, b, i, j - 1)));
    }

    @Test
    void uniquePathsMatchesRecursionWithObstacles() {
        Random rnd = new Random(46);
        for (int t = 0; t < TRIALS; t++) {
            int rows = 1 + rnd.nextInt(5), cols = 1 + rnd.nextInt(5);
            int[][] g = new int[rows][cols];
            for (int[] row : g) for (int c = 0; c < cols; c++) row[c] = rnd.nextInt(4) == 0 ? 1 : 0;
            assertEquals(paths(g, 0, 0), DynamicProgramming.uniquePathsWithObstacles(g));
        }
        assertEquals(0L, DynamicProgramming.uniquePathsWithObstacles(new int[][] {{1}}));
        assertEquals(10L, DynamicProgramming.uniquePathsWithObstacles(new int[3][4]));   // C(5, 2): 2 downs and 3 rights in any order
    }

    private static long paths(int[][] g, int r, int c) {
        if (r >= g.length || c >= g[0].length || g[r][c] == 1) return 0;
        if (r == g.length - 1 && c == g[0].length - 1) return 1;
        return paths(g, r + 1, c) + paths(g, r, c + 1);
    }
}
