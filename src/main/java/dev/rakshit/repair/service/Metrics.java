package dev.rakshit.repair.service;

import dev.rakshit.repair.agent.RepairRun;
import dev.rakshit.repair.metrics.Percentile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregates over finished runs: how often repairs succeed, how fast, and what they cost. */
public final class Metrics {
    private Metrics() {}

    public static Map<String, Object> summarize(List<RepairRun> runs) {
        List<RepairRun> done = new ArrayList<>();
        for (RepairRun r : runs) if (r.finished()) done.add(r);
        int resolved = 0;
        long inTok = 0, outTok = 0;
        double cost = 0;
        int sandboxRuns = 0;
        List<Long> latencies = new ArrayList<>(), resolvedLatencies = new ArrayList<>();
        int[] within = new int[3];
        int[] ks = {1, 3, 10};
        for (RepairRun r : done) {
            latencies.add(r.latencyMs());
            inTok += r.usage().inputTokens();
            outTok += r.usage().outputTokens();
            cost += r.costUsd();
            sandboxRuns += r.sandboxRuns();
            if (r.resolved()) {
                resolved++;
                resolvedLatencies.add(r.latencyMs());
                int verified = r.attempts().size();
                for (int i = 0; i < ks.length; i++) if (verified <= ks[i]) within[i]++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runs", runs.size());
        m.put("finished", done.size());
        m.put("resolved", resolved);
        m.put("resolveRate", done.isEmpty() ? 0.0 : (double) resolved / done.size());
        m.put("latencyP50Ms", Percentile.nearestRank(latencies, 50));
        m.put("latencyP95Ms", Percentile.nearestRank(latencies, 95));
        m.put("resolvedLatencyP95Ms", Percentile.nearestRank(resolvedLatencies, 95));
        m.put("inputTokens", inTok);
        m.put("outputTokens", outTok);
        m.put("costUsd", cost);
        m.put("sandboxRuns", sandboxRuns);
        // fraction of finished runs fixed with at most k candidates verified: the ranking quality of the whole pipeline
        for (int i = 0; i < ks.length; i++) m.put("solvedWithin" + ks[i], done.isEmpty() ? 0.0 : (double) within[i] / done.size());
        return m;
    }
}
