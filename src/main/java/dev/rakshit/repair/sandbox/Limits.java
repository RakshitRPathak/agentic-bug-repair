package dev.rakshit.repair.sandbox;

/**
 * Resource limits for one sandboxed run.
 *
 * @param timeoutMs wall-clock limit; the whole process tree is killed when it passes
 * @param memoryMb memory ceiling (a cgroup limit under Docker, an address-space limit otherwise)
 * @param maxProcesses most processes/threads-as-processes the run may have alive at once
 * @param maxOutputBytes output kept; the rest is read and thrown away so the child never blocks on a full pipe
 */
public record Limits(long timeoutMs, int memoryMb, int maxProcesses, int maxOutputBytes) {
    public Limits {
        if (timeoutMs <= 0 || memoryMb < 64 || maxProcesses < 1 || maxOutputBytes < 1024) {
            throw new IllegalArgumentException("unreasonable limits: " + timeoutMs + "ms " + memoryMb + "MB "
                    + maxProcesses + " procs " + maxOutputBytes + " bytes");
        }
    }

    public static Limits defaults() {
        return new Limits(120_000, 2048, 128, 1 << 20);
    }

    public Limits withTimeout(long ms) {
        return new Limits(ms, memoryMb, maxProcesses, maxOutputBytes);
    }
}
