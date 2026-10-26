package dev.rakshit.repair.synth;

import java.io.IOException;
import java.util.List;

public interface PatchSynthesizer {
    /** The candidates (best first) and the model usage they cost. */
    record Proposal(List<Candidate> candidates, Usage usage, List<String> notes) {
        public Proposal(List<Candidate> candidates, Usage usage) {
            this(candidates, usage, List.of());
        }
    }

    String name();

    Proposal propose(SynthRequest request) throws IOException;
}
