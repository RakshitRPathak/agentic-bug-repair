package dev.rakshit.repair.synth;

import dev.rakshit.repair.graph.CodeGraph;
import dev.rakshit.repair.localize.Suspect;
import dev.rakshit.repair.trace.FailureReport;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Everything a synthesizer may use for one round.
 *
 * @param workspace the repository as it is now (the original plus any partial fix already accepted); diffs are against it
 * @param graph code graph of the original repository (used for context; line numbers may have drifted after accepted edits)
 * @param suspects ranked candidates for the buggy method
 * @param failures failing tests of the workspace as it is now
 * @param feedback the critic's report on the previous round, or empty
 * @param tried keys of candidates that were already verified, never to be proposed again
 * @param beam how many candidates to return
 * @param round 1-based round number
 */
public record SynthRequest(Path workspace, CodeGraph graph, List<Suspect> suspects, List<FailureReport> failures,
                           String feedback, Set<String> tried, int beam, int round) {}
