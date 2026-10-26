package dev.rakshit.repair.trace;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the output of a test command. Understands the line format of the test runner used across these repositories
 * ({@code PASS  Class.method}, {@code FAIL  Class.method  ->  Exception: message}, followed by indented {@code at}
 * lines), plus javac diagnostics and a stack trace printed by a crashed runner.
 */
public final class TestOutputParser {
    private static final Pattern PASS = Pattern.compile("^\\s*PASS\\s+(\\S+)\\s*$");
    private static final Pattern START = Pattern.compile("^\\s*START\\s+(\\S+)\\s*$");
    private static final Pattern FAIL = Pattern.compile("^\\s*FAIL\\s+(\\S+)\\s+->\\s+(.*)$");
    private static final Pattern AT = Pattern.compile("^\\s*at\\s+([\\w$.<>]+)\\.([\\w$<>]+)\\((\\w+\\.java):(\\d+)\\)\\s*$");
    private static final Pattern COMPILE_ERROR = Pattern.compile("^.*\\.java:\\d+: error:.*$");
    private static final Pattern WARNING = Pattern.compile("^.*\\.java:\\d+: warning:.*$");

    private TestOutputParser() {}

    /** Parses a single stack-trace line, or returns null. */
    public static Frame frame(String line) {
        Matcher m = AT.matcher(line);
        if (!m.matches()) return null;
        return new Frame(m.group(1), m.group(2), m.group(3), Integer.parseInt(m.group(4)));
    }

    public static TestRun parse(String output, boolean timedOut) {
        Set<String> passed = new HashSet<>();
        List<FailureReport> failed = new ArrayList<>();
        List<String> compileErrors = new ArrayList<>();
        int warnings = 0;
        boolean sawResults = false;
        String started = null;

        String curTest = null, curType = null, curMsg = null;
        List<Frame> curFrames = new ArrayList<>();
        boolean crashed = false;
        List<Frame> crashFrames = new ArrayList<>();
        String crashHead = null;

        String[] lines = output.split("\\R", -1);
        for (String line : lines) {
            Matcher st = START.matcher(line);
            if (st.matches()) {
                started = st.group(1);
                continue;
            }
            Matcher p = PASS.matcher(line);
            Matcher f = FAIL.matcher(line);
            if (p.matches() || f.matches()) {
                if (curTest != null) failed.add(new FailureReport(curTest, curType, curMsg, curFrames));
                curTest = null;
                curFrames = new ArrayList<>();
                sawResults = true;
                started = null;
                if (p.matches()) {
                    passed.add(p.group(1));
                } else {
                    curTest = f.group(1);
                    String rest = f.group(2).trim();
                    int colon = rest.indexOf(": ");
                    curType = colon < 0 ? rest : rest.substring(0, colon);
                    curMsg = colon < 0 ? "" : rest.substring(colon + 2);
                }
                continue;
            }
            Frame fr = frame(line);
            if (fr != null) {
                if (curTest != null) curFrames.add(fr);
                else if (crashHead != null) crashFrames.add(fr);
                continue;
            }
            if (COMPILE_ERROR.matcher(line).matches()) {
                if (compileErrors.size() < 40) compileErrors.add(line.trim());
            } else if (WARNING.matcher(line).matches()) {
                warnings++;
            } else if (line.startsWith("Exception in thread") || (line.startsWith("Caused by:") && crashHead == null)) {
                crashed = true;
                crashHead = line;
            } else if (line.startsWith("passed:")) {
                if (curTest != null) {
                    failed.add(new FailureReport(curTest, curType, curMsg, curFrames));
                    curTest = null;
                }
            }
        }
        if (curTest != null) failed.add(new FailureReport(curTest, curType, curMsg, curFrames));
        if (started != null && timedOut) {
            // the sandbox killed the run while this test was still going: an infinite loop or a deadlock
            failed.add(new FailureReport(started, "Timeout", "the test did not finish within the time limit (infinite loop?)", List.of()));
        }
        if (crashed && !sawResults) {
            // the runner itself died (e.g. a StackOverflowError or a failing static initialiser) before printing results
            String type = crashHead.replaceFirst("^Exception in thread \"[^\"]*\"\\s*", "");
            int colon = type.indexOf(": ");
            failed.add(new FailureReport("<runner>", colon < 0 ? type : type.substring(0, colon),
                    colon < 0 ? "" : type.substring(colon + 2), crashFrames));
        }
        String tail = output.length() > 4000 ? output.substring(output.length() - 4000) : output;
        return new TestRun(compileErrors.isEmpty(), compileErrors, warnings, passed, failed, timedOut, crashed && !sawResults, tail);
    }
}
