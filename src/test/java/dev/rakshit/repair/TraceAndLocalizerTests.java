package dev.rakshit.repair;

import static dev.rakshit.repair.testkit.Assert.assertEquals;
import static dev.rakshit.repair.testkit.Assert.assertFalse;
import static dev.rakshit.repair.testkit.Assert.assertNull;
import static dev.rakshit.repair.testkit.Assert.assertTrue;

import dev.rakshit.repair.graph.CodeGraph;
import dev.rakshit.repair.graph.JavaIndexer;
import dev.rakshit.repair.localize.FaultLocalizer;
import dev.rakshit.repair.localize.GraphRag;
import dev.rakshit.repair.localize.Suspect;
import dev.rakshit.repair.retrieval.HybridRetriever;
import dev.rakshit.repair.testkit.Test;
import dev.rakshit.repair.trace.FailureReport;
import dev.rakshit.repair.trace.Frame;
import dev.rakshit.repair.trace.TestOutputParser;
import dev.rakshit.repair.trace.TestRun;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class TraceAndLocalizerTests {
    private static final String OUTPUT = """
              START StatsTests.countAboveIsStrict
              FAIL  StatsTests.countAboveIsStrict  ->  java.lang.AssertionError: expected <1> but was <2>
                    at dev.rakshit.repair.testkit.Assert.assertEquals(Assert.java:20)
                    at demo.StatsTests.countAboveIsStrict(StatsTests.java:25)
              PASS  StatsTests.countAboveNone
              PASS  StatsTests.maxOfSeveral
              PASS  StatsTests.meanOfThree
              PASS  ReportTests.outliersCountsValuesAboveTheMean
              PASS  ReportTests.summaryText

            passed: 5   failed: 1
            """;

    // ------------------------------------------------------------------------------------------------ parser

    @Test
    public void parsesPassesFailuresMessagesAndFrames() {
        TestRun run = TestOutputParser.parse(OUTPUT, false);
        assertEquals(5, run.passed().size());
        assertEquals(1, run.failed().size());
        FailureReport f = run.failed().get(0);
        assertEquals("StatsTests.countAboveIsStrict", f.testName());
        assertEquals("java.lang.AssertionError", f.exceptionType());
        assertEquals("expected <1> but was <2>", f.message());
        assertEquals(2, f.frames().size());
        assertEquals(new Frame("demo.StatsTests", "countAboveIsStrict", "StatsTests.java", 25), f.frames().get(1));
        assertEquals("StatsTests", f.testClass());
        assertEquals("countAboveIsStrict", f.testMethod());
        assertTrue(run.compiled());
        assertFalse(run.allGreen());
    }

    @Test
    public void countsCompileErrorsAndWarningsSeparately() {
        TestRun run = TestOutputParser.parse("""
                src/main/java/p/A.java:3: warning: [rawtypes] found raw type: List
                src/main/java/p/A.java:9: warning: [unchecked] unchecked call
                src/main/java/p/A.java:12: error: cannot find symbol
                  symbol: variable q
                1 error
                """, false);
        assertFalse(run.compiled());
        assertEquals(1, run.compileErrors().size());
        assertTrue(run.compileErrors().get(0).contains("cannot find symbol"));
        assertEquals(2, run.warnings());
    }

    @Test
    public void aTestThatNeverFinishedBeforeTheTimeoutIsReportedAsAFailure() {
        TestRun run = TestOutputParser.parse("  START A.one\n  PASS  A.one\n  START A.two\n", true);
        assertEquals(1, run.failed().size());
        assertEquals("A.two", run.failed().get(0).testName());
        assertEquals("Timeout", run.failed().get(0).exceptionType());
        assertTrue(run.timedOut());
        TestRun notTimedOut = TestOutputParser.parse("  START A.two\n", false);
        assertTrue(notTimedOut.failed().isEmpty(), "an unfinished test without a timeout is not invented as a failure");
    }

    @Test
    public void aRunnerThatDiesBeforeReportingIsACrashNotAGreenRun() {
        TestRun run = TestOutputParser.parse("""
                Exception in thread "main" java.lang.StackOverflowError
                \tat p.R.loop(R.java:7)
                \tat p.R.loop(R.java:7)
                """, false);
        assertTrue(run.crashed());
        assertEquals("<runner>", run.failed().get(0).testName());
        assertEquals("java.lang.StackOverflowError", run.failed().get(0).exceptionType());
        assertEquals(2, run.failed().get(0).frames().size());
        assertFalse(run.allGreen());
    }

    @Test
    public void allGreenNeedsPassingTestsNotJustAbsenceOfFailures() {
        assertFalse(TestOutputParser.parse("", false).allGreen());
        assertTrue(TestOutputParser.parse("  PASS  A.x\n", false).allGreen());
        assertFalse(TestOutputParser.parse("  PASS  A.x\n", true).allGreen());
    }

    @Test
    public void parsesLambdaAndInnerClassFrames() {
        Frame f = TestOutputParser.frame("        at p.Outer$Inner.lambda$run$0(Outer.java:44)");
        assertEquals("p.Outer$Inner", f.className());
        assertEquals("lambda$run$0", f.methodName());
        assertEquals(44, f.line());
        assertNull(TestOutputParser.frame("not a frame"));
    }

    // -------------------------------------------------------------------------------------------- localizer

    private static CodeGraph demoGraph() throws Exception {
        Path dir = Fixtures.demo(Fixtures.tempDir("loc-"), Map.of());
        return JavaIndexer.index(dir);
    }

    @Test
    public void spectrumFindsTheMethodOnlyTheFailingTestReaches() throws Exception {
        CodeGraph g = demoGraph();
        TestRun run = TestOutputParser.parse(OUTPUT, false);
        List<Suspect> s = FaultLocalizer.localize(g, run, new HybridRetriever(g), FaultLocalizer.Weights.SPECTRUM_ONLY);
        assertEquals(1, s.size());
        assertEquals("demo.Stats#countAbove(int[],int)", s.get(0).id());
        double ochiai = 1.0 / Math.sqrt(1.0 * (1 + 2));          // ef=1, ep=2 (countAboveNone, outliers...), F=1
        assertEquals(ochiai, s.get(0).signals().get("spectrum"));
    }

    @Test
    public void aMethodReachedByPassingTestsToo_scoresLowerThanOneOnlyFailuresReach() throws Exception {
        CodeGraph g = demoGraph();
        String out = """
                  FAIL  ReportTests.outliersCountsValuesAboveTheMean  ->  java.lang.AssertionError: expected <2> but was <3>
                  PASS  StatsTests.meanOfThree
                  PASS  StatsTests.countAboveNone
                  PASS  StatsTests.maxOfSeveral
                """;
        List<Suspect> s = FaultLocalizer.localize(g, TestOutputParser.parse(out, false), null, FaultLocalizer.Weights.SPECTRUM_ONLY);
        // outliers calls mean and countAbove. mean is also reached by a passing test, countAbove too; Report.outliers itself only by the failure
        assertEquals("demo.Report#outliers(int[])", s.get(0).id());
        assertTrue(s.get(0).score() > s.get(1).score());
    }

    @Test
    public void aCrashFrameInsideTheCodeIsResolvedToItsMethodByLine() throws Exception {
        CodeGraph g = demoGraph();
        String out = """
                  FAIL  StatsTests.maxOfSeveral  ->  java.lang.ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0
                        at demo.Stats.max(Stats.java:7)
                        at demo.StatsTests.maxOfSeveral(StatsTests.java:11)
                  PASS  StatsTests.meanOfThree
                """;
        TestRun run = TestOutputParser.parse(out, false);
        assertEquals("demo.Stats#max(int[])", FaultLocalizer.resolve(g, run.failed().get(0).frames().get(0)).id());
        List<Suspect> s = FaultLocalizer.localize(g, run, null, FaultLocalizer.Weights.TRACE_ONLY);
        assertEquals("demo.Stats#max(int[])", s.get(0).id());
        assertEquals(1.0, s.get(0).signals().get("frame"));
        assertEquals(1, s.size());
    }

    @Test
    public void anAssertionFailureGivesAStackTraceOnlyBaselineNothingToGoOn() throws Exception {
        CodeGraph g = demoGraph();
        List<Suspect> s = FaultLocalizer.localize(g, TestOutputParser.parse(OUTPUT, false), null, FaultLocalizer.Weights.TRACE_ONLY);
        assertTrue(s.isEmpty());
    }

    @Test
    public void textSignalPrefersMethodsTheFailingTestNames() throws Exception {
        CodeGraph g = demoGraph();
        List<Suspect> s = FaultLocalizer.localize(g, TestOutputParser.parse(OUTPUT, false), new HybridRetriever(g), FaultLocalizer.Weights.TEXT_ONLY);
        assertEquals("demo.Stats#countAbove(int[],int)", s.get(0).id());
    }

    @Test
    public void noFailuresMeansNoSuspects() throws Exception {
        CodeGraph g = demoGraph();
        assertTrue(FaultLocalizer.localize(g, TestOutputParser.parse("  PASS  StatsTests.maxOfSeveral\n", false), new HybridRetriever(g),
                FaultLocalizer.Weights.DEFAULT).isEmpty());
    }

    @Test
    public void defaultWeightsCombineSignalsAndRanksAreContiguous() throws Exception {
        CodeGraph g = demoGraph();
        List<Suspect> s = FaultLocalizer.localize(g, TestOutputParser.parse(OUTPUT, false), new HybridRetriever(g), FaultLocalizer.Weights.DEFAULT);
        assertEquals("demo.Stats#countAbove(int[],int)", s.get(0).id());
        for (int i = 0; i < s.size(); i++) {
            assertEquals(i + 1, s.get(i).rank());
            if (i > 0) assertTrue(s.get(i - 1).score() >= s.get(i).score());
            for (double v : s.get(i).signals().values()) assertTrue(v >= 0 && v <= 1.0000001, "signal in range: " + v);
        }
    }

    @Test
    public void graphRagContextCollectsCallersCalleesAndTests() throws Exception {
        CodeGraph g = demoGraph();
        GraphRag.Context ctx = GraphRag.context(g, "demo.Stats#countAbove(int[],int)", 2);
        assertEquals(List.of("demo.Report#outliers(int[])"), ctx.callers().stream().map(n -> n.id()).toList());
        assertTrue(ctx.tests().contains("demo.StatsTests#countAboveIsStrict()"), ctx.tests().toString());
        assertTrue(ctx.tests().contains("demo.ReportTests#outliersCountsValuesAboveTheMean()"), "reached through the caller: " + ctx.tests());
        String prompt = GraphRag.render(g, ctx, TestOutputParser.parse(OUTPUT, false).failed());
        assertTrue(prompt.contains("expected <1> but was <2>"));
        assertTrue(prompt.contains("   6 | ") || prompt.contains("  21 | "), "method source carries real line numbers:\n" + prompt);
        assertTrue(prompt.contains("Called by"));
    }
}
