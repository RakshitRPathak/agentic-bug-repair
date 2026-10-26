package dev.rakshit.repair.agent;

import dev.rakshit.repair.graph.CodeEdge;
import dev.rakshit.repair.graph.CodeGraph;
import dev.rakshit.repair.graph.CodeNode;
import dev.rakshit.repair.graph.EdgeKind;
import dev.rakshit.repair.graph.JavaIndexer;
import dev.rakshit.repair.localize.FaultLocalizer;
import dev.rakshit.repair.localize.Suspect;
import dev.rakshit.repair.patch.UnifiedDiff;
import dev.rakshit.repair.pr.PrBody;
import dev.rakshit.repair.retrieval.HybridRetriever;
import dev.rakshit.repair.sandbox.Sandbox;
import dev.rakshit.repair.synth.Candidate;
import dev.rakshit.repair.synth.PatchSynthesizer;
import dev.rakshit.repair.synth.SynthRequest;
import dev.rakshit.repair.trace.FailureReport;
import dev.rakshit.repair.trace.TestRun;
import dev.rakshit.repair.util.Fs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The repair agent. It drives {@link StateMachine#repairAgent()}:
 *
 * <pre>
 * INGEST    copy the repo, index the code graph, run the tests once (the baseline)
 * LOCALIZE  rank suspect methods
 * SYNTHESIZE-VERIFY-CRITIC loop, at most 1 + maxIterations rounds:
 *     propose candidates, reject any that break PatchPolicy, verify the rest in sandboxes (a wave at a time, in rank
 *     order), stop at the first wave with a full fix; otherwise accept the best partial fix as the new starting point,
 *     re-index, re-localize, and send the critic's report back to the synthesizer
 * PUBLISH   compose one diff against the pristine checkout, verify THAT diff once more, render the pull request
 * </pre>
 *
 * Nothing the synthesizer or the code under test does can touch the caller's repository: all work happens in copies.
 */
public final class RepairPipeline {
    private static final int SUSPECTS_KEPT = 10;

    private final RepairConfig cfg;
    private final Sandbox sandbox;
    private final PatchSynthesizer synthesizer;

    public RepairPipeline(RepairConfig cfg, Sandbox sandbox, PatchSynthesizer synthesizer) {
        this.cfg = cfg;
        this.sandbox = sandbox;
        this.synthesizer = synthesizer;
    }

    public RepairRun run(Path repo, String label) {
        RepairRun run = new RepairRun(label);
        execute(run, repo);
        return run;
    }

    public void execute(RepairRun run, Path repo) {
        Path pristine = null, work = null;
        ExecutorService pool = Executors.newFixedThreadPool(cfg.parallelism());
        try {
            pristine = Files.createTempDirectory("repair-pristine-");
            work = Files.createTempDirectory("repair-work-");
            Fs.copyTree(repo, pristine);
            Fs.copyTree(repo, work);
            run.setEnvironment(sandbox.describe(), synthesizer.name());
            Verifier verifier = new Verifier(sandbox, cfg.testCommand(), cfg.limits());
            doExecute(run, pristine, work, verifier, pool);
        } catch (Exception e) {
            if (!run.finished()) run.fire(RepairEvent.ERROR, "unexpected failure: " + e);
        } finally {
            pool.shutdownNow();
            Fs.deleteTree(pristine);
            Fs.deleteTree(work);
        }
    }

    private void doExecute(RepairRun run, Path pristine, Path work, Verifier baselineVerifier, ExecutorService pool) throws Exception {
        // ---------------------------------------------------------------------------------------- INGEST
        CodeGraph graph = JavaIndexer.index(pristine);
        run.setGraphStats(graphStats(graph));
        TestRun baseline = baselineVerifier.baseline(pristine);
        run.addSandboxRuns(1);
        // a candidate that loops forever should cost seconds, not minutes: allow a multiple of the healthy run time
        long adaptive = baseline.timedOut() ? cfg.limits().timeoutMs()
                : Math.max(8_000, Math.min(cfg.limits().timeoutMs(), 6 * baselineVerifier.baselineMs()));
        Verifier verifier = baselineVerifier.withLimits(cfg.limits().withTimeout(adaptive));
        if (!baseline.compiled()) {
            run.fire(RepairEvent.NOTHING_TO_FIX, "the repository does not compile: " + String.join("; ", baseline.compileErrors().subList(0, Math.min(2, baseline.compileErrors().size()))));
            return;
        }
        if (baseline.failed().isEmpty()) {
            run.fire(RepairEvent.NOTHING_TO_FIX, baseline.timedOut() ? "the test run timed out before reporting failures" : "all tests pass; nothing to fix");
            return;
        }
        List<String> failing = new ArrayList<>();
        for (FailureReport f : baseline.failed()) failing.add(f.summary());
        run.setBaseline(failing, baseline.passed().size());
        run.fire(RepairEvent.READY, baseline.failed().size() + " failing, " + baseline.passed().size() + " passing; graph has "
                + graph.nodeCount() + " nodes and " + graph.edgeCount() + " edges");

        // -------------------------------------------------------------------------------------- LOCALIZE
        TestRun current = baseline;
        HybridRetriever retriever = new HybridRetriever(graph);
        List<Suspect> suspects = FaultLocalizer.localize(graph, current, retriever, cfg.weights());
        run.setSuspects(suspectsJson(graph, suspects));
        if (suspects.isEmpty()) {
            run.fire(RepairEvent.NO_SUSPECTS, "no method could be linked to the failures");
            return;
        }
        run.setViews(graphView(graph, suspects.get(0).id()), blastJson(graph, List.of(suspects.get(0).id())));
        run.fire(RepairEvent.SUSPECTS_FOUND, "top suspect " + graph.node(suspects.get(0).id()).label() + " (score "
                + String.format("%.2f", suspects.get(0).score()) + ")");

        // ------------------------------------------------------------------------ SYNTHESIZE / VERIFY / CRITIC
        Set<String> tried = new HashSet<>();
        Set<String> touched = new LinkedHashSet<>();
        List<String> changedMethods = new ArrayList<>();
        String feedback = "";
        int totalRounds = 1 + cfg.maxIterations();
        for (int round = 1; round <= totalRounds; round++) {
            run.setRound(round);
            List<String> notes = new ArrayList<>();
            List<Candidate> candidates = new ArrayList<>();
            PatchSynthesizer.Proposal proposal = synthesizer.propose(new SynthRequest(work, graph, suspects,
                    current.failed(), feedback, tried, cfg.beam(), round));
            run.addUsage(proposal.usage(), cfg.cost().cost(proposal.usage()));
            notes.addAll(proposal.notes());
            for (Candidate c : proposal.candidates()) {
                try {
                    PatchPolicy.check(c.diff(), graph, cfg.maxPatchLines());
                    candidates.add(c);
                } catch (PatchPolicy.Violation v) {
                    tried.add(c.key());
                    notes.add("rejected '" + c.description() + "': " + v.getMessage());
                }
            }
            if (candidates.isEmpty()) {
                run.fire(RepairEvent.NO_CANDIDATES, "round " + round + ": no usable candidates" + (notes.isEmpty() ? "" : " (" + notes.get(0) + ")"));
            } else {
                run.fire(RepairEvent.CANDIDATES_READY, "round " + round + ": " + candidates.size() + " candidate(s) from " + synthesizer.name());
            }

            List<Verdict> verdicts = new ArrayList<>();
            int winner = -1;
            if (!candidates.isEmpty()) {
                for (int from = 0; from < candidates.size() && winner < 0; from += cfg.parallelism()) {
                    int to = Math.min(candidates.size(), from + cfg.parallelism());
                    List<Future<Verdict>> wave = new ArrayList<>();
                    for (int i = from; i < to; i++) {
                        Candidate c = candidates.get(i);
                        tried.add(c.key());
                        wave.add(pool.submit(() -> verifier.verify(work, c.diff(), baseline)));
                    }
                    for (int i = from; i < to; i++) {
                        Verdict v;
                        try {
                            v = wave.get(i - from).get();
                        } catch (ExecutionException e) {
                            throw e.getCause() instanceof Exception ex ? ex : e;
                        }
                        run.addSandboxRuns(v.applied() ? 1 : 0);
                        verdicts.add(v);
                        run.addAttempt(attemptJson(round, i + 1, candidates.get(i), v));
                        if (winner < 0 && v.passes()) winner = i;
                    }
                }
            }
            if (winner >= 0) {
                run.fire(RepairEvent.ALL_TESTS_PASS, "round " + round + ": '" + candidates.get(winner).description() + "' passes every test");
                Candidate won = candidates.get(winner);
                touched.addAll(touchedFiles(won.diff()));
                changedMethods.add(won.methodId());
                publish(run, graph, pristine, work, verifier, baseline, won, touched, changedMethods);
                return;
            }
            if (!candidates.isEmpty()) {
                run.fire(RepairEvent.STILL_FAILING, "round " + round + ": none of " + candidates.size() + " candidate(s) passes");
            }

            // CRITIC
            StringBuilder fb = new StringBuilder();
            for (String n : notes) fb.append(n).append('\n');
            int best = -1;
            for (int i = 0; i < verdicts.size(); i++) {
                if (verdicts.get(i).improves() && (best < 0 || verdicts.get(i).fixed().size() > verdicts.get(best).fixed().size())) best = i;
            }
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < verdicts.size(); i++) order.add(i);
            order.sort((a, b) -> Integer.compare(score(verdicts.get(b)), score(verdicts.get(a))));
            for (int i = 0; i < Math.min(3, order.size()); i++) {
                fb.append(Critic.describe(candidates.get(order.get(i)).description(), verdicts.get(order.get(i)))).append('\n');
            }
            feedback = fb.toString();
            if (round == totalRounds) {
                run.fire(RepairEvent.OUT_OF_BUDGET, "no verified fix after " + round + " round(s) and " + run.attempts().size() + " candidate(s)");
                return;
            }
            String why = "round " + round + " report sent back to the synthesizer";
            if (best >= 0) {
                Candidate keep = candidates.get(best);
                UnifiedDiff.applyTo(work, keep.diff());
                touched.addAll(touchedFiles(keep.diff()));
                changedMethods.add(keep.methodId());
                current = verdicts.get(best).run();
                run.addPartial(keep.description() + " (" + keep.file() + ":" + keep.line() + ") fixed " + verdicts.get(best).fixed().size() + " test(s)");
                graph = JavaIndexer.index(work);
                retriever = new HybridRetriever(graph);
                suspects = FaultLocalizer.localize(graph, current, retriever, cfg.weights());
                if (suspects.isEmpty()) {
                    run.fire(RepairEvent.OUT_OF_BUDGET, "after a partial fix no suspect remains");
                    return;
                }
                why += "; kept partial fix '" + keep.description() + "' and re-localized";
            }
            run.fire(RepairEvent.RETRY, why);
        }
    }

    private static int score(Verdict v) {
        if (!v.applied() || v.run() == null || !v.run().compiled()) return -1000;
        return v.fixed().size() * 10 - v.broken().size() * 20 - v.run().failed().size();
    }

    // ------------------------------------------------------------------------------------------------ PUBLISH

    private void publish(RepairRun run, CodeGraph graph, Path pristine, Path work, Verifier verifier, TestRun baseline,
                         Candidate won, Set<String> touched, List<String> changedMethods) throws Exception {
        Path copy = Files.createTempDirectory("repair-final-");
        try {
            Fs.copyTree(work, copy);
            UnifiedDiff.applyTo(copy, won.diff());
            StringBuilder finalDiff = new StringBuilder();
            for (String path : touched) {
                String before = Files.readString(pristine.resolve(path), StandardCharsets.UTF_8);
                String after = Files.readString(copy.resolve(path), StandardCharsets.UTF_8);
                finalDiff.append(UnifiedDiff.create(path, before, after));
            }
            String diff = finalDiff.toString();
            Verdict check = verifier.verify(pristine, diff, baseline);
            run.addSandboxRuns(1);
            if (!check.passes()) {
                run.fire(RepairEvent.PUBLISH_FAILED, "the composed diff failed re-verification on a clean checkout: " + Critic.describe("final diff", check).replace('\n', ' '));
                return;
            }
            run.setResult(diff, run.acceptedPartials().isEmpty() ? won.description() : won.description() + " (after " + String.join("; ", run.acceptedPartials()) + ")");
            run.setViews(graphViewOf(graph, changedMethods), blastJson(graph, changedMethods));
            run.setPr(PrBody.render(run), "");
            run.fire(RepairEvent.PUBLISHED, "diff re-verified on a clean checkout; pull request prepared");
        } finally {
            Fs.deleteTree(copy);
        }
    }

    private static Set<String> touchedFiles(String diff) {
        Set<String> s = new LinkedHashSet<>();
        try {
            for (UnifiedDiff.FileDiff fd : UnifiedDiff.parse(diff)) s.add(fd.path());
        } catch (UnifiedDiff.PatchException e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    // ------------------------------------------------------------------------------------------------- JSON views

    private static Map<String, Object> graphStats(CodeGraph g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodes", g.nodeCount());
        m.put("edges", g.edgeCount());
        for (EdgeKind k : EdgeKind.values()) m.put(k.name().toLowerCase(), g.edgeCount(k));
        m.put("methods", g.methods().size());
        return m;
    }

    static List<Map<String, Object>> suspectsJson(CodeGraph g, List<Suspect> suspects) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Suspect s : suspects.subList(0, Math.min(SUSPECTS_KEPT, suspects.size()))) {
            CodeNode n = g.node(s.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", s.rank());
            m.put("id", s.id());
            m.put("label", n.label());
            m.put("file", n.file());
            m.put("startLine", n.startLine());
            m.put("endLine", n.endLine());
            m.put("score", s.score());
            m.put("signals", s.signals());
            out.add(m);
        }
        return out;
    }

    static Map<String, Object> attemptJson(int round, int rank, Candidate c, Verdict v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("round", round);
        m.put("rank", rank);
        m.put("operator", c.operator());
        m.put("description", c.description());
        m.put("file", c.file());
        m.put("line", c.line());
        m.put("applied", v.applied());
        m.put("compiled", v.compiled());
        m.put("fixed", v.fixed().size());
        m.put("broken", v.broken().size());
        m.put("stillFailing", v.run() == null ? 0 : v.run().failed().size());
        m.put("timedOut", v.run() != null && v.run().timedOut());
        m.put("lintDelta", v.lintDelta());
        m.put("passes", v.passes());
        m.put("ms", v.durationMs());
        m.put("critique", Critic.describe(c.description(), v));
        return m;
    }

    /** The neighbourhood of a method for the dashboard: 2 hops either way, plus callers out to 3 hops (the blast radius). */
    static Map<String, Object> graphView(CodeGraph g, String suspectId) {
        return graphViewOf(g, List.of(suspectId));
    }

    static Map<String, Object> graphViewOf(CodeGraph g, List<String> centres) {
        Map<String, String> role = new LinkedHashMap<>();
        Map<String, Integer> hops = new LinkedHashMap<>();
        for (String c : centres) {
            if (!g.contains(c)) continue;
            role.put(c, "suspect");
            hops.put(c, 0);
        }
        for (String c : centres) {
            if (!g.contains(c)) continue;
            for (var e : g.traverse(c, 2, Set.of(EdgeKind.CALLS, EdgeKind.MODIFIES), CodeGraph.Direction.OUT).entrySet()) {
                if (role.putIfAbsent(e.getKey(), g.node(e.getKey()).kind().name().equals("FIELD") ? "field" : "downstream") == null) hops.put(e.getKey(), e.getValue());
            }
            for (var e : g.traverse(c, 3, Set.of(EdgeKind.CALLS), CodeGraph.Direction.IN).entrySet()) {
                if (role.putIfAbsent(e.getKey(), g.node(e.getKey()).isTest() ? "test" : "upstream") == null) hops.put(e.getKey(), e.getValue());
            }
        }
        List<String> ids = new ArrayList<>(role.keySet());
        if (ids.size() > 60) ids = ids.subList(0, 60);
        Set<String> keep = new HashSet<>(ids);
        List<Map<String, Object>> nodes = new ArrayList<>(), edges = new ArrayList<>();
        for (String id : ids) {
            CodeNode n = g.node(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("label", n.label());
            m.put("role", role.get(id));
            m.put("hops", hops.get(id));
            m.put("file", n.file());
            nodes.add(m);
        }
        for (String id : ids) {
            for (CodeEdge e : g.edgesFrom(id)) {
                if ((e.kind() == EdgeKind.CALLS || e.kind() == EdgeKind.MODIFIES) && keep.contains(e.to())) {
                    edges.add(Map.of("from", e.from(), "to", e.to(), "kind", e.kind().name()));
                }
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("nodes", nodes);
        view.put("edges", edges);
        return view;
    }

    static Map<String, Object> blastJson(CodeGraph g, List<String> methods) {
        Set<String> up = new LinkedHashSet<>(), down = new LinkedHashSet<>(), tests = new LinkedHashSet<>();
        for (String id : methods) {
            if (!g.contains(id)) continue;
            CodeGraph.BlastRadius b = g.blastRadius(id, 4);
            for (String u : b.upstream().keySet()) if (!g.node(u).isTest()) up.add(g.node(u).label());
            for (String d : b.downstream().keySet()) down.add(g.node(d).label());
            for (String t : b.tests()) tests.add(g.node(t).label());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("methods", methods);
        m.put("upstream", new ArrayList<>(up));
        m.put("downstream", new ArrayList<>(down));
        m.put("tests", new ArrayList<>(tests));
        return m;
    }
}
