package dev.rakshit.repair.bench;

import dev.rakshit.repair.agent.RepairConfig;
import dev.rakshit.repair.agent.RepairPipeline;
import dev.rakshit.repair.agent.RepairRun;
import dev.rakshit.repair.agent.Verifier;
import dev.rakshit.repair.graph.CodeGraph;
import dev.rakshit.repair.graph.JavaIndexer;
import dev.rakshit.repair.localize.FaultLocalizer;
import dev.rakshit.repair.localize.FaultLocalizer.Weights;
import dev.rakshit.repair.localize.Suspect;
import dev.rakshit.repair.metrics.Percentile;
import dev.rakshit.repair.patch.UnifiedDiff;
import dev.rakshit.repair.retrieval.HybridRetriever;
import dev.rakshit.repair.sandbox.Limits;
import dev.rakshit.repair.sandbox.ProcessSandbox;
import dev.rakshit.repair.sandbox.Sandbox;
import dev.rakshit.repair.synth.CascadeSynthesizer;
import dev.rakshit.repair.synth.MutationSynthesizer;
import dev.rakshit.repair.synth.SynthesizerConfig;
import dev.rakshit.repair.synth.PatchSynthesizer;
import dev.rakshit.repair.trace.TestRun;
import dev.rakshit.repair.util.Fs;
import dev.rakshit.repair.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A small SWE-bench-style benchmark. Each case is a real project with a passing test suite plus a hand-written bug
 * (a patch that breaks it). It measures two things separately:
 *
 * <ul>
 *   <li><b>Localization</b>: where the true buggy method ranks among the suspects, for several signal combinations, so the
 *       value of the call graph is measured rather than claimed.</li>
 *   <li><b>Repair</b>: whether the full agent finds a diff that passes every test on a clean checkout, how many candidates
 *       it verified, and whether the repaired source is identical to the original or merely passes the tests.</li>
 * </ul>
 */
public final class Bench {
    public record Case(String id, String target, String title, String kind, String split, List<String> truth, boolean hang,
                       Path dir, Path targetDir) {}

    public record LocResult(String caseId, String kind, String split, Map<String, Integer> rankByVariant) {}

    public record RepairResult(String caseId, String kind, String split, boolean resolved, boolean restoredOriginal, int rounds,
                               int candidates, int sandboxRuns, long latencyMs, String outcome, String fix,
                               int modelCalls, long inputTokens, long outputTokens, int modelErrors) {}

    private static final Map<String, Weights> VARIANTS = new LinkedHashMap<>();

    static {
        VARIANTS.put("trace only (baseline)", Weights.TRACE_ONLY);
        VARIANTS.put("text search only", Weights.TEXT_ONLY);
        VARIANTS.put("trace + text (no graph)", Weights.NO_GRAPH);
        VARIANTS.put("call-graph spectrum only", Weights.SPECTRUM_ONLY);
        VARIANTS.put("full (graph + trace + text)", Weights.DEFAULT);
    }

    private Bench() {}

    public static List<Case> load(Path benchDir) throws IOException {
        List<Case> cases = new ArrayList<>();
        try (Stream<Path> s = Files.list(benchDir.resolve("cases"))) {
            for (Path dir : (Iterable<Path>) s.sorted()::iterator) {
                Path meta = dir.resolve("case.json");
                if (!Files.isRegularFile(meta)) continue;
                Map<String, Object> m = Json.parseObject(Files.readString(meta, StandardCharsets.UTF_8));
                List<String> truth = new ArrayList<>();
                for (Object o : (List<?>) m.get("truth")) truth.add(String.valueOf(o));
                String target = Json.str(m, "target");
                cases.add(new Case(Json.str(m, "id"), target, Json.str(m, "title"), Json.str(m, "kind"), Json.str(m, "split"), truth,
                        Boolean.TRUE.equals(m.get("hang")), dir, benchDir.resolve("targets").resolve(target)));
            }
        }
        return cases;
    }

    /** A fresh copy of the target with the case's bug applied. */
    static Path materialize(Case c) throws Exception {
        return materializeInto(c, Files.createTempDirectory("bench-" + c.id() + "-"));
    }

    public static Path materializeInto(Case c, Path dir) throws Exception {
        Files.createDirectories(dir);
        Fs.copyTree(c.targetDir(), dir);
        UnifiedDiff.applyTo(dir, Files.readString(c.dir().resolve("bug.patch"), StandardCharsets.UTF_8));
        return dir;
    }

    // ------------------------------------------------------------------------------------------ localization

    public static LocResult localize(Case c, Sandbox sandbox, Limits limits, List<String> command) throws Exception {
        Path repo = materialize(c);
        try {
            CodeGraph graph = JavaIndexer.index(repo);
            TestRun baseline = new Verifier(sandbox, command, limits).baseline(repo);
            HybridRetriever retriever = new HybridRetriever(graph);
            Map<String, Integer> ranks = new LinkedHashMap<>();
            for (Map.Entry<String, Weights> v : VARIANTS.entrySet()) {
                List<Suspect> suspects = FaultLocalizer.localize(graph, baseline, retriever, v.getValue());
                int rank = 0;
                for (Suspect s : suspects) {
                    if (c.truth().contains(s.id())) {
                        rank = s.rank();
                        break;
                    }
                }
                ranks.put(v.getKey(), rank);          // 0 = not among the suspects at all
            }
            return new LocResult(c.id(), c.kind(), c.split(), ranks);
        } finally {
            Fs.deleteTree(repo);
        }
    }

    // -------------------------------------------------------------------------------------------------- tuning

    /** Everything needed to re-localize one case under many weightings without re-running its tests. */
    record Prepared(Case c, CodeGraph graph, TestRun baseline, HybridRetriever retriever) {}

    static int rankOf(Prepared p, Weights w) {
        for (Suspect s : FaultLocalizer.localize(p.graph(), p.baseline(), p.retriever(), w)) {
            if (p.c().truth().contains(s.id())) return s.rank();
        }
        return 0;
    }

    static double mrr(List<Prepared> ps, Weights w) {
        double sum = 0;
        for (Prepared p : ps) {
            int r = rankOf(p, w);
            if (r > 0) sum += 1.0 / r;
        }
        return ps.isEmpty() ? 0 : sum / ps.size();
    }

    /** Grid-searches signal weights on the dev split only, then reports the held-out test split with the winner. */
    static String tune(List<Case> cases, Sandbox sandbox, RepairConfig cfg) throws Exception {
        List<Prepared> dev = new ArrayList<>(), test = new ArrayList<>();
        for (Case c : cases) {
            Path repo = materialize(c);
            try {
                CodeGraph g = JavaIndexer.index(repo);
                TestRun base = new Verifier(sandbox, cfg.testCommand(), cfg.limits()).baseline(repo);
                (c.split().equals("dev") ? dev : test).add(new Prepared(c, g, base, new HybridRetriever(g)));
            } finally {
                Fs.deleteTree(repo);
            }
        }
        double[] grid = {0, 0.25, 0.5, 1.0};
        List<Object[]> scored = new ArrayList<>();
        for (double a : grid) for (double b : grid) for (double c : grid) for (double d : grid) {
            if (a + b + c + d == 0) continue;
            Weights w = new Weights(a, b, c, d);
            scored.add(new Object[] {w, mrr(dev, w)});
        }
        scored.sort((x, y) -> Double.compare((double) y[1], (double) x[1]));
        StringBuilder sb = new StringBuilder("dev split MRR by weights (spectrum, frame, proximity, text):\n");
        for (int i = 0; i < 8; i++) sb.append(String.format("  %s -> %.3f%n", scored.get(i)[0], (double) scored.get(i)[1]));
        Weights best = (Weights) scored.get(0)[0];
        sb.append(String.format("default weights: dev MRR %.3f, test MRR %.3f%n", mrr(dev, Weights.DEFAULT), mrr(test, Weights.DEFAULT)));
        sb.append(String.format("best-on-dev %s: dev MRR %.3f, held-out test MRR %.3f%n", best, mrr(dev, best), mrr(test, best)));
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------ repair

    public static RepairResult repair(Case c, Sandbox sandbox, PatchSynthesizer synth, RepairConfig cfg) throws Exception {
        Path repo = materialize(c);
        try {
            RepairRun run = new RepairPipeline(cfg, sandbox, synth).run(repo, c.id());
            boolean restored = false;
            if (run.resolved()) {
                Path fixed = Files.createTempDirectory("bench-fixed-");
                try {
                    Fs.copyTree(repo, fixed);
                    UnifiedDiff.applyTo(fixed, run.diff());
                    restored = sameSources(fixed, c.targetDir());
                } finally {
                    Fs.deleteTree(fixed);
                }
            }
            String fix = run.resolved() ? firstLine(run.rationale()) : "";
            return new RepairResult(c.id(), c.kind(), c.split(), run.resolved(), restored, run.round(), run.attempts().size(),
                    run.sandboxRuns(), run.latencyMs(), run.outcome(), fix,
                    run.usage().calls(), run.usage().inputTokens(), run.usage().outputTokens(),
                    (synth instanceof CascadeSynthesizer cs ? cs.modelErrors() : 0) + (isModelFailure(run.outcome()) ? 1 : 0));
        } finally {
            Fs.deleteTree(repo);
        }
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    /** True if every .java file under {@code a} has exactly the text of its counterpart under {@code b}. */
    static boolean sameSources(Path a, Path b) throws IOException {
        try (Stream<Path> s = Files.walk(a)) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".java"))::iterator) {
                Path other = b.resolve(a.relativize(p).toString());
                if (!Files.isRegularFile(other) || !Files.readString(p).equals(Files.readString(other))) return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------ reports

    static Map<String, Double> locMetrics(List<LocResult> rs, String variant) {
        int n = rs.size(), top1 = 0, top3 = 0, top5 = 0;
        double mrr = 0;
        for (LocResult r : rs) {
            int rank = r.rankByVariant().get(variant);
            if (rank == 1) top1++;
            if (rank >= 1 && rank <= 3) top3++;
            if (rank >= 1 && rank <= 5) top5++;
            if (rank >= 1) mrr += 1.0 / rank;
        }
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("top1", n == 0 ? 0 : (double) top1 / n);
        m.put("top3", n == 0 ? 0 : (double) top3 / n);
        m.put("top5", n == 0 ? 0 : (double) top5 / n);
        m.put("mrr", n == 0 ? 0 : mrr / n);
        return m;
    }

    public static final String MUTATION_LABEL = "offline mutation synthesizer, no model";

    static boolean isModelFailure(String outcome) {
        return outcome != null && (outcome.contains("model server") || outcome.contains("Anthropic API"));
    }

    public static String report(List<LocResult> loc, List<RepairResult> rep) {
        return report(loc, rep, MUTATION_LABEL);
    }

    /** @param synth what produced the patches, for the repair table's heading (e.g. the model and server) */
    public static String report(List<LocResult> loc, List<RepairResult> rep, String synth) {
        StringBuilder sb = new StringBuilder();
        if (!loc.isEmpty()) {
            sb.append("### Fault localization (").append(loc.size()).append(" bugs)\n\n");
            sb.append("Rank of the true buggy method among the suspects. top-k = share of bugs where it is in the first k.\n\n");
            sb.append("| Signals | top-1 | top-3 | top-5 | MRR |\n|---|---|---|---|---|\n");
            for (String v : VARIANTS.keySet()) {
                Map<String, Double> m = locMetrics(loc, v);
                sb.append(String.format("| %s | %.0f%% | %.0f%% | %.0f%% | %.2f |%n", v, 100 * m.get("top1"), 100 * m.get("top3"),
                        100 * m.get("top5"), m.get("mrr")));
            }
            for (String split : List.of("dev", "test")) {
                List<LocResult> part = loc.stream().filter(r -> r.split().equals(split)).toList();
                if (part.isEmpty()) continue;
                Map<String, Double> m = locMetrics(part, "full (graph + trace + text)");
                Map<String, Double> t = locMetrics(part, "trace only (baseline)");
                sb.append(String.format("%n%s split (%d bugs): full top-1 %.0f%%, top-3 %.0f%%; trace-only top-1 %.0f%%, top-3 %.0f%%.%n", split,
                        part.size(), 100 * m.get("top1"), 100 * m.get("top3"), 100 * t.get("top1"), 100 * t.get("top3")));
            }
            sb.append('\n');
        }
        if (!rep.isEmpty()) {
            int resolved = 0, restored = 0;
            List<Long> lat = new ArrayList<>();
            for (RepairResult r : rep) {
                if (r.resolved()) {
                    resolved++;
                    lat.add(r.latencyMs());
                    if (r.restoredOriginal()) restored++;
                }
            }
            sb.append("### Repair (").append(rep.size()).append(" bugs, ").append(synth).append(")\n\n");
            sb.append(String.format("Resolved (passes every test on a clean checkout): **%d / %d (%.0f%%)**; source identical to the original: %d. "
                            + "Latency of resolved runs: median %.0f s, p95 %.0f s.%n%n", resolved, rep.size(), 100.0 * resolved / rep.size(), restored,
                    Percentile.nearestRank(lat, 50) / 1000.0, Percentile.nearestRank(lat, 95) / 1000.0));
            for (String kind : List.of("operator", "other")) {
                List<RepairResult> part = rep.stream().filter(r -> r.kind().equals(kind)).toList();
                if (part.isEmpty()) continue;
                long ok = part.stream().filter(RepairResult::resolved).count();
                sb.append(String.format("- Bugs %s: %d / %d resolved%n", kind.equals("operator") ? "fixable by one small edit" : "needing new code or a different kind of edit", ok, part.size()));
            }
            int calls = rep.stream().mapToInt(RepairResult::modelCalls).sum();
            boolean model = calls > 0;
            long failedCases = rep.stream().filter(r -> r.modelErrors() > 0).count();
            if (failedCases > 0)
                sb.append(String.format("%n**Warning: the model failed in %d of %d bugs** (%d failed rounds in total); those rounds fell back to "
                        + "the offline synthesizer or ended the run. These are not model results.%n", failedCases, rep.size(),
                        rep.stream().mapToInt(RepairResult::modelErrors).sum()));
            if (model) {
                long in = rep.stream().mapToLong(RepairResult::inputTokens).sum(), out = rep.stream().mapToLong(RepairResult::outputTokens).sum();
                sb.append(String.format("%nModel calls: %d in total, %d input and %d output tokens (%.0f input tokens per call).%n",
                        calls, in, out, (double) in / calls));
            }
            sb.append(model ? "\n| Case | Kind | Resolved | Rounds | Candidates verified | Model calls | Time | Fix found |\n|---|---|---|---|---|---|---|---|\n"
                    : "\n| Case | Kind | Resolved | Rounds | Candidates verified | Time | Fix found |\n|---|---|---|---|---|---|---|\n");
            for (RepairResult r : rep) {
                String status = r.resolved() ? (r.restoredOriginal() ? "yes (original restored)" : "yes (differs from original)") : "no";
                String fix = r.resolved() ? r.fix().replace("|", "\\|") : "-";
                if (model)
                    sb.append(String.format("| %s | %s | %s | %d | %d | %d | %.0f s | %s |%n", r.caseId(), r.kind(), status,
                            r.rounds(), r.candidates(), r.modelCalls(), r.latencyMs() / 1000.0, fix));
                else
                    sb.append(String.format("| %s | %s | %s | %d | %d | %.0f s | %s |%n", r.caseId(), r.kind(), status,
                            r.rounds(), r.candidates(), r.latencyMs() / 1000.0, fix));
            }
        }
        return sb.toString();
    }

    public static Map<String, Object> toJson(List<LocResult> loc, List<RepairResult> rep) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> l = new ArrayList<>();
        for (LocResult r : loc) l.add(Map.of("case", r.caseId(), "kind", r.kind(), "split", r.split(), "ranks", r.rankByVariant()));
        m.put("localization", l);
        List<Object> p = new ArrayList<>();
        for (RepairResult r : rep) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("case", r.caseId());
            e.put("kind", r.kind());
            e.put("split", r.split());
            e.put("resolved", r.resolved());
            e.put("restoredOriginal", r.restoredOriginal());
            e.put("rounds", r.rounds());
            e.put("candidates", r.candidates());
            e.put("sandboxRuns", r.sandboxRuns());
            e.put("latencyMs", r.latencyMs());
            e.put("outcome", r.outcome());
            e.put("fix", r.fix());
            e.put("modelCalls", r.modelCalls());
            e.put("inputTokens", r.inputTokens());
            e.put("outputTokens", r.outputTokens());
            e.put("modelErrors", r.modelErrors());
            p.add(e);
        }
        m.put("repair", p);
        return m;
    }

    // ---------------------------------------------------------------------------------------------------- CLI

    public static void main(String[] args) throws Exception {
        Path dir = Path.of("bench");
        String mode = "both";
        SynthesizerConfig.Mode synthMode = SynthesizerConfig.Mode.MUTATION;
        List<String> only = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dir" -> dir = Path.of(args[++i]);
                case "--mode" -> mode = args[++i];
                case "--cases" -> only.addAll(List.of(args[++i].split(",")));
                case "--synth" -> synthMode = SynthesizerConfig.Mode.parse(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        List<Case> cases = load(dir);
        if (!only.isEmpty()) cases = cases.stream().filter(c -> only.stream().anyMatch(o -> c.id().startsWith(o))).toList();
        Sandbox sandbox = new ProcessSandbox();
        RepairConfig cfg = RepairConfig.defaults().withLimits(Limits.defaults().withTimeout(30_000));
        Supplier<PatchSynthesizer> synth = SynthesizerConfig.fromEnv(System.getenv(), synthMode);
        String label = synthMode == SynthesizerConfig.Mode.MUTATION ? MUTATION_LABEL
                : (synthMode == SynthesizerConfig.Mode.MODEL_ONLY ? "model only: " : "mutation first, then model: ")
                + SynthesizerConfig.describeModel(System.getenv());
        if (synthMode != SynthesizerConfig.Mode.MUTATION) {
            System.err.println("synthesizer: " + label);
            // One tiny request first: a misconfigured or broken model server should stop the run here, not produce a
            // results table in which the model never took part.
            try {
                SynthesizerConfig.modelFromEnv(System.getenv()).complete("Reply with the single word OK.", "OK?", 8, 0);
            } catch (IOException e) {
                System.err.println("the model is not answering, so no benchmark was run: " + e.getMessage());
                System.exit(2);
            }
        }
        if (mode.equals("tune")) {
            System.out.println(tune(cases, sandbox, cfg));
            return;
        }
        List<LocResult> loc = new ArrayList<>();
        List<RepairResult> rep = new ArrayList<>();
        for (Case c : cases) {
            System.err.println("== " + c.id() + " - " + c.title());
            if (mode.equals("both") || mode.equals("localize")) {
                LocResult r = localize(c, sandbox, cfg.limits(), cfg.testCommand());
                loc.add(r);
                System.err.println("   ranks: " + r.rankByVariant());
            }
            if (mode.equals("both") || mode.equals("repair")) {
                RepairResult r = repair(c, sandbox, synth.get(), cfg);
                rep.add(r);
                System.err.println("   repair: " + (r.resolved() ? "RESOLVED " : "unresolved ") + r.candidates() + " candidates, " + r.latencyMs() / 1000 + " s - " + r.outcome());
            }
        }
        String text = report(loc, rep, label);
        System.out.println(text);
        Path results = dir.resolve("results");
        Files.createDirectories(results);
        // Model runs get their own files so the offline baseline in RESULTS.md is never overwritten.
        String suffix = synthMode == SynthesizerConfig.Mode.MUTATION ? ""
                : "_" + (synthMode == SynthesizerConfig.Mode.MODEL_ONLY ? "model-only" : "model") + "_"
                + System.getenv().getOrDefault("REPAIR_MODEL", "model").replaceAll("[^A-Za-z0-9.-]+", "-");
        Map<String, Object> json = toJson(loc, rep);
        json.put("synthesizer", label);
        Files.writeString(results.resolve("RESULTS" + suffix + ".md"), text, StandardCharsets.UTF_8);
        Files.writeString(results.resolve("results" + suffix + ".json"), Json.write(json), StandardCharsets.UTF_8);
        if (rep.stream().anyMatch(r -> r.modelErrors() > 0)) System.exit(1);  // the warning is in the report too
    }
}
