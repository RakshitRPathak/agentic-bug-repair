package dev.rakshit.repair.metrics;

/**
 * pass@k as defined in "Evaluating Large Language Models Trained on Code" (Chen et al., 2021): the probability that at
 * least one of k samples drawn without replacement from n generated samples, c of which are correct, is correct.
 * {@code 1 - C(n-c, k) / C(n, k)}, computed as a product so it cannot overflow.
 */
public final class PassAtK {
    private PassAtK() {}

    public static double estimate(int n, int c, int k) {
        if (n < 1 || c < 0 || c > n || k < 1 || k > n) throw new IllegalArgumentException("need 1 <= k <= n and 0 <= c <= n");
        if (n - c < k) return 1.0;
        double allWrong = 1.0;
        for (int i = n - c + 1; i <= n; i++) allWrong *= 1.0 - (double) k / i;
        return 1.0 - allWrong;
    }
}
