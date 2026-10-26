package dev.rakshit.repair.bench;

import static dev.rakshit.repair.testkit.Assert.assertEquals;
import static dev.rakshit.repair.testkit.Assert.assertFalse;
import static dev.rakshit.repair.testkit.Assert.assertTrue;

import dev.rakshit.repair.agent.PatchPolicy;
import dev.rakshit.repair.graph.CodeGraph;
import dev.rakshit.repair.graph.JavaIndexer;
import dev.rakshit.repair.sandbox.Limits;
import dev.rakshit.repair.sandbox.ProcessSandbox;
import dev.rakshit.repair.testkit.Test;
import dev.rakshit.repair.util.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BenchTests {
    private static final Path BENCH = Path.of("bench");

    @Test
    public void everyBenchmarkCaseIsWellFormedAndItsBugAppliesAndTargetsARealMethod() throws Exception {
        List<Bench.Case> cases = Bench.load(BENCH);
        assertTrue(cases.size() >= 15, "cases: " + cases.size());
        Set<String> ids = new HashSet<>();
        int devCount = 0, testCount = 0;
        for (Bench.Case c : cases) {
            assertTrue(ids.add(c.id()), "duplicate id " + c.id());
            assertTrue(c.split().equals("dev") || c.split().equals("test"), c.id() + " split " + c.split());
            assertTrue(c.kind().equals("operator") || c.kind().equals("other"), c.id() + " kind " + c.kind());
            assertFalse(c.truth().isEmpty(), c.id() + " has no ground truth");
            if (c.split().equals("dev")) devCount++;
            else testCount++;

            Path repo = Bench.materialize(c);
            CodeGraph g = JavaIndexer.index(repo);
            assertTrue(g.compileErrors().isEmpty(), c.id() + " does not compile: " + g.compileErrors());
            for (String t : c.truth()) assertTrue(g.node(t) != null, c.id() + ": ground-truth method " + t + " is not in the graph");

            // the bug is a change to non-test source only, within the policy a repair would be held to
            String patch = Files.readString(c.dir().resolve("bug.patch"));
            PatchPolicy.check(patch, g, 400);
            // and it really changes something relative to the pristine target
            assertFalse(Bench.sameSources(repo, c.targetDir()), c.id() + ": bug.patch changes nothing");
            dev.rakshit.repair.util.Fs.deleteTree(repo);
        }
        assertTrue(devCount >= 6 && testCount >= 6, "balanced splits: dev " + devCount + " test " + testCount);
    }

    @Test
    public void everyTargetIsGreenBeforeAnyBugIsPlanted() throws Exception {
        Set<String> seen = new HashSet<>();
        for (Bench.Case c : Bench.load(BENCH)) {
            if (!seen.add(c.target())) continue;
            var res = new ProcessSandbox().run(c.targetDir(), List.of("bash", "build.sh"), new Limits(120_000, 2048, 128, 1 << 20));
            assertEquals(0, res.exitCode(), c.target() + ":\n" + res.output());
            assertTrue(res.output().contains("failed: 0"), c.target());
        }
        assertTrue(seen.size() >= 2);
    }

    @Test
    public void localizationOfOneCaseRanksTheTrueMethodUnderEveryVariant() throws Exception {
        Bench.Case c = Bench.load(BENCH).stream().filter(x -> x.id().startsWith("01-")).findFirst().orElseThrow();
        Bench.LocResult r = Bench.localize(c, new ProcessSandbox(), new Limits(120_000, 2048, 128, 1 << 20), List.of("bash", "build.sh"));
        assertEquals(5, r.rankByVariant().size());
        int full = r.rankByVariant().get("full (graph + trace + text)");
        assertTrue(full >= 1 && full <= 5, "ranks: " + r.rankByVariant());
        assertTrue(r.rankByVariant().get("trace only (baseline)") != 1 || full == 1, "the baseline gets no free lunch: " + r.rankByVariant());
    }

    @Test
    public void scoringAndReportingAreArithmeticallyRight() {
        String v = "full (graph + trace + text)";
        Map<String, Integer> a = new java.util.LinkedHashMap<>(), b = new java.util.LinkedHashMap<>(), c = new java.util.LinkedHashMap<>(), d = new java.util.LinkedHashMap<>();
        for (String k : List.of("trace only (baseline)", "text search only", "trace + text (no graph)", "call-graph spectrum only", v)) {
            a.put(k, 1);
            b.put(k, 2);
            c.put(k, 4);
            d.put(k, 0);                                        // not among the suspects at all
        }
        List<Bench.LocResult> loc = List.of(new Bench.LocResult("a", "operator", "dev", a), new Bench.LocResult("b", "operator", "dev", b),
                new Bench.LocResult("c", "other", "test", c), new Bench.LocResult("d", "other", "test", d));
        Map<String, Double> m = Bench.locMetrics(loc, v);
        assertEquals(0.25, m.get("top1"));
        assertEquals(0.5, m.get("top3"));
        assertEquals(0.75, m.get("top5"));
        assertEquals((1.0 + 0.5 + 0.25 + 0.0) / 4, m.get("mrr"));

        List<Bench.RepairResult> rep = List.of(
                new Bench.RepairResult("a", "operator", "dev", true, true, 1, 3, 5, 10_000, "ok", "replace > with >="),
                new Bench.RepairResult("b", "other", "test", false, false, 4, 32, 40, 100_000, "no fix", ""));
        String md = Bench.report(loc, rep);
        assertTrue(md.contains("### Fault localization (4 bugs)") && md.contains("| full (graph + trace + text) | 25% | 50% | 75% | 0.44 |"), md);
        assertTrue(md.contains("**1 / 2 (50%)**") && md.contains("yes (original restored)") && md.contains("Bugs fixable by one small edit: 1 / 1"), md);
        Map<String, Object> j = Json.parseObject(Json.write(Bench.toJson(loc, rep)));
        assertEquals(4, ((List<?>) j.get("localization")).size());
        assertEquals(2, ((List<?>) j.get("repair")).size());
    }

    @Test
    public void restoredOriginalMeansByteIdenticalSources() throws Exception {
        Path a = Files.createTempDirectory("same-a-"), b = Files.createTempDirectory("same-b-");
        Files.writeString(a.resolve("X.java"), "class X {}\n");
        Files.writeString(b.resolve("X.java"), "class X {}\n");
        assertTrue(Bench.sameSources(a, b));
        Files.writeString(b.resolve("X.java"), "class X { }\n");
        assertFalse(Bench.sameSources(a, b));
        Files.delete(b.resolve("X.java"));
        assertFalse(Bench.sameSources(a, b));
    }
}
