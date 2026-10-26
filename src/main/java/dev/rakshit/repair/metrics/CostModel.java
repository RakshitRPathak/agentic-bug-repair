package dev.rakshit.repair.metrics;

import dev.rakshit.repair.synth.Usage;

/** Converts token usage to dollars at configured per-million-token prices. The defaults are placeholders, not a price list. */
public record CostModel(double inputUsdPerMTok, double outputUsdPerMTok) {
    public static CostModel defaults() {
        return new CostModel(3.0, 15.0);
    }

    public double cost(Usage u) {
        return u.inputTokens() / 1e6 * inputUsdPerMTok + u.outputTokens() / 1e6 * outputUsdPerMTok;
    }
}
