package dev.rakshit.repair.pr;

import static dev.rakshit.repair.testkit.Assert.assertEquals;
import static dev.rakshit.repair.testkit.Assert.assertFalse;
import static dev.rakshit.repair.testkit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.rakshit.repair.Fixtures;
import dev.rakshit.repair.agent.RepairConfig;
import dev.rakshit.repair.agent.RepairPipeline;
import dev.rakshit.repair.agent.RepairRun;
import dev.rakshit.repair.sandbox.ProcessSandbox;
import dev.rakshit.repair.synth.MutationSynthesizer;
import dev.rakshit.repair.testkit.Test;
import dev.rakshit.repair.util.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class PublisherTests {
    private static final String TOKEN = "ghp_SECRET_TOKEN_1234567890";

    /** A resolved run for a git checkout whose "origin" is a local bare repository. */
    private static final class World {
        Path checkout, bare;
        RepairRun run;
    }

    private static World world() throws Exception {
        World w = new World();
        w.checkout = Fixtures.demo(Fixtures.tempDir("pub-"), Map.of("src/main/java/demo/Stats.java",
                Fixtures.replace(Fixtures.STATS, "if (x > t) c++;", "if (x >= t) c++;")));
        Git.run(w.checkout, "init", "-q", "-b", "main");
        Git.run(w.checkout, "add", "-A");
        Git.run(w.checkout, "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "buggy");
        w.bare = Fixtures.tempDir("pub-bare-").resolve("widgets.git");
        Git.run(null, null, 60, null, "clone", "--quiet", "--bare", w.checkout.toString(), w.bare.toString());
        w.run = new RepairPipeline(RepairConfig.defaults().withParallelism(2), new ProcessSandbox(), new MutationSynthesizer()).run(w.checkout, "pub");
        assertTrue(w.run.resolved(), w.run.outcome());
        w.run.setSource(w.checkout, "acme/widgets", "main", Git.run(w.checkout, "rev-parse", "HEAD").trim());
        return w;
    }

    private static final class Api implements AutoCloseable {
        final HttpServer server;
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<String> auth = new CopyOnWriteArrayList<>();
        volatile int status = 201;
        volatile String reply = "{\"html_url\":\"https://github.com/acme/widgets/pull/7\"}";

        Api() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                requests.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
                byte[] b = reply.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(status, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    public void dryRunWritesThePullRequestAndThePatchAndNothingElse() throws Exception {
        World w = world();
        Path out = Fixtures.tempDir("dry-");
        PullRequestPublisher.Result r = new DryRunPublisher(out).publish(w.run);
        assertTrue(r.note().contains("dry run"));
        assertEquals(w.run.diff(), Files.readString(out.resolve(w.run.id()).resolve("fix.patch")));
        String md = Files.readString(out.resolve(w.run.id()).resolve("PR.md"));
        assertEquals("Fix StatsTests.countAboveIsStrict", PrBody.title(w.run));
        assertTrue(md.startsWith("# Fix StatsTests.countAboveIsStrict\n"), md.substring(0, 60));
        assertTrue(md.contains("## Where the bug was found") && md.contains("## Blast radius") && md.contains("## Verification") && md.contains("## Telemetry"));
        assertTrue(md.contains("A person should review this before it is merged."));
    }

    @Test
    public void aRunWithoutADiffCannotBePublished() {
        RepairRun empty = new RepairRun("nothing");
        boolean threw = false;
        try {
            new DryRunPublisher(Fixtures.tempDir("dry-")).publish(empty);
        } catch (IOException e) {
            threw = true;
        }
        assertTrue(threw);
    }

    @Test
    public void githubPublisherPushesABranchWithTheFixAndOpensADraftPullRequest() throws Exception {
        World w = world();
        try (Api api = new Api()) {
            PullRequestPublisher.Result r = new GitHubPublisher(TOKEN, api.url(), slug -> w.bare.toString()).publish(w.run);
            assertEquals("https://github.com/acme/widgets/pull/7", r.url());

            // the branch exists on the remote and holds exactly the verified change
            String branch = "autofix/" + w.run.id();
            String branches = Git.run(w.bare, "branch", "--list", branch);
            assertTrue(branches.contains(branch), branches);
            String fixed = Git.run(w.bare, "show", branch + ":src/main/java/demo/Stats.java");
            assertTrue(fixed.contains("if (x > t) c++;") && !fixed.contains("if (x >= t) c++;"));
            assertEquals("main", Git.run(w.bare, "symbolic-ref", "--short", "HEAD").trim());
            assertFalse(Git.run(w.bare, "show", "main:src/main/java/demo/Stats.java").contains("if (x > t) c++;"), "main is untouched");
            assertEquals("1", Git.run(w.bare, "rev-list", "--count", "main.." + branch).trim(), "exactly one commit");
            assertTrue(Git.run(w.bare, "log", "-1", "--format=%s", branch).contains("Fix StatsTests.countAboveIsStrict"));

            // the API call
            assertEquals(1, api.requests.size());
            assertTrue(api.requests.get(0).startsWith("POST /repos/acme/widgets/pulls "), api.requests.get(0));
            Map<String, Object> sent = Json.parseObject(api.requests.get(0).substring(api.requests.get(0).indexOf('{')));
            assertEquals(Boolean.TRUE, sent.get("draft"));
            assertEquals(branch, sent.get("head"));
            assertEquals("main", sent.get("base"));
            assertTrue(sent.get("body").toString().contains("## Verification"));
            assertEquals("Bearer " + TOKEN, api.auth.get(0));
        }
        assertEquals(w.run.diff(), Git.run(w.checkout, "diff", "--no-color").isEmpty() ? w.run.diff() : "the source checkout was modified");
    }

    @Test
    public void githubErrorsSurfaceWithoutLeakingTheToken() throws Exception {
        World w = world();
        try (Api api = new Api()) {
            api.status = 422;
            api.reply = "{\"message\":\"Validation Failed\",\"echo\":\"" + TOKEN + "\"}";
            IOException e = null;
            try {
                new GitHubPublisher(TOKEN, api.url(), slug -> w.bare.toString()).publish(w.run);
            } catch (IOException ex) {
                e = ex;
            }
            assertTrue(e != null && e.getMessage().contains("422") && e.getMessage().contains("Validation Failed"), String.valueOf(e));
            assertFalse(e.getMessage().contains(TOKEN), e.getMessage());
        }
    }

    @Test
    public void gitFailuresAreScrubbedOfTheTokenToo() throws Exception {
        World w = world();
        IOException e = null;
        try (Api api = new Api()) {
            new GitHubPublisher(TOKEN, api.url(), slug -> "/definitely/not/a/repo/" + TOKEN).publish(w.run);
        } catch (IOException ex) {
            e = ex;
        }
        assertTrue(e != null && e.getMessage().contains("git push failed"), String.valueOf(e));
        assertFalse(e.getMessage().contains(TOKEN), e.getMessage());
    }

    @Test
    public void githubPublisherNeedsAGitCheckoutAndAKnownRepositoryName() throws Exception {
        World w = world();
        RepairRun noSlug = w.run;
        noSlug.setSource(w.checkout, "", "main", "");
        List<String> problems = new ArrayList<>();
        try (Api api = new Api()) {
            try {
                new GitHubPublisher(TOKEN, api.url(), s -> s).publish(noSlug);
            } catch (IOException e) {
                problems.add(e.getMessage());
            }
            noSlug.setSource(Fixtures.tempDir("not-git-"), "acme/widgets", "main", "");
            try {
                new GitHubPublisher(TOKEN, api.url(), s -> s).publish(noSlug);
            } catch (IOException e) {
                problems.add(e.getMessage());
            }
            assertEquals(0, api.requests.size());
        }
        assertEquals(2, problems.size());
        assertTrue(problems.get(0).contains("owner/name") && problems.get(1).contains("not a git repository"), problems.toString());
    }

    @Test
    public void tokensTravelInTheEnvironmentNotTheCommandLine() throws Exception {
        // `push` to an https remote adds the Authorization header through GIT_CONFIG_* variables
        String src = Files.readString(Path.of("src/main/java/dev/rakshit/repair/pr/GitHubPublisher.java"));
        assertTrue(src.contains("GIT_CONFIG_VALUE_0") && !src.contains("\"-c\", \"http.extraheader"), "no token on the command line");
    }
}
