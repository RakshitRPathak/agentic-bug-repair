package dev.rakshit.repair.trace;

import java.util.List;
import java.util.Set;

/**
 * What one run of the repository's test command showed.
 *
 * @param compiled false if the compiler reported errors (then no test result is meaningful)
 * @param compileErrors the error lines, at most 40
 * @param warnings how many compiler warnings (lint) were printed
 */
public record TestRun(boolean compiled, List<String> compileErrors, int warnings, Set<String> passed,
                      List<FailureReport> failed, boolean timedOut, boolean crashed, String tail) {
    public TestRun {
        compileErrors = List.copyOf(compileErrors);
        passed = Set.copyOf(passed);
        failed = List.copyOf(failed);
    }

    public boolean allGreen() {
        return compiled && !timedOut && !crashed && failed.isEmpty() && !passed.isEmpty();
    }

    public Set<String> failedNames() {
        return failed.stream().map(FailureReport::testName).collect(java.util.stream.Collectors.toSet());
    }
}
