package dev.rakshit.repair.synth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Cheap first, expensive later: the first {@code cheapRounds} rounds use the free offline synthesizer; if it has nothing
 * to offer, or its candidates did not work, the model is asked, with the critic's report in the prompt. Most small bugs
 * are fixed before a single token is spent.
 *
 * <p>If the model fails (server down, misconfigured, timing out), the round falls back to whatever the offline
 * synthesizer still has untried, with a note, instead of ending the run: a model outage must not lose bugs the free
 * synthesizer can fix. {@link #modelErrors()} counts these so a benchmark can refuse to report them as model results.
 */
public final class CascadeSynthesizer implements PatchSynthesizer {
    private final PatchSynthesizer cheap, expensive;
    private final int cheapRounds;
    private int modelErrors;

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
        Proposal model;
        try {
            model = expensive.propose(req);
        } catch (IOException e) {
            modelErrors++;
            Proposal p = cheap.propose(req);
            if (p.candidates().isEmpty()) throw e;
            List<String> notes = new ArrayList<>(p.notes());
            notes.add("model unavailable (" + e.getMessage() + "); round " + req.round() + " used the offline synthesizer");
            return new Proposal(p.candidates(), p.usage(), notes);
        }
        // The model's candidates go first, but the offline search keeps going behind them: verifying a mutation costs
        // one test run and no tokens, and some single-edit bugs only surface after round 2 (the first laptop run with
        // qwen2.5-coder:7b lost bug 03 that way while the model fixed bug 05, a bug mutation cannot fix).
        Proposal offline = cheap.propose(req);
        if (offline.candidates().isEmpty()) return model;
        List<Candidate> merged = new ArrayList<>(model.candidates());
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Candidate c : merged) keys.add(c.key());
        for (Candidate c : offline.candidates()) if (keys.add(c.key())) merged.add(c);
        List<String> notes = new ArrayList<>(model.notes());
        notes.addAll(offline.notes());
        return new Proposal(merged, model.usage(), notes);
    }

    /** How many rounds the model failed to answer. */
    public int modelErrors() {
        return modelErrors;
    }
}
