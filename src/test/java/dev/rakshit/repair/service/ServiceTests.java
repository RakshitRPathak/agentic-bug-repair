package dev.rakshit.repair.service;

import static dev.rakshit.repair.testkit.Assert.assertEquals;
import static dev.rakshit.repair.testkit.Assert.assertFalse;
import static dev.rakshit.repair.testkit.Assert.assertThrows;
import static dev.rakshit.repair.testkit.Assert.assertTrue;

import dev.rakshit.repair.Fixtures;
import dev.rakshit.repair.agent.RepairConfig;
import dev.rakshit.repair.agent.RepairEvent;
import dev.rakshit.repair.agent.RepairRun;
import dev.rakshit.repair.pr.DryRunPublisher;
import dev.rakshit.repair.pr.Git;
import dev.rakshit.repair.pr.PullRequestPublisher;
import dev.rakshit.repair.sandbox.Limits;
import dev.rakshit.repair.sandbox.ProcessSandbox;
import dev.rakshit.repair.sandbox.Sandbox;
import dev.rakshit.repair.sandbox.SandboxResult;
import dev.rakshit.repair.synth.MutationSynthesizer;
import dev.rakshit.repair.testkit.Test;
import dev.rakshit.repair.util.Json;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

public class ServiceTests {
    private static final String SECRET = "It's a Secret to Everybody";
    private static final String STATS = "src/main/java/demo/Stats.java";

    // -------------------------------------------------------------------------------------------- helpers

    /** A sandbox that holds every run until released, so queue behaviour can be observed. */
    private static final class Blocking implements Sandbox {
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public SandboxResult run(Path repo, List<String> command, Limits limits) throws IOException {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new SandboxResult(0, "", false, false, false, 0);
        }

        @Override
        public String describe() {
            return "blocking test sandbox";
        }
    }

    private static ServiceConfig config(Path data, String secret, Set<String> repos, List<Path> roots, String apiKey, int workers, int cap,
                                        Function<String, String> clone, Sandbox sandbox) {
        return new ServiceConfig(data, secret, repos, roots, apiKey, workers, cap,
                RepairConfig.defaults().withParallelism(2).withLimits(new Limits(60_000, 1024, 128, 1 << 20)),
                MutationSynthesizer::new, sandbox, new DryRunPublisher(data.resolve("pr")), clone);
    }

    private static Path buggyProject(String name) throws Exception {
        Path dir = Fixtures.demo(Fixtures.tempDir(name), Map.of(STATS, Fixtures.replace(Fixtures.STATS, "if (x > t) c++;", "if (x >= t) c++;")));
        return dir;
    }

    /** Turns a directory into a git repository on branch main and returns a bare clone of it plus its HEAD sha. */
    private static String[] bareRemote(Path project) throws Exception {
        Git.run(project, "init", "-q", "-b", "main");
        Git.run(project, "add", "-A");
        Git.run(project, "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "buggy");
        Path bare = Fixtures.tempDir("bare-").resolve("widgets.git");
        Git.run(null, null, 60, null, "clone", "--quiet", "--bare", project.toString(), bare.toString());
        return new String[] {bare.toString(), Git.run(project, "rev-parse", "HEAD").trim()};
    }

    private static byte[] workflowRun(String conclusion, String repo, String sha) {
        return ("{\"action\":\"completed\",\"repository\":{\"full_name\":\"" + repo + "\"},\"workflow_run\":{\"conclusion\":\"" + conclusion
                + "\",\"head_sha\":\"" + sha + "\",\"head_branch\":\"main\"}}").getBytes(StandardCharsets.UTF_8);
    }

    private static void waitFinished(RepairService svc, String id) throws Exception {
        long end = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < end) {
            RepairRun r = svc.store().live(id);
            if (r != null && r.finished()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("run " + id + " did not finish");
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder post(String url, String body, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return b;
    }

    /** Raw HTTP so the Host header can be forged, which the JDK client forbids. */
    private static String raw(int port, String request) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            InputStream in = s.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------------------------------- signature

    @Test
    public void signatureMatchesGithubsDocumentedTestVector() {
        byte[] body = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        String expected = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";
        assertEquals(expected, Signature.sign(SECRET, body));
        assertTrue(Signature.valid(SECRET, body, expected));
    }

    @Test
    public void signatureRejectsTamperingWrongSecretsAndMissingHeaders() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String good = Signature.sign("s", body);
        assertTrue(Signature.valid("s", body, good));
        assertFalse(Signature.valid("s", "{\"a\":2}".getBytes(StandardCharsets.UTF_8), good));
        assertFalse(Signature.valid("other", body, good));
        assertFalse(Signature.valid("s", body, null));
        assertFalse(Signature.valid("s", body, good.substring(0, good.length() - 1)));
        assertFalse(Signature.valid("s", body, "sha256="));
        assertFalse(Signature.valid("", body, good), "an empty secret never authenticates anything");
    }

    // ------------------------------------------------------------------------------------------ job queue

    @Test
    public void queueRunsAtMostWorkersJobsHoldsCapacityAndRefusesTheRest() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        JobQueue q = new JobQueue(2, 3);
        for (int i = 0; i < 5; i++) q.submit(() -> {
            try {
                gate.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(100);
        assertEquals(2, q.running());
        assertEquals(3, q.queued());
        assertThrows(JobQueue.QueueFullException.class, () -> q.submit(() -> { }));
        assertEquals(1, q.rejected());
        gate.countDown();
        assertTrue(q.drain(5000));
        assertEquals(5, q.completed());
    }

    @Test
    public void aJobThatThrowsDoesNotKillTheWorker() throws Exception {
        JobQueue q = new JobQueue(1, 5);
        int[] ran = {0};
        q.submit(() -> {
            throw new IllegalStateException("boom");
        });
        q.submit(() -> ran[0]++);
        assertTrue(q.drain(5000));
        assertEquals(1, ran[0]);
    }

    // ------------------------------------------------------------------------------------------- webhooks

    @Test
    public void webhookAnswersEachSituationWithTheRightStatus() throws Exception {
        Path data = Fixtures.tempDir("svc-");
        Blocking sandbox = new Blocking();
        try (RepairService svc = new RepairService(config(data, SECRET, Set.of("acme/widgets"), List.of(data), "", 1, 1, s -> "/nonexistent/" + s, sandbox))) {
            WebhookHandler h = new WebhookHandler(svc);
            byte[] fail = workflowRun("failure", "acme/widgets", "abcdef1234567");
            String sig = Signature.sign(SECRET, fail);

            assertEquals(401, h.handle("workflow_run", "d0", "sha256=bad", fail).status());
            assertEquals(401, h.handle("workflow_run", "d0", null, fail).status());
            assertEquals(200, h.handle("ping", "d1", Signature.sign(SECRET, "{}".getBytes(StandardCharsets.UTF_8)), "{}".getBytes(StandardCharsets.UTF_8)).status());
            byte[] ok = workflowRun("success", "acme/widgets", "abcdef1234567");
            var ignored = h.handle("workflow_run", "d2", Signature.sign(SECRET, ok), ok);
            assertEquals(200, ignored.status());
            assertTrue(ignored.body().get("message").toString().contains("success"));
            byte[] other = workflowRun("failure", "evil/other", "abcdef1234567");
            assertEquals(403, h.handle("workflow_run", "d3", Signature.sign(SECRET, other), other).status());
            byte[] badSlug = workflowRun("failure", "../../etc", "abcdef1234567");
            assertEquals(400, h.handle("workflow_run", "d4", Signature.sign(SECRET, badSlug), badSlug).status());
            byte[] badSha = workflowRun("failure", "acme/widgets", "--upload-pack=x");
            assertEquals(400, h.handle("workflow_run", "d5", Signature.sign(SECRET, badSha), badSha).status());
            byte[] junk = "not json".getBytes(StandardCharsets.UTF_8);
            assertEquals(400, h.handle("workflow_run", "d6", Signature.sign(SECRET, junk), junk).status());
            assertEquals(200, h.handle("issues", "d7", sig, fail).status());                  // unknown events are acknowledged and ignored

            var accepted = h.handle("workflow_run", "d8", sig, fail);
            assertEquals(202, accepted.status());
            assertTrue(accepted.body().containsKey("runId"));
            var dup = h.handle("workflow_run", "d8", sig, fail);
            assertEquals(200, dup.status());
            assertTrue(dup.body().get("message").toString().contains("duplicate"), dup.body().toString());
            assertEquals(202, h.handle("workflow_run", "d9", sig, fail).status());          // second job waits in the queue
            var full = h.handle("workflow_run", "d10", sig, fail);
            assertEquals(429, full.status());                                                // third is refused: backpressure
            sandbox.release.countDown();
        }
    }

    @Test
    public void aServerWithoutASecretDoesNotAnswerWebhooksAtAll() throws Exception {
        Path data = Fixtures.tempDir("svc-");
        try (RepairService svc = new RepairService(config(data, "", Set.of("a/b"), List.of(data), "", 1, 1, s -> s, new ProcessSandbox()))) {
            byte[] body = workflowRun("failure", "a/b", "abcdef1");
            assertEquals(503, new WebhookHandler(svc).handle("workflow_run", "x", Signature.sign("guess", body), body).status());
        }
    }

    @Test
    public void checkSuiteEventsAreUnderstoodToo() throws Exception {
        Path data = Fixtures.tempDir("svc-");
        Blocking sandbox = new Blocking();
        try (RepairService svc = new RepairService(config(data, SECRET, Set.of("acme/widgets"), List.of(data), "", 1, 2, s -> "/nonexistent", sandbox))) {
            byte[] body = ("{\"action\":\"completed\",\"repository\":{\"full_name\":\"acme/widgets\"},\"check_suite\":{\"conclusion\":\"failure\","
                    + "\"head_sha\":\"abcdef1\",\"head_branch\":\"feature/x\"}}").getBytes(StandardCharsets.UTF_8);
            assertEquals(202, new WebhookHandler(svc).handle("check_suite", "c1", Signature.sign(SECRET, body), body).status());
            sandbox.release.countDown();
        }
    }

    // ----------------------------------------------------------------------- the whole thing over real HTTP

    @Test
    public void aFailedBuildWebhookBecomesAReviewedPullRequestOverHttp() throws Exception {
        Path project = buggyProject("e2e-");
        String[] remote = bareRemote(project);
        Path data = Fixtures.tempDir("svc-data-");
        try (RepairService svc = new RepairService(config(data, SECRET, Set.of("acme/widgets"), List.of(data), "", 1, 4, s -> remote[0], new ProcessSandbox()));
             HttpApi api = new HttpApi(svc, "127.0.0.1", 0)) {
            api.start();
            String base = "http://127.0.0.1:" + api.port();

            // the dashboard is served with a strict content security policy
            HttpResponse<String> page = send(HttpRequest.newBuilder(URI.create(base + "/")));
            assertEquals(200, page.statusCode());
            assertTrue(page.headers().firstValue("content-security-policy").orElse("").contains("default-src 'self'"));
            assertEquals("nosniff", page.headers().firstValue("x-content-type-options").orElse(""));
            assertFalse(page.body().contains("onclick="), "no inline handlers: they would violate the CSP");

            // GitHub calls the webhook
            byte[] payload = workflowRun("failure", "acme/widgets", remote[1]);
            assertEquals(401, send(post(base + "/webhooks/github", new String(payload, StandardCharsets.UTF_8),
                    "X-GitHub-Event", "workflow_run", "X-Hub-Signature-256", "sha256=00")).statusCode());
            HttpResponse<String> accepted = send(post(base + "/webhooks/github", new String(payload, StandardCharsets.UTF_8),
                    "X-GitHub-Event", "workflow_run", "X-GitHub-Delivery", "delivery-1", "X-Hub-Signature-256", Signature.sign(SECRET, payload)));
            assertEquals(202, accepted.statusCode());
            String id = (String) Json.parseObject(accepted.body()).get("runId");
            waitFinished(svc, id);

            Map<String, Object> run = Json.parseObject(send(HttpRequest.newBuilder(URI.create(base + "/api/runs/" + id))).body());
            assertEquals("RESOLVED", run.get("state"), String.valueOf(run.get("outcome")));
            assertEquals("acme/widgets", run.get("repo"));
            assertEquals(remote[1], run.get("sha"));
            assertTrue(((String) run.get("diff")).contains("+            if (x > t) c++;"));

            HttpResponse<String> patch = send(HttpRequest.newBuilder(URI.create(base + "/api/runs/" + id + "/patch")));
            assertEquals(200, patch.statusCode());
            assertTrue(patch.headers().firstValue("content-disposition").orElse("").contains("attachment"));
            assertEquals(run.get("diff"), patch.body());

            // the list and the metrics include it
            List<?> list = Json.parse(send(HttpRequest.newBuilder(URI.create(base + "/api/runs"))).body()) instanceof List<?> l ? l : List.of();
            assertEquals(1, list.size());
            Map<String, Object> metrics = Json.parseObject(send(HttpRequest.newBuilder(URI.create(base + "/api/metrics"))).body());
            assertEquals(1L, ((Number) metrics.get("resolved")).longValue());
            assertEquals(1.0, ((Number) metrics.get("solvedWithin10")).doubleValue());

            // one-click approval, once
            HttpResponse<String> approved = send(post(base + "/api/runs/" + id + "/approve", "{}", "Content-Type", "application/json"));
            assertEquals(200, approved.statusCode(), approved.body());
            assertTrue(Files.readString(data.resolve("pr").resolve(id).resolve("fix.patch")).equals(run.get("diff")));
            assertTrue(Files.readString(data.resolve("pr").resolve(id).resolve("PR.md")).contains("## Verification"));
            assertEquals(409, send(post(base + "/api/runs/" + id + "/approve", "{}", "Content-Type", "application/json")).statusCode());

            // the same delivery again is not repaired twice
            HttpResponse<String> again = send(post(base + "/webhooks/github", new String(payload, StandardCharsets.UTF_8),
                    "X-GitHub-Event", "workflow_run", "X-GitHub-Delivery", "delivery-1", "X-Hub-Signature-256", Signature.sign(SECRET, payload)));
            assertEquals(200, again.statusCode());
            assertEquals(1, svc.store().liveRuns().size());
        }
    }

    // ------------------------------------------------------------------------------------- API hardening

    @Test
    public void stateChangingCallsRefuseBrowsersTricksAndStrangers() throws Exception {
        Path root = Fixtures.tempDir("root-");
        Path project = buggyProject("api-");
        Path inside = Files.createDirectories(root.resolve("p"));
        dev.rakshit.repair.util.Fs.copyTree(project, inside);
        Path data = Fixtures.tempDir("svc-data-");
        Blocking sandbox = new Blocking();
        try (RepairService svc = new RepairService(config(data, "", Set.of(), List.of(root), "", 1, 1, s -> s, sandbox));
             HttpApi api = new HttpApi(svc, "127.0.0.1", 0)) {
            api.start();
            String base = "http://127.0.0.1:" + api.port();
            String body = "{\"repoPath\":\"" + inside.toString().replace("\\", "\\\\") + "\"}";

            // a web page cannot send a "simple" cross-site POST: JSON content type is required
            assertEquals(415, send(post(base + "/api/runs", body, "Content-Type", "text/plain")).statusCode());
            assertEquals(415, send(post(base + "/api/runs", body)).statusCode());
            // a foreign Origin is refused even with the right content type
            assertEquals(403, send(post(base + "/api/runs", body, "Content-Type", "application/json", "Origin", "https://evil.example")).statusCode());
            // DNS rebinding: the request arrives for a hostname that is not loopback
            String forged = raw(api.port(), "POST /api/runs HTTP/1.1\r\nHost: evil.example\r\nContent-Type: application/json\r\nContent-Length: "
                    + body.length() + "\r\nConnection: close\r\n\r\n" + body);
            assertTrue(forged.startsWith("HTTP/1.1 403"), forged);
            // paths outside the allowed roots
            String outside = "{\"repoPath\":\"" + Files.createTempDirectory("elsewhere-").toString() + "\"}";
            assertEquals(403, send(post(base + "/api/runs", outside, "Content-Type", "application/json")).statusCode());
            assertEquals(403, send(post(base + "/api/runs", "{\"repoPath\":\"" + root + "/p/../../../etc\"}", "Content-Type", "application/json")).statusCode());
            assertEquals(400, send(post(base + "/api/runs", "{\"repoPath\":\"/no/such/dir\"}", "Content-Type", "application/json")).statusCode());
            assertEquals(400, send(post(base + "/api/runs", "[1]", "Content-Type", "application/json")).statusCode());
            assertEquals(400, send(post(base + "/api/runs", "{}", "Content-Type", "application/json")).statusCode());
            assertEquals(0, svc.store().liveRuns().size(), "none of the refused requests created a run");

            // the legitimate call works; a second queues; a third meets backpressure
            assertEquals(202, send(post(base + "/api/runs", body, "Content-Type", "application/json", "Origin", base)).statusCode());
            assertEquals(202, send(post(base + "/api/runs", body, "Content-Type", "application/json")).statusCode());
            HttpResponse<String> full = send(post(base + "/api/runs", body, "Content-Type", "application/json"));
            assertEquals(429, full.statusCode());
            sandbox.release.countDown();

            assertEquals(404, send(HttpRequest.newBuilder(URI.create(base + "/api/runs/nope"))).statusCode());
            assertEquals(404, send(HttpRequest.newBuilder(URI.create(base + "/api/runs/../../etc/passwd"))).statusCode());
            assertEquals(404, send(HttpRequest.newBuilder(URI.create(base + "/nothing"))).statusCode());
            assertEquals(404, send(post(base + "/api/runs/zzz/approve", "{}", "Content-Type", "application/json")).statusCode());
        }
    }

    @Test
    public void oversizedBodiesAreRejected() throws Exception {
        Path data = Fixtures.tempDir("svc-");
        try (RepairService svc = new RepairService(config(data, SECRET, Set.of("a/b"), List.of(data), "", 1, 1, s -> s, new ProcessSandbox()));
             HttpApi api = new HttpApi(svc, "127.0.0.1", 0)) {
            api.start();
            String huge = "x".repeat((2 << 20) + 10);
            assertEquals(413, send(post("http://127.0.0.1:" + api.port() + "/webhooks/github", huge, "X-GitHub-Event", "push")).statusCode());
        }
    }

    @Test
    public void anApiKeyIsRequiredWhenConfiguredAndForAnyNonLoopbackBind() throws Exception {
        Path data = Fixtures.tempDir("svc-");
        try (RepairService open = new RepairService(config(data, "", Set.of(), List.of(data), "", 1, 1, s -> s, new ProcessSandbox()))) {
            assertThrows(IllegalArgumentException.class, () -> {
                try {
                    new HttpApi(open, "0.0.0.0", 0);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        try (RepairService keyed = new RepairService(config(data, "", Set.of(), List.of(data), "k3y", 1, 1, s -> s, new ProcessSandbox()));
             HttpApi api = new HttpApi(keyed, "127.0.0.1", 0)) {
            api.start();
            String base = "http://127.0.0.1:" + api.port();
            assertEquals(401, send(post(base + "/api/runs", "{}", "Content-Type", "application/json")).statusCode());
            assertEquals(401, send(post(base + "/api/runs", "{}", "Content-Type", "application/json", "X-API-Key", "k3x")).statusCode());
            assertEquals(400, send(post(base + "/api/runs", "{}", "Content-Type", "application/json", "X-API-Key", "k3y")).statusCode(), "right key gets past authentication");
            assertEquals(200, send(HttpRequest.newBuilder(URI.create(base + "/api/metrics"))).statusCode(), "read-only endpoints stay open");
        }
    }

    // ------------------------------------------------------------------------------------ run store, metrics

    private static RepairRun finished(String label, boolean resolved, int attempts) {
        RepairRun r = new RepairRun(label);
        r.fire(RepairEvent.READY, "r");
        r.fire(RepairEvent.SUSPECTS_FOUND, "s");
        r.fire(RepairEvent.CANDIDATES_READY, "c");
        for (int i = 0; i < attempts; i++) r.addAttempt(Map.of("round", 1, "rank", i + 1));
        if (resolved) {
            r.fire(RepairEvent.ALL_TESTS_PASS, "p");
            r.fire(RepairEvent.PUBLISHED, "d");
        } else {
            r.fire(RepairEvent.STILL_FAILING, "f");
            r.fire(RepairEvent.OUT_OF_BUDGET, "o");
        }
        return r;
    }

    @Test
    public void metricsAggregateResolveRateAndSolvedWithinK() {
        Map<String, Object> m = Metrics.summarize(List.of(finished("a", true, 1), finished("b", true, 5), finished("c", false, 8), new RepairRun("still running")));
        assertEquals(4, m.get("runs"));
        assertEquals(3, m.get("finished"));
        assertEquals(2, m.get("resolved"));
        assertEquals(2.0 / 3, m.get("resolveRate"));
        assertEquals(1.0 / 3, m.get("solvedWithin1"));
        assertEquals(1.0 / 3, m.get("solvedWithin3"));
        assertEquals(2.0 / 3, m.get("solvedWithin10"));
        assertEquals(0, Metrics.summarize(List.of()).get("finished"));
    }

    @Test
    public void runStorePersistsFinishedRunsAcrossRestartsAndSkipsCorruptFiles() throws Exception {
        Path dir = Fixtures.tempDir("store-");
        RunStore a = new RunStore(dir, 50);
        RepairRun r = finished("persisted", true, 2);
        a.add(r);
        a.persist(r);
        Files.writeString(dir.resolve("garbage.json"), "{not json");
        RunStore b = new RunStore(dir, 50);
        assertEquals(1, b.summaries().size());
        assertEquals("persisted", b.summaries().get(0).get("label"));
        assertEquals("RESOLVED", b.get(r.id()).get("state"));
        assertTrue(b.live(r.id()) == null, "archived runs are history, not live");
    }

    @Test
    public void runStoreKeepsOnlyTheNewestRuns() {
        RunStore s = new RunStore(null, 3);
        for (int i = 0; i < 6; i++) s.add(new RepairRun("r" + i));
        assertEquals(3, s.liveRuns().size());
    }
}
