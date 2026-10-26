package dev.rakshit.repair.sandbox;

import static dev.rakshit.repair.testkit.Assert.assertEquals;
import static dev.rakshit.repair.testkit.Assert.assertFalse;
import static dev.rakshit.repair.testkit.Assert.assertThrows;
import static dev.rakshit.repair.testkit.Assert.assertTrue;

import dev.rakshit.repair.testkit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SandboxTests {
    private static Path repo(String script) throws Exception {
        Path dir = Files.createTempDirectory("sbx-test-");
        Files.writeString(dir.resolve("run.sh"), script);
        Files.writeString(dir.resolve("data.txt"), "original\n");
        return dir;
    }

    private static final Limits FAST = new Limits(10_000, 256, 64, 64 * 1024);

    @Test
    public void runsACommandAndCapturesStdoutAndStderrAndExitCode() throws Exception {
        Path r = repo("echo out; echo err 1>&2; exit 3\n");
        SandboxResult res = new ProcessSandbox().run(r, List.of("bash", "run.sh"), FAST);
        assertEquals(3, res.exitCode());
        assertTrue(res.output().contains("out") && res.output().contains("err"), res.output());
        assertFalse(res.ok());
        assertFalse(res.timedOut());
    }

    @Test
    public void whatTheCommandWritesNeverReachesTheCallersDirectory() throws Exception {
        Path r = repo("echo hacked > data.txt; echo new > created.txt; rm run.sh\n");
        new ProcessSandbox().run(r, List.of("bash", "run.sh"), FAST);
        assertEquals("original\n", Files.readString(r.resolve("data.txt")));
        assertFalse(Files.exists(r.resolve("created.txt")));
        assertTrue(Files.exists(r.resolve("run.sh")));
    }

    @Test
    public void theEnvironmentIsEmptyOfSecrets() throws Exception {
        Path r = repo("echo \"token=[${GITHUB_TOKEN-unset}] key=[${ANTHROPIC_API_KEY-unset}] home=$HOME\"\n");
        SandboxResult res = new ProcessSandbox().run(r, List.of("bash", "run.sh"), FAST);
        assertTrue(res.output().contains("token=[unset] key=[unset]"), res.output());
    }

    @Test
    public void aRunawayCommandIsKilledAtTheTimeout() throws Exception {
        Path r = repo("while true; do :; done\n");
        long t0 = System.nanoTime();
        SandboxResult res = new ProcessSandbox().run(r, List.of("bash", "run.sh"), FAST.withTimeout(700));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(res.timedOut());
        assertFalse(res.ok());
        assertTrue(ms < 6000, "killed promptly, took " + ms + " ms");
    }

    @Test
    public void childrenOfTheCommandDieWithIt() throws Exception {
        Path r = repo("sleep 300 &\nsleep 300 &\nwait\n");
        SandboxResult res = new ProcessSandbox().run(r, List.of("bash", "run.sh"), FAST.withTimeout(600));
        assertTrue(res.timedOut());
        Thread.sleep(300);
        long left = ProcessHandle.current().descendants().filter(h -> h.info().commandLine().orElse("").contains("sleep 300")).count();
        assertEquals(0L, left);
    }

    @Test
    public void aForkBombTripsTheProcessLimit() throws Exception {
        Path r = repo("for i in $(seq 1 200); do sleep 30 & done; wait\n");
        SandboxResult res = new ProcessSandbox().run(r, List.of("bash", "run.sh"), new Limits(15_000, 256, 20, 64 * 1024));
        assertTrue(res.killedForProcessLimit(), "output: " + res.output());
        assertFalse(res.ok());
    }

    @Test
    public void outputIsCappedAndTheChildIsNotBlockedByAFullPipe() throws Exception {
        Path r = repo("yes 0123456789abcdef | head -c 3000000; echo done\n");
        SandboxResult res = new ProcessSandbox().run(r, List.of("bash", "run.sh"), FAST);
        assertTrue(res.outputTruncated());
        assertTrue(res.output().length() <= 64 * 1024 + 16, "kept " + res.output().length());
        assertEquals(0, res.exitCode());
        assertFalse(res.timedOut());
    }

    @Test
    public void memoryHogsAreStopped() throws Exception {
        Path dir = Files.createTempDirectory("sbx-mem-");
        Files.writeString(dir.resolve("Hog.java"), """
                import java.util.*;
                public class Hog { public static void main(String[] a) { List<long[]> l = new ArrayList<>(); while (true) l.add(new long[1_000_000]); } }
                """);
        SandboxResult res = new ProcessSandbox().run(dir, List.of("bash", "-c", "javac Hog.java && java Hog"), new Limits(30_000, 128, 64, 64 * 1024));
        assertFalse(res.ok());
        assertFalse(res.timedOut(), "must die from the memory limit, not the timeout: " + res.output());
    }

    @Test
    public void thereIsNoNetworkWhereTheKernelAllowsAnIsolatedNamespace() throws Exception {
        ProcessSandbox sbx = new ProcessSandbox();
        if (!sbx.networkIsolated()) return; // describe() says so honestly; nothing to assert on such a host
        Path r = repo("""
                (exec 3<>/dev/tcp/127.0.0.1/9 ) 2>/dev/null && echo CONNECTED || echo REFUSED
                cat /proc/net/dev | tail -n +3 | awk -F: '{print $1}' | tr -d ' ' | sort | tr '\\n' ' '
                """);
        SandboxResult res = sbx.run(r, List.of("bash", "run.sh"), FAST);
        assertTrue(res.output().contains("REFUSED"), res.output());
        assertTrue(res.output().trim().endsWith("lo"), "only a loopback device exists: " + res.output());
        assertTrue(sbx.describe().contains("no network"));
    }

    @Test
    public void describeAdmitsWhenThereIsNoNetworkIsolation() {
        String d = new ProcessSandbox().describe();
        assertTrue(d.contains("no network") || d.contains("NO network isolation"), d);
    }

    @Test
    public void rejectsUnreasonableLimits() {
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 256, 8, 4096));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1000, 8, 8, 4096));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1000, 256, 0, 4096));
    }

    @Test
    public void dockerCommandLineAppliesEveryIsolationFlag() {
        List<String> a = new DockerSandbox("eclipse-temurin:17-jdk", 2)
                .buildArgv("repair-x", Path.of("/tmp/some repo"), List.of("bash", "build.sh"), new Limits(60_000, 1024, 100, 1 << 20));
        String joined = String.join(" ", a);
        assertEquals("docker", a.get(0));
        assertTrue(joined.contains("--network none"), joined);
        assertTrue(joined.contains("--memory 1024m --memory-swap 1024m"), joined);
        assertTrue(joined.contains("--cpus 2"), joined);
        assertTrue(joined.contains("--pids-limit 100"), joined);
        assertTrue(joined.contains("--read-only"), joined);
        assertTrue(joined.contains("--cap-drop ALL"), joined);
        assertTrue(joined.contains("--security-opt no-new-privileges"), joined);
        assertTrue(joined.contains("--user 65534:65534"), joined);
        assertTrue(joined.contains("-v /tmp/some repo:/src:ro"), "the repository is mounted read-only: " + joined);
        assertTrue(joined.contains("--rm"), joined);
        assertFalse(joined.contains("--privileged"), joined);
        assertFalse(joined.contains("-v /var/run/docker.sock"), joined);
        assertTrue(joined.endsWith("bash build.sh"), "the command comes last, as separate arguments: " + joined);
        assertEquals("bash", a.get(a.size() - 2));
        assertEquals("build.sh", a.get(a.size() - 1));
    }

    @Test
    public void aHostileCommandCannotInjectDockerOptions() {
        List<String> a = new DockerSandbox("img", 1).buildArgv("n", Path.of("/r"), List.of("bash", "-c", "x; --privileged"), Limits.defaults());
        assertTrue(a.contains("x; --privileged"), "passed through as one argument, never split or interpreted");
        int image = a.indexOf("img");
        assertTrue(image > 0 && a.indexOf("x; --privileged") > image, "everything the caller supplies sits after the image name");
    }

    @Test
    public void autoPicksASandboxAndSaysWhich() {
        Sandbox s = Sandboxes.auto();
        assertTrue(s.describe().length() > 20);
    }
}
