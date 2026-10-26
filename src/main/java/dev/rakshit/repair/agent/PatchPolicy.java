package dev.rakshit.repair.agent;

import dev.rakshit.repair.graph.CodeGraph;
import dev.rakshit.repair.graph.CodeNode;
import dev.rakshit.repair.graph.NodeKind;
import dev.rakshit.repair.patch.UnifiedDiff;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rules every patch must satisfy before it is allowed near the sandbox. The point is to stop an agent from "fixing" a
 * failing test by editing the test, weakening the build script or CI, or rewriting half the project: only existing
 * non-test source files may change, and only by a bounded number of lines.
 */
public final class PatchPolicy {
    private PatchPolicy() {}

    public static final class Violation extends Exception {
        private static final long serialVersionUID = 1L;

        public Violation(String message) {
            super(message);
        }
    }

    public static void check(String diff, CodeGraph graph, int maxChangedLines) throws Violation {
        Set<String> sourceFiles = new HashSet<>(), testFiles = new HashSet<>();
        for (CodeNode n : graph.nodes()) {
            if (n.kind() != NodeKind.CLASS) continue;
            (n.isTest() ? testFiles : sourceFiles).add(n.file());
        }
        List<UnifiedDiff.FileDiff> files;
        try {
            files = UnifiedDiff.parse(diff);
        } catch (UnifiedDiff.PatchException e) {
            throw new Violation("malformed diff: " + e.getMessage());
        }
        for (UnifiedDiff.FileDiff fd : files) {
            String p = fd.path();
            if (testFiles.contains(p) || p.contains("/test/") || p.endsWith("Test.java") || p.endsWith("Tests.java")) {
                throw new Violation("patches may not modify tests: " + p);
            }
            if (!sourceFiles.contains(p)) throw new Violation("patches may only modify existing non-test source files, not " + p);
        }
        int[] s = UnifiedDiff.stats(diff);
        if (s[0] + s[1] > maxChangedLines) {
            throw new Violation("patch changes " + (s[0] + s[1]) + " lines; the limit is " + maxChangedLines + ". Make a smaller change.");
        }
    }
}
