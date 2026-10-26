package dev.rakshit.repair.service;

import dev.rakshit.repair.agent.RepairEvent;
import dev.rakshit.repair.agent.RepairPipeline;
import dev.rakshit.repair.agent.RepairRun;
import dev.rakshit.repair.pr.Git;
import dev.rakshit.repair.pr.PullRequestPublisher;
import dev.rakshit.repair.util.Fs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Owns the run store and the worker queue; everything the HTTP layer needs, with no HTTP in it. */
public final class RepairService implements AutoCloseable {
    public static final class RejectedException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int status;

        public RejectedException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final ServiceConfig cfg;
    private final RunStore store;
    private final JobQueue queue;
    private final Set<String> seenDeliveries = Collections.synchronizedSet(new LinkedHashSet<>());

    public RepairService(ServiceConfig cfg) {
        this.cfg = cfg;
        this.store = new RunStore(cfg.dataDir() == null ? null : cfg.dataDir().resolve("runs"), 500);
        this.queue = new JobQueue(cfg.workers(), cfg.queueCapacity());
    }

    public RunStore store() {
        return store;
    }

    public JobQueue queue() {
        return queue;
    }

    public ServiceConfig config() {
        return cfg;
    }

    /** Repairs a repository that is already on disk. {@code repo} must be inside one of the configured roots. */
    public RepairRun submitLocal(Path repo, String label, String slug) throws RejectedException {
        Path real;
        try {
            real = repo.toRealPath();
        } catch (IOException e) {
            throw new RejectedException(400, "no such directory: " + repo);
        }
        boolean allowed = false;
        for (Path root : cfg.localRoots()) {
            try {
                if (real.startsWith(root.toRealPath())) allowed = true;
            } catch (IOException ignored) {
                // a missing root allows nothing
            }
        }
        if (!allowed) throw new RejectedException(403, "that path is outside the directories this server may read");
        if (!Files.isDirectory(real)) throw new RejectedException(400, "not a directory: " + repo);
        RepairRun run = new RepairRun(label == null || label.isBlank() ? real.getFileName().toString() : label);
        run.setSource(real, slug, "main", "");
        enqueue(run, () -> real, false);
        return run;
    }

    /** Handles a verified, allowed webhook for a failing build; the clone happens on the worker, not on the HTTP thread. */
    public RepairRun submitFailure(String slug, String branch, String sha, String deliveryId) throws RejectedException {
        if (deliveryId != null && !deliveryId.isBlank()) {
            synchronized (seenDeliveries) {
                if (seenDeliveries.contains(deliveryId)) throw new RejectedException(200, "duplicate delivery " + deliveryId + " ignored");
                seenDeliveries.add(deliveryId);
                if (seenDeliveries.size() > 2000) seenDeliveries.remove(seenDeliveries.iterator().next());
            }
        }
        if (!cfg.allowedRepos().contains(slug)) throw new RejectedException(403, "repository " + slug + " is not enabled");
        RepairRun run = new RepairRun(slug + "@" + (sha == null || sha.length() < 7 ? "?" : sha.substring(0, 7)));
        enqueue(run, () -> clone(run, slug, branch, sha), true);
        return run;
    }

    private interface Source {
        Path get() throws IOException;
    }

    private void enqueue(RepairRun run, Source source, boolean ownsSource) throws RejectedException {
        store.add(run);
        try {
            queue.submit(() -> {
                try {
                    Path dir = source.get();
                    if (ownsSource) run.setSource(dir, run.repoSlug(), run.baseBranch(), run.headSha());
                    new RepairPipeline(cfg.repair(), cfg.sandbox(), cfg.synthesizer().get()).execute(run, dir);
                } catch (IOException | RuntimeException e) {
                    if (!run.finished()) run.fire(RepairEvent.ERROR, "could not start: " + e.getMessage());
                } finally {
                    store.persist(run);
                }
            });
        } catch (JobQueue.QueueFullException e) {
            run.fire(RepairEvent.ERROR, "rejected: too many repairs queued");
            store.persist(run);
            throw new RejectedException(429, "too many repairs are queued; retry later");
        }
    }

    private Path clone(RepairRun run, String slug, String branch, String sha) throws IOException {
        Path dir = (cfg.dataDir() != null ? cfg.dataDir() : Files.createTempDirectory("repair-data-")).resolve("clones").resolve(run.id());
        Files.createDirectories(dir.getParent());
        String url = cfg.cloneUrl().apply(slug);
        String[] args = branch == null || branch.isBlank()
                ? new String[] {"clone", "--quiet", url, dir.toString()}
                : new String[] {"clone", "--quiet", "--branch", branch, url, dir.toString()};
        Git.run(null, null, 300, null, args);
        if (sha != null && sha.matches("[0-9a-f]{7,40}")) Git.run(dir, "checkout", "-q", sha);
        run.setSource(dir, slug, branch, sha);
        return dir;
    }

    /** The one-click approval: opens the pull request for a resolved run. */
    public PullRequestPublisher.Result approve(String id) throws RejectedException {
        RepairRun run = store.live(id);
        if (run == null) throw new RejectedException(404, "no such run (or it is from an earlier server session)");
        if (!run.resolved()) throw new RejectedException(409, "run " + id + " has no verified fix to publish");
        synchronized (run) {
            if (run.approved()) throw new RejectedException(409, "run " + id + " was already approved");
            try {
                PullRequestPublisher.Result r = cfg.publisher().publish(run);
                run.setApproved(true);
                run.setPr(run.prMarkdown(), r.url());
                store.persist(run);
                return r;
            } catch (IOException e) {
                throw new RejectedException(502, "could not publish: " + e.getMessage());
            }
        }
    }

    public Map<String, Object> metrics() {
        Map<String, Object> m = Metrics.summarize(store.liveRuns());
        m.put("queued", queue.queued());
        m.put("running", queue.running());
        m.put("rejected", queue.rejected());
        return m;
    }

    @Override
    public void close() {
        queue.close();
    }

    /** Deletes the clone directory of a run (called when the operator cleans up). */
    public void deleteClone(RepairRun run) {
        if (run.sourceRepo() != null && cfg.dataDir() != null && run.sourceRepo().startsWith(cfg.dataDir().resolve("clones"))) {
            Fs.deleteTree(run.sourceRepo());
        }
    }
}
