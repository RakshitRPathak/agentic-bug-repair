package dev.rakshit.repair.service;

import dev.rakshit.repair.agent.RepairRun;
import dev.rakshit.repair.util.Json;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GitHub webhook logic, separate from HTTP so it can be tested directly. Order matters: the signature is checked first,
 * over the exact raw bytes received, before the body is parsed or anything is trusted. The clone URL is built from the
 * repository name, never taken from the payload.
 */
public final class WebhookHandler {
    public record Response(int status, Map<String, Object> body) {}

    private final RepairService service;

    public WebhookHandler(RepairService service) {
        this.service = service;
    }

    public Response handle(String event, String deliveryId, String signature, byte[] raw) {
        String secret = service.config().webhookSecret();
        if (secret == null || secret.isEmpty()) return reply(503, "webhook endpoint is disabled (no secret configured)");
        if (!Signature.valid(secret, raw, signature)) return reply(401, "invalid signature");
        if ("ping".equals(event)) return reply(200, "pong");

        Map<String, Object> payload;
        try {
            payload = Json.parseObject(new String(raw, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return reply(400, "body is not valid JSON");
        }
        String slug = Json.str(payload, "repository", "full_name");
        String conclusion, sha, branch;
        if ("workflow_run".equals(event)) {
            if (!"completed".equals(Json.str(payload, "action"))) return reply(200, "ignored: workflow not completed");
            conclusion = Json.str(payload, "workflow_run", "conclusion");
            sha = Json.str(payload, "workflow_run", "head_sha");
            branch = Json.str(payload, "workflow_run", "head_branch");
        } else if ("check_suite".equals(event)) {
            if (!"completed".equals(Json.str(payload, "action"))) return reply(200, "ignored: check suite not completed");
            conclusion = Json.str(payload, "check_suite", "conclusion");
            sha = Json.str(payload, "check_suite", "head_sha");
            branch = Json.str(payload, "check_suite", "head_branch");
        } else {
            return reply(200, "ignored event " + event);
        }
        if (!"failure".equals(conclusion)) return reply(200, "ignored: conclusion is " + conclusion);
        if (slug == null || !slug.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) return reply(400, "missing or malformed repository name");
        if (sha != null && !sha.matches("[0-9a-f]{7,40}")) return reply(400, "malformed commit sha");
        if (branch != null && !branch.matches("[A-Za-z0-9_./-]{1,200}")) return reply(400, "malformed branch name");
        try {
            RepairRun run = service.submitFailure(slug, branch, sha, deliveryId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "repair queued");
            body.put("runId", run.id());
            return new Response(202, body);
        } catch (RepairService.RejectedException e) {
            return reply(e.status(), e.getMessage());
        }
    }

    private static Response reply(int status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", message);
        return new Response(status, m);
    }
}
