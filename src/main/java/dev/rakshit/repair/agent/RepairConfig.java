package dev.rakshit.repair.agent;

import dev.rakshit.repair.localize.FaultLocalizer;
import dev.rakshit.repair.metrics.CostModel;
import dev.rakshit.repair.sandbox.Limits;
import java.util.List;

/**
 * @param maxIterations self-correction iterations after the first attempt (the design calls for 3)
 * @param beam candidates verified per round
 * @param parallelism sandboxes running at once
 * @param maxPatchLines most lines a patch may add plus remove
 */
public record RepairConfig(List<String> testCommand, Limits limits, int maxIterations, int beam, int parallelism,
                           FaultLocalizer.Weights weights, int maxPatchLines, CostModel cost) {
    public RepairConfig {
        testCommand = List.copyOf(testCommand);
        if (maxIterations < 0 || beam < 1 || parallelism < 1 || maxPatchLines < 1) throw new IllegalArgumentException("bad config");
    }

    public static RepairConfig defaults() {
        return new RepairConfig(List.of("bash", "build.sh"), Limits.defaults().withTimeout(90_000), 3, 8,
                Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())), FaultLocalizer.Weights.DEFAULT, 40,
                CostModel.defaults());
    }

    public RepairConfig withBeam(int b) {
        return new RepairConfig(testCommand, limits, maxIterations, b, parallelism, weights, maxPatchLines, cost);
    }

    public RepairConfig withMaxIterations(int n) {
        return new RepairConfig(testCommand, limits, n, beam, parallelism, weights, maxPatchLines, cost);
    }

    public RepairConfig withParallelism(int p) {
        return new RepairConfig(testCommand, limits, maxIterations, beam, p, weights, maxPatchLines, cost);
    }

    public RepairConfig withWeights(FaultLocalizer.Weights w) {
        return new RepairConfig(testCommand, limits, maxIterations, beam, parallelism, w, maxPatchLines, cost);
    }

    public RepairConfig withTestCommand(List<String> c) {
        return new RepairConfig(c, limits, maxIterations, beam, parallelism, weights, maxPatchLines, cost);
    }

    public RepairConfig withLimits(Limits l) {
        return new RepairConfig(testCommand, l, maxIterations, beam, parallelism, weights, maxPatchLines, cost);
    }
}
