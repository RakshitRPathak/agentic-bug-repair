package dev.rakshit.repair.synth;

import java.io.IOException;

/**
 * Cheap first, expensive later: the first {@code cheapRounds} rounds use the free offline synthesizer; if it has nothing
 * to offer, or its candidates did not work, the model is asked, with the critic's report in the prompt. Most small bugs
 * are fixed before a single token is spent.
 */
public final class CascadeSynthesizer implements PatchSynthesizer {
    private final PatchSynthesizer cheap, expensive;
    private final int cheapRounds;

    public CascadeSynthesizer(PatchSynthesizer cheap, PatchSynthesizer expensive, int cheapRounds) {
        this.cheap = cheap;
        this.expensive = expensive;
        this.cheapRounds = cheapRounds;
    }

    @Override
    public String name() {
        return cheap.name() + ", then " + expensive.name() + " after round " + cheapRounds;
    }

    @Override
    public Proposal propose(SynthRequest req) throws IOException {
        if (req.round() <= cheapRounds) {
            Proposal p = cheap.propose(req);
            if (!p.candidates().isEmpty()) return p;
        }
        return expensive.propose(req);
    }
}
