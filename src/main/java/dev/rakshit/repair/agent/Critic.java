package dev.rakshit.repair.agent;

import dev.rakshit.repair.trace.FailureReport;
import java.util.ArrayList;
import java.util.List;

/** Turns verification results into the text a synthesizer reads before its next attempt. */
public final class Critic {
    private Critic() {}

    public static String describe(String candidateDescription, Verdict v) {
        StringBuilder sb = new StringBuilder("Candidate: ").append(candidateDescription).append('\n');
        if (!v.applied()) return sb.append("The diff did not apply: ").append(v.applyError()).append('\n').toString();
        if (v.run() == null) return sb.append("The sandbox produced no result.\n").toString();
        if (!v.run().compiled()) {
            sb.append("It does not compile:\n");
            for (String e : v.run().compileErrors().subList(0, Math.min(8, v.run().compileErrors().size()))) sb.append("  ").append(e).append('\n');
            return sb.toString();
        }
        if (v.run().timedOut()) sb.append("The test run timed out or exceeded its process limit (an infinite loop or runaway recursion?).\n");
        if (v.run().crashed()) sb.append("The test runner crashed before reporting results.\n");
        if (!v.fixed().isEmpty()) sb.append("Now passing: ").append(String.join(", ", sorted(v.fixed()))).append('\n');
        if (!v.broken().isEmpty()) sb.append("REGRESSION - these passed before and fail now: ").append(String.join(", ", sorted(v.broken()))).append('\n');
        List<FailureReport> still = v.run().failed();
        if (!still.isEmpty()) {
            sb.append("Still failing:\n");
            for (FailureReport f : still.subList(0, Math.min(6, still.size()))) sb.append("  ").append(f.summary()).append('\n');
        }
        if (v.lintDelta() > 0) sb.append("It adds ").append(v.lintDelta()).append(" new compiler warning(s).\n");
        return sb.toString();
    }

    private static List<String> sorted(java.util.Set<String> s) {
        List<String> l = new ArrayList<>(s);
        java.util.Collections.sort(l);
        return l;
    }
}
