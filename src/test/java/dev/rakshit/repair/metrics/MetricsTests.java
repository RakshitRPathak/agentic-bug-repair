package dev.rakshit.repair.metrics;

import static dev.rakshit.repair.testkit.Assert.assertEquals;
import static dev.rakshit.repair.testkit.Assert.assertInRange;
import static dev.rakshit.repair.testkit.Assert.assertThrows;
import static dev.rakshit.repair.testkit.Assert.assertTrue;

import dev.rakshit.repair.synth.Usage;
import dev.rakshit.repair.testkit.Test;
import java.util.List;

public class MetricsTests {
    private static void near(double actual, double expected, String what) {
        assertInRange(actual, expected - 1e-9, expected + 1e-9, what);
    }

    private static double choose(int n, int k) {
        double r = 1;
        for (int i = 1; i <= k; i++) r = r * (n - k + i) / i;
        return r;
    }

    @Test
    public void passAtKMatchesTheDefinitionOnSmallCases() {
        near(PassAtK.estimate(10, 3, 1), 0.3, "pass@1 is the plain success rate");
        near(PassAtK.estimate(5, 2, 3), 0.9, "1 - C(3,3)/C(5,3)");
        for (int n = 1; n <= 12; n++) {
            for (int c = 0; c <= n; c++) {
                for (int k = 1; k <= n; k++) {
                    double direct = 1.0 - (n - c < k ? 0.0 : choose(n - c, k) / choose(n, k));
                    near(PassAtK.estimate(n, c, k), direct, "n=" + n + " c=" + c + " k=" + k);
                }
            }
        }
    }

    @Test
    public void passAtKIsMonotoneInKAndCAndStaysInRange() {
        for (int c = 0; c <= 20; c++) {
            double prev = -1;
            for (int k = 1; k <= 20; k++) {
                double v = PassAtK.estimate(20, c, k);
                assertTrue(v >= prev - 1e-12 && v >= 0 && v <= 1, "k=" + k + " c=" + c + " v=" + v);
                prev = v;
            }
        }
        assertEquals(0.0, PassAtK.estimate(7, 0, 4));
        assertEquals(1.0, PassAtK.estimate(7, 7, 1));
    }

    @Test
    public void passAtKDoesNotOverflowForLargeSampleCounts() {
        double v = PassAtK.estimate(100_000, 50_000, 50);
        assertTrue(v > 0.999999 && v <= 1.0, String.valueOf(v));
    }

    @Test
    public void passAtKRejectsNonsense() {
        assertThrows(IllegalArgumentException.class, () -> PassAtK.estimate(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> PassAtK.estimate(5, 6, 1));
        assertThrows(IllegalArgumentException.class, () -> PassAtK.estimate(5, 1, 6));
        assertThrows(IllegalArgumentException.class, () -> PassAtK.estimate(5, 1, 0));
    }

    @Test
    public void nearestRankPercentilesFollowTheTextbookDefinition() {
        List<Long> v = List.of(15L, 20L, 35L, 40L, 50L);
        assertEquals(20L, Percentile.nearestRank(v, 30));
        assertEquals(20L, Percentile.nearestRank(v, 40));
        assertEquals(35L, Percentile.nearestRank(v, 50));
        assertEquals(50L, Percentile.nearestRank(v, 100));
        assertEquals(50L, Percentile.nearestRank(v, 95));
        List<Long> hundred = new java.util.ArrayList<>();
        for (long i = 100; i >= 1; i--) hundred.add(i);                  // unsorted input is fine
        assertEquals(95L, Percentile.nearestRank(hundred, 95));
        assertEquals(50L, Percentile.nearestRank(hundred, 50));
        assertEquals(0L, Percentile.nearestRank(List.of(), 95));
        assertEquals(7L, Percentile.nearestRank(List.of(7L), 95));
        assertThrows(IllegalArgumentException.class, () -> Percentile.nearestRank(v, 0));
        assertThrows(IllegalArgumentException.class, () -> Percentile.nearestRank(v, 101));
    }

    @Test
    public void costIsTokensTimesPricePerMillion() {
        CostModel c = new CostModel(3.0, 15.0);
        near(c.cost(new Usage(2, 1_000_000, 100_000)), 4.5, "cost");
        near(c.cost(Usage.NONE), 0, "free");
        assertEquals(new Usage(3, 30, 6), new Usage(1, 10, 2).plus(new Usage(2, 20, 4)));
    }
}
