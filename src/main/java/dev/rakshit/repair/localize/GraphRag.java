package dev.rakshit.repair.localize;

import dev.rakshit.repair.graph.CodeGraph;
import dev.rakshit.repair.graph.CodeNode;
import dev.rakshit.repair.graph.EdgeKind;
import dev.rakshit.repair.trace.FailureReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The "graph" half of GraphRAG: starting from a suspect, a bounded traversal of the code graph collects what the model
 * needs to reason about a change - who calls it (what depends on its behaviour), what it calls, which fields it
 * writes, and which tests exercise it - instead of dumping whole files into the prompt.
 */
public final class GraphRag {
    private static final int SNIPPET_LINES = 25;

    public record Context(CodeNode suspect, Map<String, Integer> neighbourhood, List<CodeNode> callers,
                          List<CodeNode> callees, List<String> modifiedFields, List<String> tests) {}

    private GraphRag() {}

    public static Context context(CodeGraph g, String suspectId, int hops) {
        CodeNode s = g.node(suspectId);
        Map<String, Integer> hood = g.traverse(suspectId, hops, Set.of(EdgeKind.CALLS, EdgeKind.MODIFIES), CodeGraph.Direction.BOTH);
        List<CodeNode> callers = new ArrayList<>(), callees = new ArrayList<>();
        List<String> fields = new ArrayList<>(), tests = new ArrayList<>();
        for (String c : g.callers(suspectId)) {
            CodeNode n = g.node(c);
            if (n.isTest()) tests.add(c);
            else callers.add(n);
        }
        for (String c : g.callees(suspectId)) callees.add(g.node(c));
        fields.addAll(g.neighbours(suspectId, EdgeKind.MODIFIES, CodeGraph.Direction.OUT));
        // tests that reach the suspect indirectly are also relevant
        for (String t : g.blastRadius(suspectId, hops + 3).tests()) if (!tests.contains(t)) tests.add(t);
        return new Context(s, hood, callers, callees, fields, tests);
    }

    /** Renders the context as the text of an LLM prompt. */
    public static String render(CodeGraph g, Context ctx, List<FailureReport> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Failing tests\n");
        for (FailureReport f : failures) sb.append("- ").append(f.summary()).append('\n');
        sb.append("\n## Suspect method (").append(ctx.suspect().file()).append(", lines ")
                .append(ctx.suspect().startLine()).append('-').append(ctx.suspect().endLine()).append(")\n");
        sb.append(numbered(ctx.suspect()));
        if (!ctx.callers().isEmpty()) {
            sb.append("\n## Called by\n");
            for (CodeNode n : ctx.callers()) sb.append("// ").append(n.label()).append("\n").append(truncate(n.source())).append('\n');
        }
        if (!ctx.callees().isEmpty()) {
            sb.append("\n## Calls\n");
            Map<String, CodeNode> seen = new LinkedHashMap<>();
            for (CodeNode n : ctx.callees()) seen.putIfAbsent(n.id(), n);
            for (CodeNode n : seen.values()) sb.append("// ").append(n.label()).append("\n").append(truncate(n.source())).append('\n');
        }
        if (!ctx.modifiedFields().isEmpty()) sb.append("\n## Fields it writes\n").append(String.join(", ", ctx.modifiedFields())).append('\n');
        return sb.toString();
    }

    /** The method's source with real file line numbers, so a model can write a diff with correct hunk headers. */
    public static String numbered(CodeNode n) {
        StringBuilder sb = new StringBuilder();
        String[] lines = n.source().split("\n", -1);
        for (int i = 0; i < lines.length; i++) sb.append(String.format("%4d | %s%n", n.startLine() + i, lines[i]));
        return sb.toString();
    }

    private static String truncate(String src) {
        String[] lines = src.split("\n");
        if (lines.length <= SNIPPET_LINES) return src;
        return String.join("\n", java.util.Arrays.copyOf(lines, SNIPPET_LINES)) + "\n    // ... " + (lines.length - SNIPPET_LINES) + " more lines";
    }
}
