package dev.rakshit.repair.synth;

import static dev.rakshit.repair.testkit.Assert.assertEquals;
import static dev.rakshit.repair.testkit.Assert.assertFalse;
import static dev.rakshit.repair.testkit.Assert.assertThrows;
import static dev.rakshit.repair.testkit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.rakshit.repair.Fixtures;
import dev.rakshit.repair.graph.CodeGraph;
import dev.rakshit.repair.graph.JavaIndexer;
import dev.rakshit.repair.localize.FaultLocalizer;
import dev.rakshit.repair.localize.Suspect;
import dev.rakshit.repair.patch.UnifiedDiff;
import dev.rakshit.repair.testkit.Test;
import dev.rakshit.repair.trace.FailureReport;
import dev.rakshit.repair.trace.TestOutputParser;
import dev.rakshit.repair.util.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class SynthesizerTests {
    private static final String COUNT_ABOVE = "demo.Stats#countAbove(int[],int)";

    private static final String FAILING = """
              FAIL  StatsTests.countAboveIsStrict  ->  java.lang.AssertionError: expected <1> but was <2>
                    at demo.StatsTests.countAboveIsStrict(StatsTests.java:25)
              PASS  StatsTests.countAboveNone
              PASS  StatsTests.maxOfSeveral
              PASS  StatsTests.meanOfThree
            """;

    /** The demo project with `x > t` turned into `x >= t`, plus everything a synthesizer is given. */
    private static SynthRequest request(Path dir, String feedback, Set<String> tried, int beam, int round) throws Exception {
        CodeGraph g = JavaIndexer.index(dir);
        List<FailureReport> failures = TestOutputParser.parse(FAILING, false).failed();
        List<Suspect> suspects = List.of(new Suspect(COUNT_ABOVE, 1.0, 1, Map.of("spectrum", 1.0)),
                new Suspect("demo.Stats#mean(int[])", 0.3, 2, Map.of("spectrum", 0.3)));
        return new SynthRequest(dir, g, suspects, failures, feedback, tried, beam, round);
    }

    private static Path buggy() throws Exception {
        return Fixtures.demo(Fixtures.tempDir("syn-"), Map.of("src/main/java/demo/Stats.java", Fixtures.replace(Fixtures.STATS, "if (x > t) c++;", "if (x >= t) c++;")));
    }

    // ----------------------------------------------------------------------------------------- mutation

    @Test
    public void mutationEnumeratesTheRelationalFlipsInsideTheNamedMethodOnly() throws Exception {
        List<MutationSynthesizer.Mutation> ms = MutationSynthesizer.mutationsFor(Fixtures.STATS, COUNT_ABOVE);
        assertTrue(ms.stream().anyMatch(m -> m.operator().equals("RELATIONAL") && m.replacement().equals(">=")), ms.toString());
        assertTrue(ms.stream().anyMatch(m -> m.operator().equals("INCREMENT")), ms.toString());
        int start = Fixtures.STATS.indexOf("countAbove"), end = Fixtures.STATS.length();
        for (var m : ms) assertTrue(m.start() >= start && m.end() <= end, "stays inside the method: " + m);
        assertTrue(MutationSynthesizer.mutationsFor(Fixtures.STATS, "demo.Stats#nope()").isEmpty());
    }

    @Test
    public void everyMutationChangesTheTextAndMostStillCompile() throws Exception {
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        int total = 0, compiling = 0;
        for (String id : List.of(COUNT_ABOVE, "demo.Stats#max(int[])", "demo.Stats#mean(int[])")) {
            for (var m : MutationSynthesizer.mutationsFor(Fixtures.STATS, id)) {
                String patched = MutationSynthesizer.splice(Fixtures.STATS, m);
                assertFalse(patched.equals(Fixtures.STATS), "no-op mutation: " + m);
                total++;
                Path d = Fixtures.tempDir("mut-");
                Files.createDirectories(d.resolve("demo"));
                Files.writeString(d.resolve("demo/Stats.java"), patched);
                int rc = compiler.run(null, java.io.OutputStream.nullOutputStream(), java.io.OutputStream.nullOutputStream(), "-proc:none", "-d", d.resolve("o").toString(), d.resolve("demo/Stats.java").toString());
                if (rc == 0) compiling++;
            }
        }
        assertTrue(total >= 15, "found only " + total);
        assertTrue(compiling >= total * 0.8, compiling + " of " + total + " mutations compile");
    }

    @Test
    public void mutationFindsTheOneEditThatUndoesTheBugAndRanksItNearTheTop() throws Exception {
        Path dir = buggy();
        PatchSynthesizer.Proposal p = new MutationSynthesizer().propose(request(dir, "", Set.of(), 8, 1));
        assertFalse(p.candidates().isEmpty());
        assertTrue(p.candidates().size() <= 8);
        assertEquals(Usage.NONE, p.usage());
        int at = -1;
        for (int i = 0; i < p.candidates().size(); i++) {
            Candidate c = p.candidates().get(i);
            if (c.operator().equals("RELATIONAL") && c.diff().contains("-            if (x >= t) c++;") && c.diff().contains("+            if (x > t) c++;")) at = i;
        }
        assertTrue(at >= 0 && at < 3, "the correct fix is ranked " + (at + 1) + " of " + p.candidates().size());
    }

    @Test
    public void mutationDiffsApplyCleanlyAndAreNeverRepeatedOnceTried() throws Exception {
        Path dir = buggy();
        SynthRequest r1 = request(dir, "", Set.of(), 5, 1);
        PatchSynthesizer.Proposal first = new MutationSynthesizer().propose(r1);
        for (Candidate c : first.candidates()) {
            Path copy = Fixtures.tempDir("apply-");
            dev.rakshit.repair.util.Fs.copyTree(dir, copy);
            UnifiedDiff.applyTo(copy, c.diff());
        }
        Set<String> tried = new java.util.HashSet<>();
        first.candidates().forEach(c -> tried.add(c.key()));
        PatchSynthesizer.Proposal second = new MutationSynthesizer().propose(request(dir, "", tried, 5, 2));
        for (Candidate c : second.candidates()) assertFalse(tried.contains(c.key()), "repeated " + c.key());
        assertEquals(5, second.candidates().size());
    }

    @Test
    public void mutationOutputIsDeterministic() throws Exception {
        Path dir = buggy();
        List<String> a = new MutationSynthesizer().propose(request(dir, "", Set.of(), 8, 1)).candidates().stream().map(Candidate::key).toList();
        List<String> b = new MutationSynthesizer().propose(request(dir, "", Set.of(), 8, 1)).candidates().stream().map(Candidate::key).toList();
        assertEquals(a, b);
    }

    @Test
    public void withNoSuspectsThereIsNothingToPropose() throws Exception {
        Path dir = buggy();
        SynthRequest r = request(dir, "", Set.of(), 5, 1);
        SynthRequest none = new SynthRequest(dir, r.graph(), List.of(), r.failures(), "", Set.of(), 5, 1);
        assertTrue(new MutationSynthesizer().propose(none).candidates().isEmpty());
    }

    // ------------------------------------------------------------------------------------------- the LLM

    private static final class Scripted implements LlmClient {
        final List<String> replies;
        final List<String> prompts = new ArrayList<>();
        int calls;

        Scripted(String... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public Completion complete(String system, String user, int maxTokens, double temperature) {
            prompts.add(user);
            String r = replies.get(Math.min(calls++, replies.size() - 1));
            return new Completion(r, 1000, 200);
        }
    }

    private static String goodDiff() {
        return UnifiedDiff.create("src/main/java/demo/Stats.java",
                Fixtures.replace(Fixtures.STATS, "if (x > t) c++;", "if (x >= t) c++;"), Fixtures.STATS);
    }

    @Test
    public void extractDiffReadsFencedBlocksAndBareDiffsAndIgnoresProse() {
        String d = goodDiff();
        assertEquals(d, LlmSynthesizer.extractDiff("Sure.\n```diff\n" + d + "```\nDone."));
        assertEquals(d, LlmSynthesizer.extractDiff("```\n" + d + "```"));
        assertTrue(LlmSynthesizer.extractDiff("Here is the idea: change > to >=. No diff.") == null);
        assertTrue(LlmSynthesizer.extractDiff("```java\nint x = 1;\n```") == null);
    }

    @Test
    public void llmCandidatesAreCheckedAgainstTheFilesBeforeTheyAreReturned() throws Exception {
        Path dir = buggy();
        String good = goodDiff();
        String stale = good.replace("if (x > t) c++;", "if (x > t) count++;").replace("if (x >= t) c++;", "if (x >= t) count++;");
        Scripted llm = new Scripted("RATIONALE: the comparison must be strict.\n```diff\n" + good + "```", "```diff\n" + stale + "```", "I am not sure.");
        PatchSynthesizer.Proposal p = new LlmSynthesizer(llm, 3).propose(request(dir, "", Set.of(), 8, 1));
        assertEquals(1, p.candidates().size());
        assertEquals("LLM", p.candidates().get(0).operator());
        assertTrue(p.candidates().get(0).description().startsWith("the comparison must be strict"), p.candidates().get(0).description());
        assertEquals(3, p.usage().calls());
        assertEquals(3000L, p.usage().inputTokens());
        assertEquals(2, p.notes().size());
        assertTrue(p.notes().get(0).contains("rejected") && p.notes().get(1).contains("no ```diff"), p.notes().toString());
    }

    @Test
    public void thePromptCarriesTheFailureTheGraphContextTheSourceAndTheCriticsFeedback() throws Exception {
        Path dir = buggy();
        Scripted llm = new Scripted("no idea");
        new LlmSynthesizer(llm, 1).propose(request(dir, "Candidate: replace > with >=\nREGRESSION - these passed before: A.x", Set.of(), 4, 2));
        String prompt = llm.prompts.get(0);
        assertTrue(prompt.contains("expected <1> but was <2>"), "failure message");
        assertTrue(prompt.contains("src/main/java/demo/Stats.java, lines"), "suspect location");
        assertTrue(prompt.contains("Called by") && prompt.contains("outliers"), "graph neighbourhood");
        assertTrue(prompt.contains("if (x >= t) c++;"), "current source");
        assertTrue(prompt.contains("REGRESSION - these passed before: A.x"), "feedback from the previous round");
        assertTrue(prompt.contains("demo.Stats#mean"), "other suspects");
    }

    @Test
    public void identicalSamplesCollapseToOneCandidateAndTriedKeysAreSkipped() throws Exception {
        Path dir = buggy();
        String reply = "```diff\n" + goodDiff() + "```";
        PatchSynthesizer.Proposal p = new LlmSynthesizer(new Scripted(reply), 3).propose(request(dir, "", Set.of(), 8, 1));
        assertEquals(1, p.candidates().size());
        PatchSynthesizer.Proposal again = new LlmSynthesizer(new Scripted(reply), 3).propose(request(dir, "", Set.of(p.candidates().get(0).key()), 8, 2));
        assertTrue(again.candidates().isEmpty());
    }

    @Test
    public void cascadeUsesTheFreeSynthesizerFirstAndTheModelOnlyWhenNeeded() throws Exception {
        Path dir = buggy();
        Scripted llm = new Scripted("```diff\n" + goodDiff() + "```");
        CascadeSynthesizer c = new CascadeSynthesizer(new MutationSynthesizer(), new LlmSynthesizer(llm, 1), 2);
        assertFalse(c.propose(request(dir, "", Set.of(), 4, 1)).candidates().isEmpty());
        assertEquals(0, llm.calls);
        PatchSynthesizer.Proposal late = c.propose(request(dir, "", Set.of(), 4, 3));
        assertEquals(1, llm.calls);
        assertEquals("LLM", late.candidates().get(0).operator());
        assertTrue(c.name().contains("then"));
    }

    @Test
    public void cascadeFallsBackToTheModelWhenTheCheapOneIsExhausted() throws Exception {
        Path dir = buggy();
        Scripted llm = new Scripted("```diff\n" + goodDiff() + "```");
        SynthRequest r = request(dir, "", Set.of(), 4, 1);
        SynthRequest empty = new SynthRequest(dir, r.graph(), r.suspects(), r.failures(), "", allKeys(r), 4, 1);
        PatchSynthesizer.Proposal p = new CascadeSynthesizer(new MutationSynthesizer(), new LlmSynthesizer(llm, 1), 2).propose(empty);
        assertEquals(1, llm.calls);
        assertEquals(1, p.candidates().size());
    }

    private static Set<String> allKeys(SynthRequest r) throws IOException {
        Set<String> keys = new java.util.HashSet<>();
        for (Suspect s : r.suspects()) {
            var node = r.graph().node(s.id());
            String text = Files.readString(r.workspace().resolve(node.file()));
            for (var m : MutationSynthesizer.mutationsFor(text, s.id())) keys.add(node.file() + "@" + m.start() + ":" + m.end() + "->" + m.replacement());
        }
        return keys;
    }

    // ----------------------------------------------------------------------------------------- HTTP client

    private static HttpServer stub(AtomicInteger hits, AtomicReference<String> seenBody, AtomicReference<String> seenKey, int... statuses) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/messages", ex -> {
            int n = hits.getAndIncrement();
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            seenKey.set(ex.getRequestHeaders().getFirst("x-api-key") + "|" + ex.getRequestHeaders().getFirst("anthropic-version"));
            int status = statuses[Math.min(n, statuses.length - 1)];
            String body = status == 200
                    ? "{\"content\":[{\"type\":\"text\",\"text\":\"hello \"},{\"type\":\"text\",\"text\":\"world\"}],\"usage\":{\"input_tokens\":12,\"output_tokens\":3}}"
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

    @Test
    public void anthropicClientSendsTheMessagesRequestAndParsesTextAndUsage() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>(), key = new AtomicReference<>();
        HttpServer s = stub(hits, body, key, 200);
        try {
            AnthropicClient c = new AnthropicClient("sk-test", "claude-sonnet-5-5", "http://127.0.0.1:" + s.getAddress().getPort() + "/");
            LlmClient.Completion r = c.complete("be brief", "fix it", 500, 0.0);
            assertEquals("hello world", r.text());
            assertEquals(12L, r.inputTokens());
            assertEquals(3L, r.outputTokens());
            assertEquals("sk-test|2023-06-01", key.get());
            Map<String, Object> sent = Json.parseObject(body.get());
            assertEquals("claude-sonnet-5-5", sent.get("model"));
            assertEquals("be brief", sent.get("system"));
            assertEquals(500L, ((Number) sent.get("max_tokens")).longValue());
            assertEquals("fix it", Json.str(((List<?>) sent.get("messages")).get(0), "content"));
        } finally {
            s.stop(0);
        }
    }

    @Test
    public void anthropicClientRetriesOverloadAndRateLimitButNotClientErrors() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer s = stub(hits, new AtomicReference<>(), new AtomicReference<>(), 529, 429, 200);
        try {
            AnthropicClient c = new AnthropicClient("k", "m", "http://127.0.0.1:" + s.getAddress().getPort());
            assertEquals("hello world", c.complete("s", "u", 10, 0).text());
            assertEquals(3, hits.get());
        } finally {
            s.stop(0);
        }
        AtomicInteger hits2 = new AtomicInteger();
        HttpServer s2 = stub(hits2, new AtomicReference<>(), new AtomicReference<>(), 401);
        try {
            AnthropicClient c = new AnthropicClient("bad", "m", "http://127.0.0.1:" + s2.getAddress().getPort());
            IOException e = null;
            try {
                c.complete("s", "u", 10, 0);
            } catch (IOException ex) {
                e = ex;
            }
            assertTrue(e != null && e.getMessage().contains("401"), String.valueOf(e));
            assertEquals(1, hits2.get());
        } finally {
            s2.stop(0);
        }
    }

    @Test
    public void anthropicClientRequiresAKeyAndNeverPutsItInErrors() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new AnthropicClient("", "m", "http://x"));
        AtomicInteger hits = new AtomicInteger();
        HttpServer s = stub(hits, new AtomicReference<>(), new AtomicReference<>(), 400);
        try {
            AnthropicClient c = new AnthropicClient("sk-very-secret", "m", "http://127.0.0.1:" + s.getAddress().getPort());
            try {
                c.complete("s", "u", 10, 0);
            } catch (IOException e) {
                assertFalse(e.getMessage().contains("sk-very-secret"));
            }
        } finally {
            s.stop(0);
        }
    }
}
