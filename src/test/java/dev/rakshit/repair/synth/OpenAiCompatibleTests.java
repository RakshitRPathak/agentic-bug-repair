package dev.rakshit.repair.synth;

import static dev.rakshit.repair.testkit.Assert.assertEquals;
import static dev.rakshit.repair.testkit.Assert.assertFalse;
import static dev.rakshit.repair.testkit.Assert.assertThrows;
import static dev.rakshit.repair.testkit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.rakshit.repair.testkit.Test;
import dev.rakshit.repair.util.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** The OpenAI-compatible client (Ollama, LM Studio, vLLM, ...) against a local stub, and choosing a synthesizer from env. */
public class OpenAiCompatibleTests {

    /** A stub /v1/chat/completions that answers with {@code content} after the given status codes. */
    private static HttpServer stub(AtomicInteger hits, AtomicReference<String> seenBody, AtomicReference<String> seenAuth,
                                   String content, int... statuses) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/chat/completions", ex -> {
            int n = hits.getAndIncrement();
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            seenAuth.set(String.valueOf(ex.getRequestHeaders().getFirst("authorization")));
            int status = statuses[Math.min(n, statuses.length - 1)];
            String body = status == 200
                    ? Json.write(Map.of("choices", List.of(Map.of("index", 0, "message", Map.of("role", "assistant", "content", content))),
                    "usage", Map.of("prompt_tokens", 40, "completion_tokens", 7)))
                    : "{\"error\":{\"message\":\"nope\"}}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        return s;
    }

    private static String url(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort() + "/v1";
    }

    @Test
    public void sendsAChatCompletionRequestAndParsesTextAndUsage() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>(), auth = new AtomicReference<>();
        HttpServer s = stub(hits, body, auth, "RATIONALE: off by one.\n```diff\n--- a/X\n+++ b/X\n```", 200);
        try {
            OpenAiCompatibleClient c = new OpenAiCompatibleClient(url(s) + "/", "qwen2.5-coder:7b", "", Duration.ofSeconds(5));
            LlmClient.Completion r = c.complete("be brief", "fix it", 500, 0.0);
            assertTrue(r.text().startsWith("RATIONALE: off by one."), r.text());
            assertEquals(40L, r.inputTokens());
            assertEquals(7L, r.outputTokens());
            assertEquals("null", auth.get()); // no key, no Authorization header (local servers do not want one)
            Map<String, Object> sent = Json.parseObject(body.get());
            assertEquals("qwen2.5-coder:7b", sent.get("model"));
            assertEquals(500L, ((Number) sent.get("max_tokens")).longValue());
            assertEquals(Boolean.FALSE, sent.get("stream"));
            List<?> messages = (List<?>) sent.get("messages");
            assertEquals("system", Json.str(messages.get(0), "role"));
            assertEquals("be brief", Json.str(messages.get(0), "content"));
            assertEquals("user", Json.str(messages.get(1), "role"));
            assertEquals("fix it", Json.str(messages.get(1), "content"));
        } finally {
            s.stop(0);
        }
    }

    @Test
    public void sendsTheKeyAsABearerTokenOnlyWhenOneIsGivenAndNeverPutsItInErrors() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer s = stub(hits, new AtomicReference<>(), auth, "ok", 200);
        try {
            new OpenAiCompatibleClient(url(s), "m", "sk-secret-123", Duration.ofSeconds(5)).complete("s", "u", 10, 0);
            assertEquals("Bearer sk-secret-123", auth.get());
        } finally {
            s.stop(0);
        }
        HttpServer s2 = stub(new AtomicInteger(), new AtomicReference<>(), new AtomicReference<>(), "", 400);
        try {
            IOException e = null;
            try {
                new OpenAiCompatibleClient(url(s2), "m", "sk-secret-123", Duration.ofSeconds(5)).complete("s", "u", 10, 0);
            } catch (IOException ex) {
                e = ex;
            }
            assertTrue(e != null && e.getMessage().contains("400"), String.valueOf(e));
            assertFalse(e.getMessage().contains("sk-secret-123"));
        } finally {
            s2.stop(0);
        }
    }

    @Test
    public void retriesRateLimitsAndServerErrorsButNotClientErrors() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer s = stub(hits, new AtomicReference<>(), new AtomicReference<>(), "fine", 503, 429, 200);
        try {
            assertEquals("fine", new OpenAiCompatibleClient(url(s), "m", "", Duration.ofSeconds(5)).complete("s", "u", 10, 0).text());
            assertEquals(3, hits.get());
        } finally {
            s.stop(0);
        }
        AtomicInteger hits2 = new AtomicInteger();
        HttpServer s2 = stub(hits2, new AtomicReference<>(), new AtomicReference<>(), "", 404);
        try {
            IOException e = null;
            try {
                new OpenAiCompatibleClient(url(s2), "m", "", Duration.ofSeconds(5)).complete("s", "u", 10, 0);
            } catch (IOException ex) {
                e = ex;
            }
            assertTrue(e != null && e.getMessage().contains("404"), String.valueOf(e));
            assertEquals(1, hits2.get());
        } finally {
            s2.stop(0);
        }
    }

    @Test
    public void reasoningBlocksAreRemovedBeforeTheDiffIsLookedFor() throws Exception {
        HttpServer s = stub(new AtomicInteger(), new AtomicReference<>(), new AtomicReference<>(),
                "<think>maybe it is the loop bound...\n```diff\n--- a/WRONG.java\n+++ b/WRONG.java\n@@ -1,1 +1,1 @@\n-x\n+y\n```\n</think>\n"
                        + "RATIONALE: use >=.\n```diff\n--- a/RIGHT.java\n+++ b/RIGHT.java\n@@ -1,1 +1,1 @@\n-a > b\n+a >= b\n```", 200);
        try {
            String text = new OpenAiCompatibleClient(url(s), "qwen3:8b", "", Duration.ofSeconds(5)).complete("s", "u", 10, 0).text();
            assertFalse(text.contains("WRONG"), text);
            assertTrue(text.startsWith("RATIONALE: use >=."), text);
            assertTrue(String.valueOf(LlmSynthesizer.extractDiff(text)).contains("RIGHT.java"), text);
            // Without the stripping, the diff inside the reasoning would be the one extracted.
            String raw = "<think>\n```diff\n--- a/WRONG.java\n+++ b/WRONG.java\n@@ -1,1 +1,1 @@\n-x\n+y\n```\n</think>\n```diff\n--- a/RIGHT.java\n+++ b/RIGHT.java\n@@ -1 +1 @@\n-a\n+b\n```";
            assertTrue(String.valueOf(LlmSynthesizer.extractDiff(raw)).contains("WRONG.java"));
        } finally {
            s.stop(0);
        }
        // Cut off while still thinking: nothing after the open tag is an answer.
        assertEquals("", OpenAiCompatibleClient.stripThinking("<think>still going ```diff\n--- a/X"));
        assertEquals("plain answer", OpenAiCompatibleClient.stripThinking("  plain answer \n"));
    }

    @Test
    public void anUnreachableServerSaysWhereItLookedAndNeedsAModelName() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        String dead = "http://127.0.0.1:" + port + "/v1";
        IOException e = null;
        try {
            new OpenAiCompatibleClient(dead, "m", "", Duration.ofSeconds(5)).complete("s", "u", 10, 0);
        } catch (IOException ex) {
            e = ex;
        }
        assertTrue(e != null && e.getMessage().contains(dead) && e.getMessage().contains("running"), String.valueOf(e));
        assertThrows(IllegalArgumentException.class, () -> new OpenAiCompatibleClient(dead, "", "", Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new OpenAiCompatibleClient("", "m", "", Duration.ofSeconds(5)));
    }

    // ----------------------------------------------------------------------------------- choosing a synthesizer

    @Test
    public void withNoModelConfiguredTheOfflineSynthesizerIsUsed() {
        PatchSynthesizer p = SynthesizerConfig.fromEnv(Map.of(), SynthesizerConfig.Mode.CASCADE).get();
        assertTrue(p instanceof MutationSynthesizer, p.name());
        assertEquals(null, SynthesizerConfig.modelFromEnv(Map.of()));
        assertEquals("none", SynthesizerConfig.describeModel(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> SynthesizerConfig.fromEnv(Map.of(), SynthesizerConfig.Mode.MODEL_ONLY));
    }

    @Test
    public void aLocalServerUrlSelectsTheOpenAiCompatibleClientAndWinsOverAnAnthropicKey() {
        Map<String, String> env = Map.of("REPAIR_LLM_URL", OpenAiCompatibleClient.OLLAMA_URL, "REPAIR_MODEL", "qwen2.5-coder:7b",
                "ANTHROPIC_API_KEY", "sk-ant-x");
        LlmClient m = SynthesizerConfig.modelFromEnv(env);
        assertTrue(m instanceof OpenAiCompatibleClient c && c.model().equals("qwen2.5-coder:7b") && c.baseUrl().equals(OpenAiCompatibleClient.OLLAMA_URL), String.valueOf(m));
        assertEquals("qwen2.5-coder:7b via http://localhost:11434/v1", SynthesizerConfig.describeModel(env));
        assertTrue(SynthesizerConfig.fromEnv(env, SynthesizerConfig.Mode.CASCADE).get() instanceof CascadeSynthesizer);
        assertTrue(SynthesizerConfig.fromEnv(env, SynthesizerConfig.Mode.MODEL_ONLY).get() instanceof LlmSynthesizer);
        assertTrue(SynthesizerConfig.fromEnv(env, SynthesizerConfig.Mode.MUTATION).get() instanceof MutationSynthesizer);
        // A URL without a model name is a configuration error, not a silent fallback.
        assertThrows(IllegalArgumentException.class, () -> SynthesizerConfig.modelFromEnv(Map.of("REPAIR_LLM_URL", OpenAiCompatibleClient.OLLAMA_URL)));
    }

    @Test
    public void anAnthropicKeyAloneStillSelectsTheAnthropicClientAndTheKeyNeverAppearsInLabels() {
        Map<String, String> env = Map.of("ANTHROPIC_API_KEY", "sk-ant-secret");
        assertTrue(SynthesizerConfig.modelFromEnv(env) instanceof AnthropicClient);
        String label = SynthesizerConfig.describeModel(env);
        assertEquals("claude-sonnet-5-5 via the Anthropic API", label);
        assertFalse(label.contains("sk-ant"));
        assertEquals(SynthesizerConfig.Mode.MODEL_ONLY, SynthesizerConfig.Mode.parse("model-only"));
        assertEquals(SynthesizerConfig.Mode.CASCADE, SynthesizerConfig.Mode.parse("model"));
        assertThrows(IllegalArgumentException.class, () -> SynthesizerConfig.Mode.parse("gpt"));
    }

    // ------------------------------------------------------------------------------- when the model fails

    /** Offers {@code left} candidates in total, one per call, then nothing; records each round it was asked for. */
    private static final class Offline implements PatchSynthesizer {
        int left;
        final List<Integer> rounds = new ArrayList<>();

        Offline(int left) {
            this.left = left;
        }

        @Override
        public String name() {
            return "offline";
        }

        @Override
        public Proposal propose(SynthRequest req) {
            rounds.add(req.round());
            if (left == 0) return new Proposal(List.of(), Usage.NONE);
            left--;
            return new Proposal(List.of(new Candidate("k" + left, "offline", "edit " + left, "X#m", "X.java", 1, "--- a/X\n+++ b/X\n")), Usage.NONE);
        }
    }

    private static final PatchSynthesizer BROKEN_MODEL = new PatchSynthesizer() {
        @Override
        public String name() {
            return "broken model";
        }

        @Override
        public Proposal propose(SynthRequest req) throws IOException {
            throw new IOException("model server returned 500: llama-server binary not found");
        }
    };

    private static SynthRequest round(int r) {
        return new SynthRequest(null, null, List.of(), List.of(), "", java.util.Set.of(), 4, r);
    }

    @Test
    public void aModelOutageFallsBackToTheOfflineSynthesizerInsteadOfEndingTheRun() throws Exception {
        // The laptop run that motivated this: Ollama answered 500 for every request, and bug 03, which the offline
        // synthesizer fixes in round 3, was lost because round 3 went to the model and the error ended the run.
        Offline offline = new Offline(3);
        CascadeSynthesizer c = new CascadeSynthesizer(offline, BROKEN_MODEL, 2);
        c.propose(round(1));
        c.propose(round(2));
        PatchSynthesizer.Proposal p = c.propose(round(3));
        assertEquals(1, p.candidates().size());
        assertTrue(p.notes().stream().anyMatch(n -> n.contains("model unavailable") && n.contains("llama-server")), p.notes().toString());
        assertEquals(1, c.modelErrors());
        assertEquals(List.of(1, 2, 3), offline.rounds);
    }

    @Test
    public void whenTheOfflineSynthesizerHasNothingLeftTheModelErrorStillSurfaces() {
        CascadeSynthesizer c = new CascadeSynthesizer(new Offline(0), BROKEN_MODEL, 2);
        try {
            c.propose(round(3));
            throw new AssertionError("expected the model error to surface");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("llama-server"), expected.getMessage());
        }
        assertEquals(1, c.modelErrors());
    }

    @Test
    public void aReportWithModelFailuresSaysSoInsteadOfLookingLikeModelResults() {
        String report = dev.rakshit.repair.bench.Bench.report(List.of(), List.of(
                new dev.rakshit.repair.bench.Bench.RepairResult("01", "operator", "dev", true, true, 2, 12, 14, 9_000, "ok", "replace > with >=", 0, 0, 0, 0),
                new dev.rakshit.repair.bench.Bench.RepairResult("03", "operator", "dev", false, false, 3, 16, 17, 9_000,
                        "unexpected failure: java.io.IOException: model server returned 500", "", 0, 0, 0, 1)),
                "mutation first, then model: qwen2.5-coder:7b via http://localhost:11434/v1");
        assertTrue(report.contains("Warning: the model failed in 1 of 2 bugs"), report);
        assertTrue(report.contains("These are not model results."), report);
        String clean = dev.rakshit.repair.bench.Bench.report(List.of(), List.of(
                new dev.rakshit.repair.bench.Bench.RepairResult("01", "operator", "dev", true, true, 2, 12, 14, 9_000, "ok", "x", 0, 0, 0, 0)));
        assertFalse(clean.contains("Warning"), clean);
    }
}
