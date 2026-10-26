package dev.rakshit.repair.agent;

import static dev.rakshit.repair.testkit.Assert.assertEquals;
import static dev.rakshit.repair.testkit.Assert.assertFalse;
import static dev.rakshit.repair.testkit.Assert.assertThrows;
import static dev.rakshit.repair.testkit.Assert.assertTrue;

import dev.rakshit.repair.Fixtures;
import dev.rakshit.repair.graph.CodeGraph;
import dev.rakshit.repair.graph.JavaIndexer;
import dev.rakshit.repair.patch.UnifiedDiff;
import dev.rakshit.repair.testkit.Test;
import dev.rakshit.repair.trace.TestOutputParser;
import dev.rakshit.repair.trace.TestRun;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AgentTests {
    // ---------------------------------------------------------------------------------------- state machine

    @Test
    public void theHappyPathWalksEveryStateInOrder() {
        StateMachine<RepairState, RepairEvent> m = StateMachine.repairAgent();
        assertEquals(RepairState.INGEST, m.state());
        m.fire(RepairEvent.READY, "indexed");
        m.fire(RepairEvent.SUSPECTS_FOUND, "3 suspects");
        m.fire(RepairEvent.CANDIDATES_READY, "8");
        m.fire(RepairEvent.ALL_TESTS_PASS, "green");
        m.fire(RepairEvent.PUBLISHED, "pr");
        assertEquals(RepairState.RESOLVED, m.state());
        assertEquals(5, m.history().size());
        assertEquals("indexed", m.history().get(0).reason());
        assertEquals(RepairState.INGEST, m.history().get(0).from());
    }

    @Test
    public void theCriticLoopCanRepeat() {
        StateMachine<RepairState, RepairEvent> m = StateMachine.repairAgent();
        m.fire(RepairEvent.READY, "");
        m.fire(RepairEvent.SUSPECTS_FOUND, "");
        for (int i = 0; i < 3; i++) {
            m.fire(RepairEvent.CANDIDATES_READY, "");
            m.fire(RepairEvent.STILL_FAILING, "");
            m.fire(RepairEvent.RETRY, "");
        }
        assertEquals(RepairState.SYNTHESIZE, m.state());
        m.fire(RepairEvent.NO_CANDIDATES, "");
        m.fire(RepairEvent.OUT_OF_BUDGET, "");
        assertEquals(RepairState.UNRESOLVED, m.state());
    }

    @Test
    public void illegalEventsThrowAndLeaveTheStateAlone() {
        StateMachine<RepairState, RepairEvent> m = StateMachine.repairAgent();
        assertThrows(IllegalStateException.class, () -> m.fire(RepairEvent.ALL_TESTS_PASS, "skipping ahead"));
        assertEquals(RepairState.INGEST, m.state());
        assertTrue(m.history().isEmpty());
    }

    @Test
    public void terminalStatesHaveNoWayOut() {
        for (RepairState terminal : EnumSet.of(RepairState.RESOLVED, RepairState.UNRESOLVED)) {
            assertTrue(terminal.terminal());
            for (var t : StateMachine.repairAgent().definition()) assertFalse(t.from() == terminal, terminal + " must not have outgoing edges");
        }
    }

    @Test
    public void everyWorkingStateCanFailIntoUnresolvedAndEveryStateIsReachable() {
        var def = StateMachine.repairAgent().definition();
        for (RepairState s : RepairState.values()) {
            if (s.terminal()) continue;
            assertTrue(def.stream().anyMatch(t -> t.from() == s && t.event() == RepairEvent.ERROR && t.to() == RepairState.UNRESOLVED), "no ERROR edge from " + s);
        }
        Set<RepairState> seen = EnumSet.of(RepairState.INGEST);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (var t : def) if (seen.contains(t.from()) && seen.add(t.to())) grew = true;
        }
        assertEquals(EnumSet.allOf(RepairState.class), seen);
    }

    @Test
    public void duplicateTransitionsAreRejectedAtBuildTime() {
        assertThrows(IllegalArgumentException.class, () -> new StateMachine.Builder<>(RepairState.class, RepairEvent.class, RepairState.INGEST)
                .on(RepairState.INGEST, RepairEvent.READY, RepairState.LOCALIZE)
                .on(RepairState.INGEST, RepairEvent.READY, RepairState.VERIFY));
    }

    // ------------------------------------------------------------------------------------------- patch policy

    private static CodeGraph graph() throws Exception {
        return JavaIndexer.index(Fixtures.demo(Fixtures.tempDir("pol-"), Map.of()));
    }

    private static String edit(String path, String text, String from, String to) {
        return UnifiedDiff.create(path, text, text.replace(from, to));
    }

    @Test
    public void aSmallEditToASourceFileIsAllowed() throws Exception {
        PatchPolicy.check(edit("src/main/java/demo/Stats.java", Fixtures.STATS, "x > t", "x >= t"), graph(), 40);
    }

    @Test
    public void editingATestIsRefused() throws Exception {
        String d = edit("src/test/java/demo/StatsTests.java", Fixtures.STATS_TESTS, "assertEquals(1, Stats.countAbove", "assertEquals(2, Stats.countAbove");
        PatchPolicy.Violation v = null;
        try {
            PatchPolicy.check(d, graph(), 40);
        } catch (PatchPolicy.Violation e) {
            v = e;
        }
        assertTrue(v != null && v.getMessage().contains("tests"), String.valueOf(v));
    }

    @Test
    public void editingTheBuildScriptOrAnUnknownFileIsRefused() throws Exception {
        for (String path : List.of("build.sh", ".github/workflows/ci.yml", "src/main/java/demo/Missing.java")) {
            String d = UnifiedDiff.create(path, "a\nb\n", "a\nc\n");
            boolean refused = false;
            try {
                PatchPolicy.check(d, graph(), 40);
            } catch (PatchPolicy.Violation e) {
                refused = true;
            }
            assertTrue(refused, path + " must be refused");
        }
    }

    @Test
    public void oversizedPatchesAreRefusedWithAHint() throws Exception {
        String big = Fixtures.STATS.replace("int c = 0;", "int c = 0; int d1 = 1;").replace("return m;", "return m + 0;");
        String d = UnifiedDiff.create("src/main/java/demo/Stats.java", Fixtures.STATS, big);
        PatchPolicy.check(d, graph(), 40);
        try {
            PatchPolicy.check(d, graph(), 3);
            assertTrue(false, "expected a violation");
        } catch (PatchPolicy.Violation e) {
            assertTrue(e.getMessage().contains("smaller"), e.getMessage());
        }
    }

    @Test
    public void garbageIsAViolationNotACrash() throws Exception {
        try {
            PatchPolicy.check("--- a/x\n+++ b/x\n@@ nonsense @@\n", graph(), 40);
            assertTrue(false, "expected a violation");
        } catch (PatchPolicy.Violation e) {
            assertTrue(e.getMessage().contains("malformed"), e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------- verdict and critic

    private static final String BASE = """
              FAIL  A.broken  ->  java.lang.AssertionError: expected <1> but was <2>
              PASS  A.fine
              PASS  A.alsoFine
            """;

    private static Verdict verdict(String after) {
        return Verifier.evaluate(TestOutputParser.parse(after, false), TestOutputParser.parse(BASE, false), 5);
    }

    @Test
    public void aFullFixPasses() {
        Verdict v = verdict("  PASS  A.broken\n  PASS  A.fine\n  PASS  A.alsoFine\n");
        assertEquals(Set.of("A.broken"), v.fixed());
        assertTrue(v.broken().isEmpty());
        assertTrue(v.passes());
        assertTrue(v.improves());
    }

    @Test
    public void aFixThatBreaksSomethingElseIsARegression() {
        Verdict v = verdict("  PASS  A.broken\n  FAIL  A.fine  ->  java.lang.AssertionError: boom\n  PASS  A.alsoFine\n");
        assertEquals(Set.of("A.fine"), v.broken());
        assertFalse(v.passes());
        assertFalse(v.improves());
        assertTrue(Critic.describe("flip >", v).contains("REGRESSION"));
    }

    @Test
    public void aPartialFixImprovesWithoutPassing() {
        String base2 = "  FAIL  A.one  ->  java.lang.AssertionError: x\n  FAIL  A.two  ->  java.lang.AssertionError: y\n  PASS  A.ok\n";
        Verdict v = Verifier.evaluate(TestOutputParser.parse("  PASS  A.one\n  FAIL  A.two  ->  java.lang.AssertionError: y\n  PASS  A.ok\n", false),
                TestOutputParser.parse(base2, false), 1);
        assertEquals(Set.of("A.one"), v.fixed());
        assertFalse(v.passes());
        assertTrue(v.improves());
        String c = Critic.describe("candidate", v);
        assertTrue(c.contains("Now passing: A.one") && c.contains("Still failing") && c.contains("A.two"), c);
    }

    @Test
    public void aCandidateThatDoesNotCompileBreaksEverythingAndSaysWhy() {
        Verdict v = verdict("src/main/java/p/A.java:3: error: ';' expected\n1 error\n");
        assertFalse(v.compiled());
        assertEquals(Set.of("A.fine", "A.alsoFine"), v.broken());
        assertFalse(v.improves());
        assertTrue(Critic.describe("typo", v).contains("does not compile") && Critic.describe("typo", v).contains("';' expected"));
    }

    @Test
    public void newCompilerWarningsDisqualifyAnOtherwiseGreenRun() {
        Verdict v = verdict("src/main/java/p/A.java:3: warning: [rawtypes] raw\n  PASS  A.broken\n  PASS  A.fine\n  PASS  A.alsoFine\n");
        assertEquals(1, v.lintDelta());
        assertFalse(v.passes());
        assertTrue(Critic.describe("x", v).contains("new compiler warning"));
    }

    @Test
    public void aDiffThatDoesNotApplyIsReportedWithoutRunningAnything() {
        Verdict v = new Verdict("hunk 1 does not match at line 4", null, Set.of(), Set.of(), 0, 1);
        assertFalse(v.applied());
        assertFalse(v.passes());
        assertTrue(Critic.describe("bad diff", v).contains("did not apply: hunk 1 does not match"));
    }

    @Test
    public void aTimedOutRunCannotImprove() {
        TestRun base = TestOutputParser.parse(BASE, false);
        Verdict v = Verifier.evaluate(TestOutputParser.parse("  PASS  A.broken\n  PASS  A.fine\n  START A.alsoFine\n", true), base, 1);
        assertFalse(v.improves());
        assertTrue(Critic.describe("loop", v).contains("timed out"));
    }
}
