package dev.rakshit.repair.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Percentile {
    private Percentile() {}

    /** Nearest-rank percentile: the smallest value that at least p% of the values are less than or equal to. */
    public static long nearestRank(List<Long> values, double p) {
        if (values.isEmpty()) return 0;
        if (p <= 0 || p > 100) throw new IllegalArgumentException("p must be in (0, 100]");
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(1, rank) - 1);
    }
}
