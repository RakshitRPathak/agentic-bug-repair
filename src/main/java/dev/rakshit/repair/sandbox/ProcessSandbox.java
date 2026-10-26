package dev.rakshit.repair.sandbox;

import dev.rakshit.repair.util.Fs;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sandbox that needs only a Linux box: a throwaway copy of the repository, an empty environment (no tokens or keys leak
 * in), kernel resource limits ({@code ulimit}: address space, CPU time, file size), a watchdog that kills the whole
 * process tree on timeout or when it grows past the process limit, and, where the kernel allows it, a private network
 * namespace ({@code unshare}) so the code cannot reach the network at all.
 *
 * <p>This is weaker than {@link DockerSandbox}: the code still sees the host's filesystem (read-only in effect only
 * because it is not root on the host) and there is no cgroup memory or PID limit. Use Docker for code you do not trust.
 */
public final class ProcessSandbox implements Sandbox {
    private final boolean networkIsolation;

    public ProcessSandbox() {
        this.networkIsolation = probeUnshare();
    }

    public boolean networkIsolated() {
        return networkIsolation;
    }

    private static boolean probeUnshare() {
        try {
            Process p = new ProcessBuilder("unshare", "-rn", "true").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public String describe() {
        return "process sandbox: throwaway copy, empty environment, rlimits (memory, CPU, file size), process-tree watchdog, "
                + (networkIsolation ? "network namespace (no network)" : "NO network isolation on this host");
    }

    @Override
    public SandboxResult run(Path repo, List<String> command, Limits limits) throws IOException {
        Path work = Files.createTempDirectory("repair-sbx-");
        try {
            Fs.copyTree(repo, work.resolve("repo"));
            return execute(work, command, limits);
        } finally {
            Fs.deleteTree(work);
        }
    }

    private SandboxResult execute(Path work, List<String> command, Limits limits) throws IOException {
        long cpuSeconds = Math.max(10, limits.timeoutMs() / 1000 * 2);
        // virtual address space: the JVM reserves far more than it uses, so allow a multiple of the heap limit
        long vmKb = (long) limits.memoryMb() * 1024 * 3;
        String script = "ulimit -v " + vmKb + "; ulimit -t " + cpuSeconds + "; ulimit -f 262144; ulimit -c 0; exec \"$@\"";
        List<String> argv = new ArrayList<>();
        if (networkIsolation) argv.addAll(List.of("unshare", "-rn"));
        argv.addAll(List.of("bash", "-c", script, "sandbox"));
        argv.addAll(command);

        ProcessBuilder pb = new ProcessBuilder(argv).directory(work.resolve("repo").toFile()).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        env.put("HOME", work.toString());
        env.put("TMPDIR", work.toString());
        env.put("LANG", "C.UTF-8");
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) env.put("JAVA_HOME", javaHome);
        env.put("JAVA_TOOL_OPTIONS", "-Xmx" + Math.max(64, limits.memoryMb() / 2) + "m -Xss1m -XX:+UseSerialGC -XX:TieredStopAtLevel=1");

        long start = System.nanoTime();
        Process proc = pb.start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        AtomicBoolean truncated = new AtomicBoolean();
        Thread reader = new Thread(() -> drain(proc.getInputStream(), captured, limits.maxOutputBytes(), truncated), "sandbox-reader");
        reader.setDaemon(true);
        reader.start();

        AtomicBoolean procLimit = new AtomicBoolean();
        Thread watchdog = new Thread(() -> {
            try {
                while (proc.isAlive()) {
                    if (proc.descendants().count() > limits.maxProcesses()) {
                        procLimit.set(true);
                        killTree(proc);
                        return;
                    }
                    Thread.sleep(25);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "sandbox-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        boolean finished;
        try {
            finished = proc.waitFor(limits.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(proc);
            throw new IOException("interrupted while waiting for the sandboxed command");
        }
        if (!finished) killTree(proc);
        try {
            proc.waitFor(5, TimeUnit.SECONDS);
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        watchdog.interrupt();
        long ms = (System.nanoTime() - start) / 1_000_000;
        int exit = proc.isAlive() ? -1 : proc.exitValue();
        String out;
        synchronized (captured) {
            out = captured.toString(StandardCharsets.UTF_8);
        }
        return new SandboxResult(exit, out, !finished, procLimit.get(), truncated.get(), ms);
    }

    private static void drain(InputStream in, ByteArrayOutputStream sink, int max, AtomicBoolean truncated) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                synchronized (sink) {
                    int room = max - sink.size();
                    if (room > 0) sink.write(buf, 0, Math.min(room, n));
                    if (n > room) truncated.set(true);
                }
            }
        } catch (IOException ignored) {
            // the process was killed; whatever was captured is returned
        }
    }

    private static void killTree(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }
}
