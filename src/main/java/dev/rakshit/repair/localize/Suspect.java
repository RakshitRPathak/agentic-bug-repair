package dev.rakshit.repair.localize;

import java.util.Map;

/** A method that may contain the bug, with the evidence for it. {@code signals} are each in 0..1. */
public record Suspect(String id, double score, int rank, Map<String, Double> signals) {
    public Suspect {
        signals = Map.copyOf(signals);
    }
}
