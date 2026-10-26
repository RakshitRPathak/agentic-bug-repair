package dev.rakshit.repair.localize;

import dev.rakshit.repair.graph.CodeGraph;
import dev.rakshit.repair.graph.CodeNode;
import dev.rakshit.repair.graph.NodeKind;
import dev.rakshit.repair.retrieval.HybridRetriever;
import dev.rakshit.repair.trace.FailureReport;
import dev.rakshit.repair.trace.Frame;
import dev.rakshit.repair.trace.TestRun;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ranks the methods of a repository by how likely each is to contain the bug behind a set of failing tests. Four
 * independent signals, each scaled to 0..1, are combined with a weighted sum:
 *
 * <ul>
 *   <li><b>spectrum</b> - Ochiai suspiciousness on <em>static coverage</em>: a method is covered by a test if the call
 *       graph reaches it from that test. Methods reached by many failing tests and few passing tests score high. This is
 *       the standard spectrum-based fault localisation formula; the call graph stands in for a coverage tool.</li>
 *   <li><b>frame</b> - the stack trace. The innermost project (non-test) frame is where the program went wrong; frames
 *       further out score less. Assertion failures have none, because the exception is thrown inside the test.</li>
 *   <li><b>proximity</b> - 1 / (call-chain length from the failing test). A bug is more often in code the test calls
 *       directly than seven levels down.</li>
 *   <li><b>text</b> - hybrid BM25 + vector search of the failing test's source and error message against method bodies.</li>
 * </ul>
 */
public final class FaultLocalizer {
    public static final int MAX_DEPTH = 8;

    /**
     * Signal weights. They need not sum to 1; scores are only compared with each other. The default was chosen by grid
     * search on the benchmark's dev split only (see {@code Bench} mode {@code tune}); text keeps a small weight as a tie-breaker.
     */
    public record Weights(double spectrum, double frame, double proximity, double text) {
        public static final Weights DEFAULT = new Weights(0.5, 0.5, 0.25, 0.05);
        public static final Weights TRACE_ONLY = new Weights(0, 1, 0, 0);
        public static final Weights TEXT_ONLY = new Weights(0, 0, 0, 1);
        public static final Weights SPECTRUM_ONLY = new Weights(1, 0, 0, 0);
        public static final Weights NO_GRAPH = new Weights(0, 0.5, 0, 0.5);
    }

    private FaultLocalizer() {}

    public static List<Suspect> localize(CodeGraph g, TestRun run, HybridRetriever retriever, Weights w) {
        List<FailureReport> failures = run.failed();
        Map<String, Integer> failingReach = new HashMap<>();      // method -> number of failing tests reaching it
        Map<String, Integer> passingReach = new HashMap<>();
        Map<String, Integer> nearest = new HashMap<>();            // method -> fewest calls from any failing test
        for (FailureReport f : failures) {
            CodeNode t = testNode(g, f.testName());
            if (t == null) continue;
            for (Map.Entry<String, Integer> e : g.reachableByCalls(t.id(), MAX_DEPTH).entrySet()) {
                if (!isCandidate(g, e.getKey())) continue;
                failingReach.merge(e.getKey(), 1, Integer::sum);
                nearest.merge(e.getKey(), e.getValue(), Math::min);
            }
        }
        for (String name : run.passed()) {
            CodeNode t = testNode(g, name);
            if (t == null) continue;
            for (String m : g.reachableByCalls(t.id(), MAX_DEPTH).keySet()) {
                if (isCandidate(g, m)) passingReach.merge(m, 1, Integer::sum);
            }
        }

        Map<String, Double> frameScore = new HashMap<>();
        for (FailureReport f : failures) {
            int depth = 0;
            for (Frame fr : f.frames()) {
                CodeNode n = resolve(g, fr);
                if (n == null || !isCandidate(g, n.id())) continue;
                frameScore.merge(n.id(), Math.pow(0.8, depth), Math::max);
                depth++;
            }
        }

        Map<String, Double> text = Map.of();
        if (w.text() > 0 && retriever != null && !failures.isEmpty()) {
            StringBuilder q = new StringBuilder();
            for (FailureReport f : failures) {
                q.append(f.summary()).append('\n');
                CodeNode t = testNode(g, f.testName());
                if (t != null) q.append(t.source()).append('\n');
            }
            text = retriever.normalizedScores(q.toString());
        }

        Set<String> candidates = new HashSet<>();
        candidates.addAll(failingReach.keySet());
        candidates.addAll(frameScore.keySet());
        for (String id : text.keySet()) if (isCandidate(g, id)) candidates.add(id);

        int totalFailing = failures.size();
        List<Suspect> out = new ArrayList<>();
        for (String id : candidates) {
            int ef = failingReach.getOrDefault(id, 0), ep = passingReach.getOrDefault(id, 0);
            double spectrum = totalFailing == 0 || ef == 0 ? 0 : ef / Math.sqrt((double) totalFailing * (ef + ep));
            double frame = frameScore.getOrDefault(id, 0.0);
            double prox = nearest.containsKey(id) ? 1.0 / Math.max(1, nearest.get(id)) : 0;
            double txt = text.getOrDefault(id, 0.0);
            double score = w.spectrum() * spectrum + w.frame() * frame + w.proximity() * prox + w.text() * txt;
            if (score <= 0) continue;
            Map<String, Double> signals = new LinkedHashMap<>();
            signals.put("spectrum", spectrum);
            signals.put("frame", frame);
            signals.put("proximity", prox);
            signals.put("text", txt);
            out.add(new Suspect(id, score, 0, signals));
        }
        out.sort((a, b) -> {
            int c = Double.compare(b.score(), a.score());
            return c != 0 ? c : a.id().compareTo(b.id());
        });
        List<Suspect> ranked = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) ranked.add(new Suspect(out.get(i).id(), out.get(i).score(), i + 1, out.get(i).signals()));
        return ranked;
    }

    private static boolean isCandidate(CodeGraph g, String id) {
        CodeNode n = g.node(id);
        return n != null && n.kind() == NodeKind.METHOD && !n.isTest();
    }

    /** The graph node for a test printed as {@code Class.method}. */
    public static CodeNode testNode(CodeGraph g, String testName) {
        int dot = testName.lastIndexOf('.');
        if (dot < 0) return null;
        List<CodeNode> found = g.findMethods(testName.substring(0, dot), testName.substring(dot + 1));
        for (CodeNode n : found) if (n.isTest()) return n;
        return found.isEmpty() ? null : found.get(0);
    }

    /** Maps a stack frame to the method it executes, by source file and line. */
    public static CodeNode resolve(CodeGraph g, Frame f) {
        String pkgPath = "";
        String cls = f.className();
        int lastDot = cls.lastIndexOf('.');
        if (lastDot >= 0) pkgPath = cls.substring(0, lastDot).replace('.', '/') + "/";
        String suffix = pkgPath + f.fileName();
        for (CodeNode n : g.nodes()) {
            if (n.kind() == NodeKind.CLASS && (n.file().equals(suffix) || n.file().endsWith("/" + suffix))) {
                return g.methodAt(n.file(), f.line());
            }
        }
        return null;
    }
}
