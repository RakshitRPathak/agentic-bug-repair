package dev.rakshit.repair.pr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs the {@code git} command line with a timeout, optional environment, and secrets scrubbed from error messages. */
public final class Git {
    private Git() {}

    public static String run(Path dir, Map<String, String> env, long timeoutSeconds, String secret, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (dir != null) pb.directory(dir.toFile());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        if (env != null) pb.environment().putAll(env);
        Process p = pb.start();
        byte[] out;
        try {
            out = p.getInputStream().readAllBytes();
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("git " + args[0] + " timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("interrupted");
        }
        String text = new String(out, StandardCharsets.UTF_8);
        if (secret != null && !secret.isEmpty()) text = text.replace(secret, "***");
        if (p.exitValue() != 0) throw new IOException("git " + args[0] + " failed: " + text.trim());
        return text;
    }

    public static String run(Path dir, String... args) throws IOException {
        return run(dir, null, 120, null, args);
    }
}
