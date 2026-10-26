package dev.rakshit.repair.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the command in a locked-down, ephemeral container:
 * no network ({@code --network none}), memory and swap cgroup limit, CPU quota, PID limit (stops fork bombs), read-only
 * root filesystem, all capabilities dropped, {@code no-new-privileges}, an unprivileged user, and the repository mounted
 * read-only and copied into a size-capped tmpfs. The container is removed when it exits ({@code --rm}) and killed by name
 * on timeout.
 */
public final class DockerSandbox implements Sandbox {
    private final String image;
    private final int cpus;

    public DockerSandbox(String image, int cpus) {
        this.image = image;
        this.cpus = cpus;
    }

    public static DockerSandbox temurin17() {
        return new DockerSandbox("eclipse-temurin:17-jdk", 1);
    }

    /** True if a Docker daemon answers. */
    public static boolean available() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public String describe() {
        return "docker sandbox (" + image + "): --network none, memory/CPU/PID cgroup limits, read-only root, all capabilities dropped, no-new-privileges, non-root user";
    }

    /** The exact {@code docker run} command line. Pure, so it can be unit-tested without a Docker daemon. */
    public List<String> buildArgv(String containerName, Path repo, List<String> command, Limits limits) {
        List<String> a = new ArrayList<>(List.of(
                "docker", "run", "--rm", "--name", containerName,
                "--network", "none",
                "--memory", limits.memoryMb() + "m", "--memory-swap", limits.memoryMb() + "m",
                "--cpus", String.valueOf(cpus),
                "--pids-limit", String.valueOf(limits.maxProcesses()),
                "--ulimit", "nofile=1024:1024",
                "--read-only",
                "--tmpfs", "/tmp:rw,exec,nosuid,size=256m",
                "--tmpfs", "/work:rw,exec,nosuid,size=512m,uid=65534,gid=65534",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--user", "65534:65534",
                "-e", "HOME=/tmp",
                "-e", "JAVA_TOOL_OPTIONS=-Xmx" + Math.max(64, limits.memoryMb() / 2) + "m",
                "-v", repo.toAbsolutePath() + ":/src:ro",
                "-w", "/work",
                image,
                "sh", "-c", "cp -a /src/. /work/ && cd /work && exec \"$@\"", "sandbox"));
        a.addAll(command);
        return a;
    }

    @Override
    public SandboxResult run(Path repo, List<String> command, Limits limits) throws IOException {
        String name = "repair-" + UUID.randomUUID().toString().substring(0, 8);
        Process proc = new ProcessBuilder(buildArgv(name, repo, command, limits)).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicBoolean truncated = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = proc.getInputStream().read(buf)) > 0) {
                    synchronized (out) {
                        int room = limits.maxOutputBytes() - out.size();
                        if (room > 0) out.write(buf, 0, Math.min(room, n));
                        if (n > room) truncated.set(true);
                    }
                }
            } catch (IOException ignored) {
                // container killed
            }
        }, "docker-reader");
        reader.setDaemon(true);
        reader.start();
        long start = System.nanoTime();
        boolean finished;
        try {
            finished = proc.waitFor(limits.timeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                new ProcessBuilder("docker", "kill", name).redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS);
                proc.destroyForcibly();
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("interrupted while waiting for docker");
        }
        String text;
        synchronized (out) {
            text = out.toString(StandardCharsets.UTF_8);
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        return new SandboxResult(finished ? proc.exitValue() : -1, text, !finished, false, truncated.get(), ms);
    }
}
