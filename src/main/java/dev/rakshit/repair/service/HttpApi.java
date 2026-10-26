package dev.rakshit.repair.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.rakshit.repair.pr.PullRequestPublisher;
import dev.rakshit.repair.util.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The HTTP surface, on the JDK's built-in server:
 *
 * <pre>
 * GET  /                         dashboard (static files from the jar)
 * GET  /api/runs                 one-line summaries, newest first
 * GET  /api/runs/{id}            everything about a run, live while it executes
 * GET  /api/runs/{id}/patch      the verified diff, as a downloadable .patch
 * GET  /api/metrics              resolve rate, latency p50/p95, tokens, cost, solved-within-k
 * POST /api/runs                 {"repoPath": "...", "label": "..."} repair a repository on this machine   (needs API key)
 * POST /api/runs/{id}/approve    open the pull request for a resolved run                                    (needs API key)
 * POST /webhooks/github          GitHub workflow_run / check_suite events, HMAC-SHA256 verified
 * </pre>
 */
public final class HttpApi implements AutoCloseable {
    private static final int MAX_BODY = 2 << 20;
    private final RepairService service;
    private final HttpServer server;

    public HttpApi(RepairService service, String host, int port) throws IOException {
        this.service = service;
        String key = service.config().apiKey();
        InetSocketAddress addr = new InetSocketAddress(host, port);
        if ((key == null || key.isEmpty()) && !addr.getAddress().isLoopbackAddress()) {
            throw new IllegalArgumentException("refusing to listen on " + host + " without an API key; set REPAIR_API_KEY or bind to 127.0.0.1");
        }
        this.server = HttpServer.create(addr, 0);
        server.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "http");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::route);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) staticFile(ex, "index.html", "text/html; charset=utf-8");
            else if (method.equals("GET") && path.equals("/app.js")) staticFile(ex, "app.js", "text/javascript; charset=utf-8");
            else if (method.equals("GET") && path.equals("/style.css")) staticFile(ex, "style.css", "text/css; charset=utf-8");
            else if (method.equals("GET") && path.equals("/api/runs")) json(ex, 200, service.store().summaries());
            else if (method.equals("GET") && path.equals("/api/metrics")) json(ex, 200, service.metrics());
            else if (method.equals("GET") && path.matches("/api/runs/[A-Za-z0-9]+")) {
                Map<String, Object> run = service.store().get(path.substring("/api/runs/".length()));
                if (run == null) error(ex, 404, "no such run");
                else json(ex, 200, run);
            } else if (method.equals("GET") && path.matches("/api/runs/[A-Za-z0-9]+/patch")) {
                Map<String, Object> run = service.store().get(path.split("/")[3]);
                Object diff = run == null ? null : run.get("diff");
                if (diff == null || diff.toString().isBlank()) error(ex, 404, "no verified patch for that run");
                else send(ex, 200, "text/x-diff; charset=utf-8", diff.toString().getBytes(StandardCharsets.UTF_8),
                        Map.of("Content-Disposition", "attachment; filename=\"fix-" + path.split("/")[3] + ".patch\""));
            } else if (method.equals("POST") && path.equals("/api/runs")) {
                if (!authorised(ex)) return;
                startRun(ex);
            } else if (method.equals("POST") && path.matches("/api/runs/[A-Za-z0-9]+/approve")) {
                if (!authorised(ex)) return;
                approve(ex, path.split("/")[3]);
            } else if (method.equals("POST") && path.equals("/webhooks/github")) {
                webhook(ex);
            } else {
                error(ex, 404, "not found");
            }
        } catch (RuntimeException e) {
            error(ex, 500, "internal error");
        } finally {
            ex.close();
        }
    }

    /**
     * Browser-borne attacks on a local server: a page on another site can POST to localhost (CSRF), and DNS rebinding can
     * make a hostile hostname resolve to 127.0.0.1. Requiring a JSON content type forces a CORS preflight that this server
     * never grants, an Origin that is present must be this server's own, and with no API key the Host must be a loopback name.
     */
    private boolean browserSafe(HttpExchange ex) throws IOException {
        var h = ex.getRequestHeaders();
        String type = h.getFirst("Content-Type");
        if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            error(ex, 415, "send Content-Type: application/json");
            return false;
        }
        String host = h.getFirst("Host");
        String origin = h.getFirst("Origin");
        if (origin != null) {
            String authority;
            try {
                authority = java.net.URI.create(origin).getAuthority();
            } catch (IllegalArgumentException e) {
                authority = null;
            }
            if (authority == null || host == null || !authority.equalsIgnoreCase(host)) {
                error(ex, 403, "cross-origin requests are not allowed");
                return false;
            }
        }
        String key = service.config().apiKey();
        if (key == null || key.isEmpty()) {
            String name = host == null ? "" : host.replaceAll(":\\d+$", "").toLowerCase(java.util.Locale.ROOT);
            if (!(name.equals("localhost") || name.equals("127.0.0.1") || name.equals("[::1]"))) {
                error(ex, 403, "unexpected Host header");
                return false;
            }
        }
        return true;
    }

    private boolean authorised(HttpExchange ex) throws IOException {
        if (!browserSafe(ex)) return false;
        String key = service.config().apiKey();
        if (key == null || key.isEmpty()) {
            if (ex.getRemoteAddress().getAddress().isLoopbackAddress()) return true;
            error(ex, 403, "no API key configured; only local requests are allowed");
            return false;
        }
        String given = ex.getRequestHeaders().getFirst("X-API-Key");
        boolean ok = given != null && MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
        if (!ok) error(ex, 401, "missing or wrong X-API-Key");
        return ok;
    }

    private void startRun(HttpExchange ex) throws IOException {
        byte[] body = readBody(ex);
        if (body == null) return;
        Map<String, Object> req;
        try {
            req = Json.parseObject(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            error(ex, 400, "body must be a JSON object");
            return;
        }
        String repoPath = Json.str(req, "repoPath");
        if (repoPath == null || repoPath.isBlank()) {
            error(ex, 400, "repoPath is required");
            return;
        }
        try {
            var run = service.submitLocal(Path.of(repoPath), Json.str(req, "label"), Json.str(req, "repo"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", run.id());
            json(ex, 202, m);
        } catch (RepairService.RejectedException e) {
            error(ex, e.status(), e.getMessage());
        }
    }

    private void approve(HttpExchange ex, String id) throws IOException {
        try {
            PullRequestPublisher.Result r = service.approve(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", r.url());
            m.put("note", r.note());
            json(ex, 200, m);
        } catch (RepairService.RejectedException e) {
            error(ex, e.status(), e.getMessage());
        }
    }

    private void webhook(HttpExchange ex) throws IOException {
        byte[] body = readBody(ex);
        if (body == null) return;
        var h = ex.getRequestHeaders();
        WebhookHandler.Response r = new WebhookHandler(service).handle(h.getFirst("X-GitHub-Event"), h.getFirst("X-GitHub-Delivery"),
                h.getFirst("X-Hub-Signature-256"), body);
        if (r.status() == 429) ex.getResponseHeaders().add("Retry-After", "60");
        json(ex, r.status(), r.body());
    }

    /** Reads the request body, or answers 413 and returns null if it is too large. */
    private byte[] readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            if (b.length > MAX_BODY) {
                error(ex, 413, "request body too large");
                return null;
            }
            return b;
        }
    }

    private void staticFile(HttpExchange ex, String name, String type) throws IOException {
        try (InputStream in = HttpApi.class.getResourceAsStream("/web/" + name)) {
            if (in == null) {
                error(ex, 404, "dashboard files are not packaged");
                return;
            }
            send(ex, 200, type, in.readAllBytes(), Map.of(
                    "Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'"));
        }
    }

    private void json(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.write(body).getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private void error(HttpExchange ex, int status, String message) throws IOException {
        json(ex, status, Map.of("error", message));
    }

    private void send(HttpExchange ex, int status, String type, byte[] body, Map<String, String> extra) throws IOException {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Cache-Control", "no-store");
        for (var e : extra.entrySet()) h.set(e.getKey(), e.getValue());
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    /** Lists the route table, for the README and tests. */
    public static List<String> routes() {
        return List.of("GET /", "GET /api/runs", "GET /api/runs/{id}", "GET /api/runs/{id}/patch", "GET /api/metrics",
                "POST /api/runs", "POST /api/runs/{id}/approve", "POST /webhooks/github");
    }
}
