package dev.rakshit.repair.pr;

import dev.rakshit.repair.agent.RepairRun;
import dev.rakshit.repair.util.Fs;
import dev.rakshit.repair.util.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Opens a draft pull request: clones the source checkout, creates the branch {@code autofix/<run id>}, applies the
 * verified diff, commits, pushes, and calls the GitHub REST API. The token reaches git through environment variables
 * (never the command line, so it is not visible in {@code ps}) and is scrubbed from any error message.
 */
public final class GitHubPublisher implements PullRequestPublisher {
    private final String token, apiBase;
    private final Function<String, String> remoteUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    /**
     * @param remoteUrl maps "owner/repo" to the URL to push to; production uses {@link #githubRemote}, tests use a local path
     */
    public GitHubPublisher(String token, String apiBase, Function<String, String> remoteUrl) {
        this.token = token;
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.remoteUrl = remoteUrl;
    }

    public static String githubRemote(String slug) {
        return "https://github.com/" + slug + ".git";
    }

    @Override
    public Result publish(RepairRun run) throws IOException {
        if (run.diff().isBlank()) throw new IOException("run " + run.id() + " has no verified diff");
        if (run.repoSlug().isBlank()) throw new IOException("run " + run.id() + " does not know its GitHub repository (owner/name)");
        if (run.sourceRepo() == null || !Files.isDirectory(run.sourceRepo().resolve(".git"))) {
            throw new IOException("the source checkout is not a git repository, so a branch cannot be created");
        }
        String branch = "autofix/" + run.id();
        Path tmp = Files.createTempDirectory("repair-pr-");
        try {
            Path repo = tmp.resolve("repo");
            Git.run(tmp, null, 120, token, "clone", "--quiet", "--no-hardlinks", run.sourceRepo().toString(), repo.toString());
            Git.run(repo, "checkout", "-q", "-b", branch);
            Path patch = tmp.resolve("fix.patch");
            Files.writeString(patch, run.diff(), StandardCharsets.UTF_8);
            Git.run(repo, "apply", "--index", patch.toString());
            Git.run(repo, null, 120, token, "-c", "user.name=agentic-bug-repair", "-c", "user.email=noreply@example.invalid",
                    "commit", "-q", "-m", PrBody.title(run), "-m", run.rationale());
            String url = remoteUrl.apply(run.repoSlug());
            Map<String, String> env = new LinkedHashMap<>();
            if (url.startsWith("https://")) {
                String basic = Base64.getEncoder().encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
                env.put("GIT_CONFIG_COUNT", "1");
                env.put("GIT_CONFIG_KEY_0", "http.extraheader");
                env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
            }
            Git.run(repo, env, 180, token, "push", "--quiet", url, "HEAD:refs/heads/" + branch);
            return new Result(createPullRequest(run, branch), "draft pull request opened from " + branch);
        } finally {
            Fs.deleteTree(tmp);
        }
    }

    private String createPullRequest(RepairRun run, String branch) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", PrBody.title(run));
        body.put("head", branch);
        body.put("base", run.baseBranch());
        body.put("body", run.prMarkdown());
        body.put("draft", true);
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/repos/" + run.repoSlug() + "/pulls"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 201) {
                throw new IOException("GitHub returned " + resp.statusCode() + ": " + resp.body().replace(token, "***"));
            }
            String url = Json.str(Json.parse(resp.body()), "html_url");
            return url == null ? "" : url;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
