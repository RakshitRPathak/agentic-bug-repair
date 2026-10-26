package dev.rakshit.repair.trace;

import java.util.List;

/**
 * One failing test: its name ({@code Class.method}, as the test runner prints it), the exception, and the stack trace.
 * {@code frames} are innermost first, exactly as Java prints them.
 */
public record FailureReport(String testName, String exceptionType, String message, List<Frame> frames) {
    public FailureReport {
        frames = List.copyOf(frames);
    }

    public String testClass() {
        int dot = testName.lastIndexOf('.');
        return dot < 0 ? testName : testName.substring(0, dot);
    }

    public String testMethod() {
        int dot = testName.lastIndexOf('.');
        return dot < 0 ? testName : testName.substring(dot + 1);
    }

    /** A one-line description: {@code Class.method: java.lang.AssertionError: expected <3> but was <2>}. */
    public String summary() {
        return testName + ": " + exceptionType + (message.isEmpty() ? "" : ": " + message);
    }
}
