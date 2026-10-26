package dev.rakshit.repair;

import dev.rakshit.repair.agent.RepairConfig;
import dev.rakshit.repair.agent.RepairPipeline;
import dev.rakshit.repair.agent.RepairRun;
import dev.rakshit.repair.bench.Bench;
import dev.rakshit.repair.pr.DryRunPublisher;
import dev.rakshit.repair.pr.GitHubPublisher;
import dev.rakshit.repair.pr.PrBody;
import dev.rakshit.repair.pr.PullRequestPublisher;
import dev.rakshit.repair.sandbox.Sandbox;
import dev.rakshit.repair.sandbox.Sandboxes;
import dev.rakshit.repair.service.HttpApi;
import dev.rakshit.repair.service.RepairService;
import dev.rakshit.repair.service.ServiceConfig;
import dev.rakshit.repair.synth.AnthropicClient;
import dev.rakshit.repair.synth.CascadeSynthesizer;
import dev.rakshit.repair.synth.LlmSynthesizer;
import dev.rakshit.repair.synth.MutationSynthesizer;
import dev.rakshit.repair.synth.PatchSynthesizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Command line: {@code repair}, {@code serve}, {@code demo}, {@code bench}. Run with no arguments for usage. */
public final class Main {
    private Main() {}

    static final String USAGE = """
            agentic-bug-repair

              repair <repo> [--cmd "bash build.sh"] [--iterations 3] [--beam 8] [--out repair-out]
                  Repair one repository whose tests fail. Prints each state transition and the verified diff, and writes
                  PR.md and fix.patch under --out.
              serve [--port 8080] [--host 127.0.0.1] [--data ./data] [--root DIR]... [--repo owner/name]...
                  Webhook server, job queue and dashboard. --root: directories POST /api/runs may read.
                  --repo: repositories the GitHub webhook may trigger repairs for.
              demo [--bench bench] [--port 8080] [--cases 01,02,03]
                  Start the dashboard and repair a few bundled buggy projects so there is something to look at.
              bench [--dir bench] [--mode localize|repair|both|tune] [--cases 01,02]
                  Run the benchmark and print the results tables.

            Environment: ANTHROPIC_API_KEY (use a model for hard bugs; REPAIR_MODEL picks it), GITHUB_TOKEN (open real draft
            pull requests), WEBHOOK_SECRET (enable POST /webhooks/github), REPAIR_API_KEY (required to bind a non-loopback host).
            """;

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            System.out.print(USAGE);
            return;
        }
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "repair" -> repair(rest);
            case "serve" -> serve(rest, false);
            case "demo" -> serve(rest, true);
            case "bench" -> Bench.main(rest);
            default -> {
                System.err.print(USAGE);
                System.exit(2);
            }
        }
    }

    static Supplier<PatchSynthesizer> synthesizerFromEnv() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) return MutationSynthesizer::new;
        String model = System.getenv().getOrDefault("REPAIR_MODEL", "claude-sonnet-5-5");
        return () -> new CascadeSynthesizer(new MutationSynthesizer(),
                new LlmSynthesizer(new AnthropicClient(key, model, AnthropicClient.DEFAULT_URL), 2), 2);
    }

    private static void repair(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.print(USAGE);
            System.exit(2);
        }
        Path repo = Path.of(args[0]);
        Path out = Path.of("repair-out");
        RepairConfig cfg = RepairConfig.defaults();
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--cmd" -> cfg = cfg.withTestCommand(List.of(args[++i].split("\\s+")));
                case "--iterations" -> cfg = cfg.withMaxIterations(Integer.parseInt(args[++i]));
                case "--beam" -> cfg = cfg.withBeam(Integer.parseInt(args[++i]));
                case "--out" -> out = Path.of(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        Sandbox sandbox = Sandboxes.auto();
        PatchSynthesizer synth = synthesizerFromEnv().get();
        System.out.println("sandbox:     " + sandbox.describe());
        System.out.println("synthesizer: " + synth.name());
        RepairRun run = new RepairPipeline(cfg, sandbox, synth).run(repo, repo.toAbsolutePath().getFileName().toString());
        for (var t : run.machine().history()) {
            System.out.printf("  %-10s --%s--> %-10s (%d ms)  %s%n", t.from(), t.event(), t.to(), t.msInFrom(), t.reason());
        }
        System.out.println();
        if (run.resolved()) {
            System.out.println(run.diff());
            PullRequestPublisher.Result r = new DryRunPublisher(out).publish(run);
            System.out.println("Title: " + PrBody.title(run));
            System.out.println("Wrote " + r.url() + " and fix.patch");
        } else {
            System.out.println("Not resolved: " + run.outcome());
            System.exit(1);
        }
    }

    private static void serve(String[] args, boolean demo) throws Exception {
        int port = 8080;
        String host = "127.0.0.1";
        Path data = Path.of("data");
        Path bench = Path.of("bench");
        List<Path> roots = new ArrayList<>();
        Set<String> repos = new HashSet<>();
        List<String> demoCases = List.of("01", "10", "17");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--data" -> data = Path.of(args[++i]);
                case "--root" -> roots.add(Path.of(args[++i]));
                case "--repo" -> repos.add(args[++i]);
                case "--bench" -> bench = Path.of(args[++i]);
                case "--cases" -> demoCases = List.of(args[++i].split(","));
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (roots.isEmpty()) roots.add(Path.of("").toAbsolutePath());
        // the demo repairs bundled toy projects: it never touches GitHub, whatever the environment holds
        String token = demo ? "" : System.getenv("GITHUB_TOKEN");
        PullRequestPublisher publisher = token == null || token.isBlank()
                ? new DryRunPublisher(data.resolve("pr"))
                : new GitHubPublisher(token, "https://api.github.com", GitHubPublisher::githubRemote);
        ServiceConfig cfg = new ServiceConfig(data, System.getenv().getOrDefault("WEBHOOK_SECRET", ""), repos, roots,
                System.getenv().getOrDefault("REPAIR_API_KEY", ""), 2, 20, RepairConfig.defaults(), synthesizerFromEnv(),
                Sandboxes.auto(), publisher, slug -> "https://github.com/" + slug + ".git");
        RepairService service = new RepairService(cfg);
        HttpApi api = new HttpApi(service, host, port);
        api.start();
        System.out.println("dashboard:  http://" + host + ":" + api.port() + "/");
        System.out.println("sandbox:    " + cfg.sandbox().describe());
        System.out.println("pull requests: " + (token == null || token.isBlank() ? (demo ? "dry run (the demo never contacts GitHub)" : "dry run (set GITHUB_TOKEN to open real ones)") : "real draft PRs on GitHub"));
        System.out.println("webhook:    " + (cfg.webhookSecret().isEmpty() ? "disabled (set WEBHOOK_SECRET)" : "POST /webhooks/github for " + repos));
        if (demo) {
            Path work = Files.createTempDirectory("repair-demo-");
            roots.add(work);
            for (Bench.Case c : Bench.load(bench)) {
                if (demoCases.stream().noneMatch(p -> c.id().startsWith(p))) continue;
                Path dir = Bench.materializeInto(c, work.resolve(c.id()));
                service.submitLocal(dir, c.id() + " - " + c.title(), "");
                System.out.println("queued demo repair: " + c.id());
            }
        }
        System.out.println("Ctrl-C to stop.");
        Thread.currentThread().join();
    }
}
