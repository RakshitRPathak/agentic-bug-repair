package dev.rakshit.repair.sandbox;

/** Outcome of a sandboxed command. {@code output} is stdout and stderr interleaved. */
public record SandboxResult(int exitCode, String output, boolean timedOut, boolean killedForProcessLimit,
                            boolean outputTruncated, long durationMs) {
    public boolean ok() {
        return exitCode == 0 && !timedOut && !killedForProcessLimit;
    }
}
