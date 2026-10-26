package dev.rakshit.repair.synth;

import dev.rakshit.repair.graph.CodeNode;
import dev.rakshit.repair.localize.GraphRag;
import dev.rakshit.repair.localize.Suspect;
import dev.rakshit.repair.patch.UnifiedDiff;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks a language model for a surgical fix. The prompt carries the failing tests, the suspect's file with line numbers,
 * and the GraphRAG neighbourhood (callers, callees, fields written), and demands a unified diff rather than whole
 * files: it is cheaper in tokens, it cannot silently rewrite unrelated code, and it is checked mechanically. A diff
 * whose context does not match the file is rejected before any sandbox time is spent, and the reason goes back into the
 * next prompt.
 */
public final class LlmSynthesizer implements PatchSynthesizer {
    static final String SYSTEM = """
            You are a program-repair agent working on a Java repository. Tests fail; you are given the failing tests, the
            suspected file with line numbers, and related code. Find the smallest change to NON-TEST source code that makes
            the tests pass without breaking others.

            Reply with exactly:
            RATIONALE: one or two sentences saying what is wrong and why your change fixes it.
            then one fenced block ```diff containing a unified diff (as `git diff` prints it):
              --- a/<path>      +++ b/<path>      @@ -start,count +start,count @@
            Rules: paths are relative to the repository root, exactly as shown. Each hunk has about 3 lines of unchanged
            context copied exactly from the file. Change as few lines as possible. Never modify tests, build scripts or CI
            files. Do not add dependencies.""";

    private static final Pattern FENCE = Pattern.compile("```(?:diff|patch)?\\s*\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern RATIONALE = Pattern.compile("RATIONALE:\\s*(.+?)(?:\\n\\s*\\n|```)", Pattern.DOTALL);

    private final LlmClient client;
    private final int samples;
    private final int contextHops;

    public LlmSynthesizer(LlmClient client, int samples) {
        this.client = client;
        this.samples = Math.max(1, samples);
        this.contextHops = 2;
    }

    @Override
    public String name() {
        return "LLM (" + samples + " sample" + (samples > 1 ? "s" : "") + " per round)";
    }

    @Override
    public Proposal propose(SynthRequest req) throws IOException {
        if (req.suspects().isEmpty()) return new Proposal(List.of(), Usage.NONE, List.of("no suspects to repair"));
        Suspect top = req.suspects().get(0);
        CodeNode node = req.graph().node(top.id());
        String prompt = buildPrompt(req, node);
        List<Candidate> out = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Usage usage = Usage.NONE;
        int n = Math.min(samples, req.beam());
        for (int i = 0; i < n; i++) {
            LlmClient.Completion c = client.complete(SYSTEM, prompt, 2000, samples > 1 ? 0.7 : 0.0);
            usage = usage.plus(new Usage(1, c.inputTokens(), c.outputTokens()));
            String diff = extractDiff(c.text());
            if (diff == null) {
                notes.add("the reply contained no ```diff block");
                continue;
            }
            try {
                checkApplies(req.workspace(), diff);
            } catch (UnifiedDiff.PatchException e) {
                notes.add("your previous diff was rejected: " + e.getMessage());
                continue;
            }
            String key = "llm:" + sha(diff);
            if (req.tried().contains(key) || out.stream().anyMatch(x -> x.key().equals(key))) continue;
            Matcher r = RATIONALE.matcher(c.text());
            String why = r.find() ? r.group(1).trim().replaceAll("\\s+", " ") : "model-proposed change";
            out.add(new Candidate(key, "LLM", why, top.id(), node.file(), node.startLine(), diff));
        }
        return new Proposal(out, usage, notes);
    }

    String buildPrompt(SynthRequest req, CodeNode suspect) throws IOException {
        StringBuilder sb = new StringBuilder();
        var ctx = GraphRag.context(req.graph(), suspect.id(), contextHops);
        sb.append(GraphRag.render(req.graph(), ctx, req.failures()));
        sb.append("\n## Full file ").append(suspect.file()).append(" (current state)\n");
        List<String> lines = Files.readAllLines(req.workspace().resolve(suspect.file()), StandardCharsets.UTF_8);
        int from = 1, to = lines.size();
        if (lines.size() > 400) {
            from = Math.max(1, suspect.startLine() - 120);
            to = Math.min(lines.size(), suspect.endLine() + 120);
        }
        for (int i = from; i <= to; i++) sb.append(String.format("%4d | %s%n", i, lines.get(i - 1)));
        if (req.suspects().size() > 1) {
            sb.append("\n## Other suspects, in order: ");
            for (int i = 1; i < Math.min(4, req.suspects().size()); i++) sb.append(req.suspects().get(i).id()).append("; ");
            sb.append('\n');
        }
        if (!req.feedback().isBlank()) sb.append("\n## Result of your previous attempt\n").append(req.feedback()).append('\n');
        return sb.toString();
    }

    static String extractDiff(String reply) {
        Matcher m = FENCE.matcher(reply);
        while (m.find()) {
            String body = m.group(1);
            if (body.contains("@@") && (body.contains("--- ") || body.contains("+++ "))) return body.endsWith("\n") ? body : body + "\n";
        }
        int at = reply.indexOf("--- a/");
        if (at >= 0 && reply.indexOf("@@", at) > 0) return reply.substring(at).trim() + "\n";
        return null;
    }

    /** Dry run: every hunk must match its file. Nothing is written. */
    static void checkApplies(Path root, String diff) throws UnifiedDiff.PatchException, IOException {
        for (UnifiedDiff.FileDiff fd : UnifiedDiff.parse(diff)) {
            Path p = root.resolve(fd.path()).normalize();
            if (!p.startsWith(root.normalize()) || !Files.isRegularFile(p)) throw new UnifiedDiff.PatchException("no such file: " + fd.path());
            UnifiedDiff.apply(Files.readString(p, StandardCharsets.UTF_8), fd);
        }
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
