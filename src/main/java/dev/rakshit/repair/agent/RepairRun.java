package dev.rakshit.repair.agent;

import dev.rakshit.repair.synth.Usage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything known about one repair attempt, from "tests failed" to "here is the verified diff". The pipeline writes it
 * as it goes; HTTP threads read it through {@link #toJson()}, so the dashboard shows a run live.
 */
public final class RepairRun {
    private final String id = UUID.randomUUID().toString().substring(0, 8);
    private final String label;
    private final long createdAt = System.currentTimeMillis();
    private final StateMachine<RepairState, RepairEvent> machine = StateMachine.repairAgent();

    private long finishedAt;
    private String outcome = "";
    private String sandbox = "", synthesizer = "";
    private final List<String> failingTests = new ArrayList<>();
    private int baselinePassing;
    private final Map<String, Object> graphStats = new LinkedHashMap<>();
    private final List<Map<String, Object>> suspects = new ArrayList<>();
    private final List<Map<String, Object>> attempts = new ArrayList<>();
    private final List<String> acceptedPartials = new ArrayList<>();
    private Map<String, Object> graphView = Map.of();
    private Map<String, Object> blast = Map.of();
    private String diff = "", rationale = "", prMarkdown = "", prUrl = "";
    private Usage usage = Usage.NONE;
    private double costUsd;
    private int round, sandboxRuns;
    private boolean approved;
    private java.nio.file.Path sourceRepo;
    private String repoSlug = "", baseBranch = "main", headSha = "";

    public RepairRun(String label) {
        this.label = label;
    }

    public String id() {
        return id;
    }

    public StateMachine<RepairState, RepairEvent> machine() {
        return machine;
    }

    public synchronized RepairState state() {
        return machine.state();
    }

    public synchronized void fire(RepairEvent e, String reason) {
        machine.fire(e, reason);
        if (machine.state().terminal()) {
            finishedAt = System.currentTimeMillis();
            outcome = reason;
        }
    }

    public synchronized void mutate(java.util.function.Consumer<RepairRun> change) {
        change.accept(this);
    }

    // setters used by the pipeline (always called inside mutate or from a synchronized method)
    public synchronized void setEnvironment(String sandbox, String synthesizer) {
        this.sandbox = sandbox;
        this.synthesizer = synthesizer;
    }

    public synchronized void setBaseline(List<String> failing, int passing) {
        failingTests.clear();
        failingTests.addAll(failing);
        baselinePassing = passing;
    }

    public synchronized void setGraphStats(Map<String, Object> s) {
        graphStats.clear();
        graphStats.putAll(s);
    }

    public synchronized void setSuspects(List<Map<String, Object>> s) {
        suspects.clear();
        suspects.addAll(s);
    }

    public synchronized void addAttempt(Map<String, Object> a) {
        attempts.add(a);
    }

    public synchronized void addPartial(String description) {
        acceptedPartials.add(description);
    }

    public synchronized void setViews(Map<String, Object> graphView, Map<String, Object> blast) {
        this.graphView = graphView;
        this.blast = blast;
    }

    public synchronized void setResult(String diff, String rationale) {
        this.diff = diff;
        this.rationale = rationale;
    }

    public synchronized void setPr(String markdown, String url) {
        this.prMarkdown = markdown;
        this.prUrl = url;
    }

    public synchronized void addUsage(Usage u, double cost) {
        usage = usage.plus(u);
        costUsd += cost;
    }

    public synchronized void setRound(int r) {
        round = r;
    }

    public synchronized void addSandboxRuns(int n) {
        sandboxRuns += n;
    }

    public synchronized void setSource(java.nio.file.Path repo, String slug, String branch, String sha) {
        this.sourceRepo = repo;
        this.repoSlug = slug == null ? "" : slug;
        this.baseBranch = branch == null || branch.isBlank() ? "main" : branch;
        this.headSha = sha == null ? "" : sha;
    }

    public synchronized java.nio.file.Path sourceRepo() {
        return sourceRepo;
    }

    public synchronized String repoSlug() {
        return repoSlug;
    }

    public synchronized String baseBranch() {
        return baseBranch;
    }

    public synchronized String headSha() {
        return headSha;
    }

    public synchronized void setApproved(boolean a) {
        approved = a;
    }

    // getters
    public synchronized String label() {
        return label;
    }

    public synchronized String diff() {
        return diff;
    }

    public synchronized String rationale() {
        return rationale;
    }

    public synchronized String prMarkdown() {
        return prMarkdown;
    }

    public synchronized String prUrl() {
        return prUrl;
    }

    public synchronized List<String> failingTests() {
        return List.copyOf(failingTests);
    }

    public synchronized int baselinePassing() {
        return baselinePassing;
    }

    public synchronized List<Map<String, Object>> suspects() {
        return List.copyOf(suspects);
    }

    public synchronized List<Map<String, Object>> attempts() {
        return List.copyOf(attempts);
    }

    public synchronized List<String> acceptedPartials() {
        return List.copyOf(acceptedPartials);
    }

    public synchronized Map<String, Object> blast() {
        return blast;
    }

    public synchronized String sandbox() {
        return sandbox;
    }

    public synchronized Usage usage() {
        return usage;
    }

    public synchronized double costUsd() {
        return costUsd;
    }

    public synchronized int sandboxRuns() {
        return sandboxRuns;
    }

    public synchronized int round() {
        return round;
    }

    public synchronized boolean approved() {
        return approved;
    }

    public synchronized boolean finished() {
        return machine.state().terminal();
    }

    public synchronized long latencyMs() {
        return (finishedAt == 0 ? System.currentTimeMillis() : finishedAt) - createdAt;
    }

    public synchronized boolean resolved() {
        return machine.state() == RepairState.RESOLVED;
    }

    public synchronized String outcome() {
        return outcome;
    }

    public synchronized long createdAt() {
        return createdAt;
    }

    /** Milliseconds spent in each state so far (the live state counts up to now). */
    public synchronized Map<String, Long> phaseMs() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (var t : machine.history()) m.merge(t.from().name(), t.msInFrom(), Long::sum);
        return m;
    }

    public synchronized Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("state", machine.state().name());
        m.put("finished", machine.state().terminal());
        m.put("resolved", resolved());
        m.put("outcome", outcome);
        m.put("createdAt", createdAt);
        m.put("latencyMs", latencyMs());
        m.put("round", round);
        m.put("sandbox", sandbox);
        m.put("synthesizer", synthesizer);
        m.put("failingTests", failingTests);
        m.put("baselinePassing", baselinePassing);
        m.put("graph", graphStats);
        m.put("suspects", suspects);
        m.put("attempts", attempts);
        m.put("acceptedPartials", acceptedPartials);
        m.put("graphView", graphView);
        m.put("blast", blast);
        m.put("diff", diff);
        m.put("rationale", rationale);
        m.put("pr", prMarkdown);
        m.put("prUrl", prUrl);
        m.put("approved", approved);
        m.put("repo", repoSlug);
        m.put("sha", headSha);
        Map<String, Object> tel = new LinkedHashMap<>();
        tel.put("calls", usage.calls());
        tel.put("inputTokens", usage.inputTokens());
        tel.put("outputTokens", usage.outputTokens());
        tel.put("costUsd", costUsd);
        tel.put("sandboxRuns", sandboxRuns);
        tel.put("candidatesVerified", attempts.size());
        tel.put("phaseMs", phaseMs());
        m.put("telemetry", tel);
        List<Map<String, Object>> events = new ArrayList<>();
        for (var t : machine.history()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("from", t.from().name());
            e.put("event", t.event().name());
            e.put("to", t.to().name());
            e.put("reason", t.reason());
            e.put("at", t.atMs());
            e.put("msInFrom", t.msInFrom());
            events.add(e);
        }
        m.put("events", events);
        List<Map<String, Object>> def = new ArrayList<>();
        for (var t : machine.definition()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("from", t.from().name());
            e.put("event", t.event().name());
            e.put("to", t.to().name());
            def.add(e);
        }
        m.put("machine", def);
        return m;
    }
}
