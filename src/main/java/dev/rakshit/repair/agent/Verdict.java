package dev.rakshit.repair.agent;

import dev.rakshit.repair.trace.TestRun;
import java.util.Set;

/**
 * Result of verifying one candidate against the baseline.
 *
 * @param applyError why the diff did not apply, or null
 * @param run the test run (null if the diff did not apply or the sandbox failed)
 * @param fixed tests that failed before and pass now
 * @param broken tests that passed before and do not pass now (regressions)
 * @param lintDelta compiler warnings now minus warnings before
 */
public record Verdict(String applyError, TestRun run, Set<String> fixed, Set<String> broken, int lintDelta, long durationMs) {
    public Verdict {
        fixed = Set.copyOf(fixed);
        broken = Set.copyOf(broken);
    }

    public boolean applied() {
        return applyError == null;
    }

    public boolean compiled() {
        return run != null && run.compiled();
    }

    /** All tests green, nothing regressed, no new warnings. */
    public boolean passes() {
        return applied() && run != null && run.allGreen() && broken.isEmpty() && lintDelta <= 0;
    }

    /** Not a fix, but moves in the right direction without breaking anything: worth building on. */
    public boolean improves() {
        return applied() && compiled() && !run.timedOut() && !fixed.isEmpty() && broken.isEmpty() && lintDelta <= 0;
    }
}
