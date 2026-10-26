package dev.rakshit.repair.agent;

import dev.rakshit.repair.patch.UnifiedDiff;
import dev.rakshit.repair.sandbox.Limits;
import dev.rakshit.repair.sandbox.Sandbox;
import dev.rakshit.repair.sandbox.SandboxResult;
import dev.rakshit.repair.trace.FailureReport;
import dev.rakshit.repair.trace.TestOutputParser;
import dev.rakshit.repair.trace.TestRun;
import dev.rakshit.repair.util.Fs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Applies a diff to a throwaway copy, runs the repository's test command in the sandbox, and compares with the baseline. */
public final class Verifier {
    private final Sandbox sandbox;
    private final List<String> command;
    private final Limits limits;
    private volatile long baselineMs;

    public Verifier(Sandbox sandbox, List<String> command, Limits limits) {
        this.sandbox = sandbox;
        this.command = List.copyOf(command);
        this.limits = limits;
    }

    public Sandbox sandbox() {
        return sandbox;
    }

    /** Runs the unmodified repository: which tests fail, which pass, how many warnings. */
    public TestRun baseline(Path repo) throws IOException {
        SandboxResult r = sandbox.run(repo, command, limits);
        baselineMs = r.durationMs();
        return TestOutputParser.parse(r.output(), r.timedOut());
    }

    /** How long the unmodified test run took; candidates get a multiple of this as their timeout. */
    public long baselineMs() {
        return baselineMs;
    }

    public Verifier withLimits(Limits other) {
        return new Verifier(sandbox, command, other);
    }

    /** {@code workspace} is not modified. */
    public Verdict verify(Path workspace, String diff, TestRun baseline) throws IOException {
        long t0 = System.nanoTime();
        Path copy = Files.createTempDirectory("repair-verify-");
        try {
            Fs.copyTree(workspace, copy);
            try {
                UnifiedDiff.applyTo(copy, diff);
            } catch (UnifiedDiff.PatchException e) {
                return new Verdict(e.getMessage(), null, Set.of(), Set.of(), 0, elapsed(t0));
            }
            SandboxResult r = sandbox.run(copy, command, limits);
            TestRun run = TestOutputParser.parse(r.output(), r.timedOut() || r.killedForProcessLimit());
            return evaluate(run, baseline, elapsed(t0));
        } finally {
            Fs.deleteTree(copy);
        }
    }

    static Verdict evaluate(TestRun run, TestRun baseline, long ms) {
        Set<String> fixed = new HashSet<>(), broken = new HashSet<>();
        if (run.compiled()) {
            for (FailureReport f : baseline.failed()) if (run.passed().contains(f.testName())) fixed.add(f.testName());
            for (String t : baseline.passed()) if (!run.passed().contains(t)) broken.add(t);
        } else {
            broken.addAll(baseline.passed());
        }
        return new Verdict(null, run, fixed, broken, run.warnings() - baseline.warnings(), ms);
    }

    private static long elapsed(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
