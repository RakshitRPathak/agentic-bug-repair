package dev.rakshit.repair.agent;

import static dev.rakshit.repair.testkit.Assert.assertEquals;
import static dev.rakshit.repair.testkit.Assert.assertFalse;
import static dev.rakshit.repair.testkit.Assert.assertTrue;

import dev.rakshit.repair.Fixtures;
import dev.rakshit.repair.patch.UnifiedDiff;
import dev.rakshit.repair.sandbox.Limits;
import dev.rakshit.repair.sandbox.ProcessSandbox;
import dev.rakshit.repair.synth.Candidate;
import dev.rakshit.repair.synth.MutationSynthesizer;
import dev.rakshit.repair.synth.PatchSynthesizer;
import dev.rakshit.repair.synth.SynthRequest;
import dev.rakshit.repair.synth.Usage;
import dev.rakshit.repair.testkit.Test;
import dev.rakshit.repair.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class PipelineTests {
    private static final String STATS = "src/main/java/demo/Stats.java";

    private static RepairConfig cfg() {
        return RepairConfig.defaults().withParallelism(2).withBeam(8).withLimits(new Limits(60_000, 1024, 128, 1 << 20));
    }

    /** A synthesizer driven by a script, recording what it was asked. */
    private static final class Scripted implements PatchSynthesizer {
        interface Step {
            List<Candidate> apply(SynthRequest r) throws IOException;
        }

        final List<Step> steps;
        final List<SynthRequest> requests = new ArrayList<>();

        Scripted(Step... steps) {
            this.steps = List.of(steps);
        }

        @Override
        public String name() {
            return "scripted";
        }

        @Override
        public Proposal propose(SynthRequest r) throws IOException {
            requests.add(r);
            List<Candidate> c = steps.get(Math.min(requests.size(), steps.size()) - 1).apply(r);
            return new Proposal(c, new Usage(1, 100, 10));
        }
    }

    private static Candidate candidate(SynthRequest r, String file, String from, String to, String key) throws IOException {
        String text = Files.readString(r.workspace().resolve(file));
        return new Candidate(key, "SCRIPT", from + " -> " + to, "demo.Stats#x()", file, 1, UnifiedDiff.create(file, text, text.replace(from, to)));
    }

    private static Path bugged(String from, String to) throws Exception {
        return Fixtures.demo(Fixtures.tempDir("pipe-"), Map.of(STATS, Fixtures.replace(Fixtures.STATS, from, to)));
    }

    private static String fingerprint(Path dir) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                md.update(dir.relativize(p).toString().getBytes(StandardCharsets.UTF_8));
                md.update(Files.readAllBytes(p));
            }
        }
        return java.util.HexFormat.of().formatHex(md.digest());
    }

    private static List<RepairState> path(RepairRun run) {
        List<RepairState> s = new ArrayList<>();
        for (var t : run.machine().history()) s.add(t.to());
        return s;
    }

    // ------------------------------------------------------------------------------------------------ end to end

    @Test
    public void repairsASeededBugEndToEndAndTheDiffIsVerifiedAndApplicableWithGit() throws Exception {
        Path repo = bugged("if (x > t) c++;", "if (x >= t) c++;");
        String before = fingerprint(repo);
        RepairRun run = new RepairPipeline(cfg(), new ProcessSandbox(), new MutationSynthesizer()).run(repo, "seeded");

        assertTrue(run.resolved(), run.outcome() + " " + path(run));
        assertEquals(RepairState.RESOLVED, run.state());
        assertEquals(before, fingerprint(repo));                         // the caller's checkout is never touched
        assertTrue(run.diff().contains("-            if (x >= t) c++;") && run.diff().contains("+            if (x > t) c++;"), run.diff());
        assertEquals(List.of("StatsTests.countAboveIsStrict"), run.failingTests().stream().map(s -> s.substring(0, s.indexOf(':'))).toList());
        assertTrue(run.prMarkdown().contains("countAbove"), run.prMarkdown());
        assertTrue(run.sandboxRuns() >= 3, "baseline + candidates + final re-verification: " + run.sandboxRuns());

        // the published diff is a real patch: git accepts it on the original checkout, and applying it turns the suite green
        Path copy = Fixtures.tempDir("gitcheck-");
        dev.rakshit.repair.util.Fs.copyTree(repo, copy);
        Files.writeString(copy.resolve("fix.patch"), run.diff());
        assertEquals(0, new ProcessBuilder("git", "apply", "--check", "fix.patch").directory(copy.toFile()).inheritIO().start().waitFor());
        assertEquals(0, new ProcessBuilder("git", "apply", "fix.patch").directory(copy.toFile()).inheritIO().start().waitFor());
        var res = new ProcessSandbox().run(copy, List.of("bash", "build.sh"), new Limits(60_000, 1024, 128, 1 << 20));
        assertTrue(res.output().contains("failed: 0"), res.output());

        List<RepairState> walk = path(run);
        assertEquals(RepairState.LOCALIZE, walk.get(0));
        assertTrue(walk.contains(RepairState.VERIFY) && walk.contains(RepairState.PUBLISH) && walk.get(walk.size() - 1) == RepairState.RESOLVED, walk.toString());
    }

    @Test
    public void jsonViewOfAFinishedRunCarriesWhatTheDashboardNeeds() throws Exception {
        Path repo = bugged("if (x > t) c++;", "if (x >= t) c++;");
        RepairRun run = new RepairPipeline(cfg(), new ProcessSandbox(), new MutationSynthesizer()).run(repo, "json");
        Map<String, Object> j = Json.parseObject(Json.write(run.toJson()));
        assertEquals("RESOLVED", j.get("state"));
        assertEquals(Boolean.TRUE, j.get("finished"));
        assertTrue(((List<?>) j.get("events")).size() >= 5);
        assertTrue(((List<?>) j.get("machine")).size() > 10, "the state machine definition ships with the run so the UI draws what runs");
        assertFalse(((List<?>) j.get("suspects")).isEmpty());
        assertFalse(((List<?>) j.get("attempts")).isEmpty());
        assertTrue(Json.path(j, "graphView", "nodes") instanceof List<?> n && !n.isEmpty());
        assertTrue(Json.path(j, "blast", "tests") instanceof List<?>);
        assertTrue(Json.path(j, "telemetry", "sandboxRuns") instanceof Number);
        assertTrue(((String) j.get("diff")).startsWith("--- a/"));
    }

    // ------------------------------------------------------------------------------------------ the critic loop

    @Test
    public void theCriticsReportReachesTheNextRound() throws Exception {
        Path repo = bugged("if (x > t) c++;", "if (x >= t) c++;");
        Scripted synth = new Scripted(
                r -> List.of(candidate(r, STATS, "if (x >= t) c++;", "if (x != t) c++;", "wrong-1")),   // compiles, still fails
                r -> List.of(candidate(r, STATS, "if (x >= t) c++;", "if (x > t) c++;", "right-2")));
        RepairRun run = new RepairPipeline(cfg(), new ProcessSandbox(), synth).run(repo, "critic");
        assertTrue(run.resolved(), run.outcome());
        assertEquals(2, synth.requests.size());
        assertEquals("", synth.requests.get(0).feedback());
        String fb = synth.requests.get(1).feedback();
        assertTrue(fb.contains("Candidate: if (x >= t) c++; -> if (x != t) c++;") && fb.contains("Still failing") && fb.contains("countAboveIsStrict"), fb);
        assertEquals(2, synth.requests.get(1).round());
        assertTrue(synth.requests.get(1).tried().contains("wrong-1"));
        assertEquals(2, run.round());
        assertEquals(2, run.attempts().size());
    }

    @Test
    public void aFixThatBreaksAnotherTestIsNeverAccepted() throws Exception {
        // making `countAbove` return 1 passes countAboveIsStrict but breaks countAboveNone
        Path repo = bugged("if (x > t) c++;", "if (x >= t) c++;");
        Scripted synth = new Scripted(r -> List.of(candidate(r, STATS, "        return c;\n    }\n}", "        return 1;\n    }\n}", "cheat")));
        RepairRun run = new RepairPipeline(cfg().withMaxIterations(1), new ProcessSandbox(), synth).run(repo, "regress");
        assertFalse(run.resolved());
        assertEquals(RepairState.UNRESOLVED, run.state());
        var attempt = run.attempts().get(0);
        assertEquals(Boolean.FALSE, attempt.get("passes"));
        assertTrue(((Number) attempt.get("broken")).intValue() >= 1, attempt.toString());
        assertTrue(synth.requests.get(1).feedback().contains("REGRESSION"), synth.requests.get(1).feedback());
        assertTrue(run.diff() == null || run.diff().isEmpty());
    }

    @Test
    public void patchesThatTouchTestsAreRejectedBeforeTheyReachTheSandbox() throws Exception {
        Path repo = bugged("if (x > t) c++;", "if (x >= t) c++;");
        String testFile = "src/test/java/demo/StatsTests.java";
        Scripted synth = new Scripted(r -> List.of(candidate(r, testFile, "assertEquals(1, Stats.countAbove(new int[] {1, 5, 9}, 5));",
                "assertEquals(2, Stats.countAbove(new int[] {1, 5, 9}, 5));", "edit-the-test")));
        RepairRun run = new RepairPipeline(cfg().withMaxIterations(1), new ProcessSandbox(), synth).run(repo, "policy");
        assertFalse(run.resolved());
        assertTrue(run.attempts().isEmpty(), "nothing was verified");
        assertEquals(1, run.sandboxRuns(), "only the baseline ran");
        assertTrue(synth.requests.get(1).feedback().contains("patches may not modify tests"), synth.requests.get(1).feedback());
    }

    @Test
    public void aPartialFixIsKeptAndTheNextRoundBuildsOnIt() throws Exception {
        // two independent bugs; no single candidate fixes both
        String twoBugs = Fixtures.replace(Fixtures.replace(Fixtures.STATS, "if (x > t) c++;", "if (x >= t) c++;"), "if (a[i] > m) m = a[i];", "if (a[i] < m) m = a[i];");
        Path repo = Fixtures.demo(Fixtures.tempDir("two-"), Map.of(STATS, twoBugs));
        Scripted synth = new Scripted(
                r -> List.of(candidate(r, STATS, "if (x >= t) c++;", "if (x > t) c++;", "fix-count")),
                r -> List.of(candidate(r, STATS, "if (a[i] < m) m = a[i];", "if (a[i] > m) m = a[i];", "fix-max")));
        RepairRun run = new RepairPipeline(cfg(), new ProcessSandbox(), synth).run(repo, "partial");
        assertTrue(run.resolved(), run.outcome());
        assertEquals(1, run.acceptedPartials().size());
        assertTrue(synth.requests.get(1).workspace().toString().contains("repair-work-"));
        assertTrue(run.diff().contains("+            if (x > t) c++;") && run.diff().contains("+            if (a[i] > m) m = a[i];"), run.diff());
        assertEquals(1L, run.diff().lines().filter(l -> l.startsWith("--- ")).count(), "one file, one combined diff against the pristine checkout");
        // the combined diff applies to the ORIGINAL repo, not to the intermediate workspace
        Path copy = Fixtures.tempDir("combined-");
        dev.rakshit.repair.util.Fs.copyTree(repo, copy);
        UnifiedDiff.applyTo(copy, run.diff());
        assertTrue(Files.readString(copy.resolve(STATS)).equals(Fixtures.STATS));
    }

    @Test
    public void aPartialFixInOneFileAndTheWinnerInAnotherAreComposedIntoOneDiff() throws Exception {
        String report = Fixtures.replace(Fixtures.REPORT, "Stats.countAbove(a, (int) Stats.mean(a))", "Stats.countAbove(a, (int) Stats.mean(a) * 2)");
        Path repo = Fixtures.demo(Fixtures.tempDir("twofiles-"), Map.of(
                STATS, Fixtures.replace(Fixtures.STATS, "if (x > t) c++;", "if (x >= t) c++;"),
                "src/main/java/demo/Report.java", report));
        String reportPath = "src/main/java/demo/Report.java";
        Scripted synth = new Scripted(
                r -> List.of(candidate(r, STATS, "if (x >= t) c++;", "if (x > t) c++;", "fix-stats")),
                r -> List.of(candidate(r, reportPath, "(int) Stats.mean(a) * 2", "(int) Stats.mean(a)", "fix-report")));
        RepairRun run = new RepairPipeline(cfg(), new ProcessSandbox(), synth).run(repo, "twofiles");
        assertTrue(run.resolved(), run.outcome());
        assertEquals(1, run.acceptedPartials().size());
        assertTrue(run.diff().contains("--- a/" + STATS) && run.diff().contains("--- a/" + reportPath), run.diff());
        Path copy = Fixtures.tempDir("twofiles-apply-");
        dev.rakshit.repair.util.Fs.copyTree(repo, copy);
        UnifiedDiff.applyTo(copy, run.diff());
        assertEquals(Fixtures.STATS, Files.readString(copy.resolve(STATS)));
        assertEquals(Fixtures.REPORT, Files.readString(copy.resolve(reportPath)));
    }

    @Test
    public void theFinalDiffIsVerifiedAgainAndAFailureThereStopsThePublication() throws Exception {
        Path repo = bugged("if (x > t) c++;", "if (x >= t) c++;");
        // behaves for the baseline and the candidate, then reports a red suite for the clean-checkout re-verification
        ProcessSandbox real = new ProcessSandbox();
        int[] calls = {0};
        dev.rakshit.repair.sandbox.Sandbox flaky = new dev.rakshit.repair.sandbox.Sandbox() {
            @Override
            public dev.rakshit.repair.sandbox.SandboxResult run(Path r, List<String> c, Limits l) throws IOException {
                if (++calls[0] < 3) return real.run(r, c, l);
                return new dev.rakshit.repair.sandbox.SandboxResult(1, "  FAIL  StatsTests.countAboveIsStrict  ->  java.lang.AssertionError: flaky\n  PASS  StatsTests.maxOfSeveral\n", false, false, false, 1);
            }

            @Override
            public String describe() {
                return real.describe();
            }
        };
        Scripted synth = new Scripted(r -> List.of(candidate(r, STATS, "if (x >= t) c++;", "if (x > t) c++;", "right")));
        RepairRun run = new RepairPipeline(cfg(), flaky, synth).run(repo, "reverify");
        assertFalse(run.resolved());
        assertEquals(3, calls[0]);
        assertTrue(run.outcome().contains("re-verification"), run.outcome());
        assertTrue(run.diff() == null || run.diff().isEmpty());
        assertTrue(run.prMarkdown() == null || run.prMarkdown().isEmpty());
    }

    @Test
    public void aCandidateThatLoopsForeverIsKilledAndReportedNotWaitedFor() throws Exception {
        Path repo = bugged("if (x > t) c++;", "if (x >= t) c++;");
        Scripted synth = new Scripted(r -> List.of(candidate(r, STATS, "int c = 0;", "int c = 0;\n        while (c == 0) { }", "loop")));
        long t0 = System.nanoTime();
        RepairRun run = new RepairPipeline(cfg().withMaxIterations(0).withLimits(new Limits(30_000, 1024, 128, 1 << 20)), new ProcessSandbox(), synth).run(repo, "hang");
        long s = (System.nanoTime() - t0) / 1_000_000_000L;
        assertFalse(run.resolved());
        assertEquals(Boolean.TRUE, run.attempts().get(0).get("timedOut"), run.attempts().toString());
        assertTrue(s < 40, "the adaptive timeout kicks in, took " + s + " s");
    }

    // ---------------------------------------------------------------------------------------------- bail-outs

    @Test
    public void aGreenRepositoryHasNothingToFix() throws Exception {
        RepairRun run = new RepairPipeline(cfg(), new ProcessSandbox(), new MutationSynthesizer()).run(Fixtures.demo(Fixtures.tempDir("green-"), Map.of()), "green");
        assertEquals(RepairState.UNRESOLVED, run.state());
        assertTrue(run.outcome().contains("nothing to fix"), run.outcome());
        assertEquals(List.of(RepairState.UNRESOLVED), path(run));
    }

    @Test
    public void aRepositoryThatDoesNotCompileIsReportedAsSuch() throws Exception {
        Path repo = bugged("return c;", "return c");
        RepairRun run = new RepairPipeline(cfg(), new ProcessSandbox(), new MutationSynthesizer()).run(repo, "broken");
        assertEquals(RepairState.UNRESOLVED, run.state());
        assertTrue(run.outcome().contains("does not compile"), run.outcome());
    }

    @Test
    public void aSynthesizerThatThrowsEndsTheRunCleanlyInUnresolved() throws Exception {
        Path repo = bugged("if (x > t) c++;", "if (x >= t) c++;");
        PatchSynthesizer boom = new PatchSynthesizer() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public Proposal propose(SynthRequest r) throws IOException {
                throw new IOException("model API returned 529");
            }
        };
        RepairRun run = new RepairPipeline(cfg(), new ProcessSandbox(), boom).run(repo, "error");
        assertEquals(RepairState.UNRESOLVED, run.state());
        assertTrue(run.outcome().contains("529"), run.outcome());
        assertTrue(run.finished());
    }

    @Test
    public void exhaustingTheBudgetEndsUnresolvedAfterTheConfiguredNumberOfRounds() throws Exception {
        Path repo = bugged("if (x > t) c++;", "if (x >= t) c++;");
        int[] n = {0};
        Scripted synth = new Scripted(r -> List.of(candidate(r, STATS, "if (x >= t) c++;", "if (x != t + " + (n[0]++) + ") c++;", "w" + n[0])));
        RepairRun run = new RepairPipeline(cfg().withMaxIterations(2), new ProcessSandbox(), synth).run(repo, "budget");
        assertFalse(run.resolved());
        assertEquals(3, synth.requests.size());
        assertTrue(run.outcome().contains("no verified fix after 3 round"), run.outcome());
    }
}
