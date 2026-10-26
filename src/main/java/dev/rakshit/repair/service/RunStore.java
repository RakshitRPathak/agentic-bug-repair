package dev.rakshit.repair.service;

import dev.rakshit.repair.agent.RepairRun;
import dev.rakshit.repair.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the most recent runs in memory (newest first) and writes each finished run to {@code dataDir/<id>.json}, so the
 * dashboard keeps its history across restarts. Old runs beyond {@code maxRuns} are dropped from memory and disk.
 */
public final class RunStore {
    private final int maxRuns;
    private final Path dataDir;
    private final Map<String, RepairRun> live = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, Map<String, Object>> archived = Collections.synchronizedMap(new LinkedHashMap<>());

    public RunStore(Path dataDir, int maxRuns) {
        this.dataDir = dataDir;
        this.maxRuns = maxRuns;
        if (dataDir != null) loadArchive();
    }

    private void loadArchive() {
        try {
            Files.createDirectories(dataDir);
            List<Path> files = new ArrayList<>();
            try (var s = Files.list(dataDir)) {
                s.filter(p -> p.toString().endsWith(".json")).forEach(files::add);
            }
            files.sort((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()));
            for (Path f : files) {
                try {
                    Map<String, Object> m = Json.parseObject(Files.readString(f, StandardCharsets.UTF_8));
                    archived.put(String.valueOf(m.get("id")), m);
                } catch (RuntimeException | IOException ignored) {
                    // a corrupt file is skipped, not fatal
                }
            }
        } catch (IOException ignored) {
            // no persistence if the directory cannot be created
        }
    }

    public void add(RepairRun run) {
        live.put(run.id(), run);
        trim();
    }

    public RepairRun live(String id) {
        return live.get(id);
    }

    /** JSON for a run, live or archived. */
    public Map<String, Object> get(String id) {
        RepairRun r = live.get(id);
        if (r != null) return r.toJson();
        return archived.get(id);
    }

    public void persist(RepairRun run) {
        if (dataDir == null) return;
        try {
            Files.createDirectories(dataDir);
            Files.writeString(dataDir.resolve(run.id() + ".json"), Json.write(run.toJson()), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // history is best effort
        }
    }

    public List<RepairRun> liveRuns() {
        synchronized (live) {
            return new ArrayList<>(live.values());
        }
    }

    /** One-line summaries, newest first. */
    public List<Map<String, Object>> summaries() {
        Map<String, Map<String, Object>> all = new LinkedHashMap<>();
        synchronized (archived) {
            for (var e : archived.entrySet()) all.put(e.getKey(), summary(e.getValue()));
        }
        for (RepairRun r : liveRuns()) all.put(r.id(), summary(r.toJson()));
        List<Map<String, Object>> list = new ArrayList<>(all.values());
        list.sort((a, b) -> Long.compare(((Number) b.get("createdAt")).longValue(), ((Number) a.get("createdAt")).longValue()));
        return list;
    }

    private static Map<String, Object> summary(Map<String, Object> full) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("id", "label", "state", "finished", "resolved", "outcome", "createdAt", "latencyMs", "round")) m.put(k, full.get(k));
        Object failing = full.get("failingTests");
        m.put("failing", failing instanceof List<?> l ? l.size() : 0);
        return m;
    }

    private void trim() {
        synchronized (live) {
            while (live.size() > maxRuns) {
                String oldest = live.keySet().iterator().next();
                live.remove(oldest);
                if (dataDir != null) {
                    try {
                        Files.deleteIfExists(dataDir.resolve(oldest + ".json"));
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
        }
    }
}
