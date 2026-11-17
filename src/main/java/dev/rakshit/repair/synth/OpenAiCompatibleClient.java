package dev.rakshit.repair.synth;

import dev.rakshit.repair.util.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Calls any server that speaks the OpenAI chat-completions API: a local model under Ollama
 * ({@code http://localhost:11434/v1}), LM Studio ({@code http://localhost:1234/v1}), llama.cpp's server or vLLM, as
 * well as hosted gateways. The API key is optional because local servers do not check one.
 *
 * <p>Reasoning models (Qwen3, DeepSeek-R1, ...) often emit a {@code <think>...</think>} block before the answer; it is
 * removed so the diff extraction only sees the answer.
 */
public final class OpenAiCompatibleClient implements LlmClient {
    public static final String OLLAMA_URL = "http://localhost:11434/v1";
    private static final Pattern THINK = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);

    private final String baseUrl, model, apiKey;
    private final Duration timeout;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    /**
     * @param baseUrl the API root including the version segment, e.g. {@code http://localhost:11434/v1}
     * @param apiKey  sent as a bearer token when non-blank; local servers do not need one
     * @param timeout per request; a local model on a laptop GPU can take minutes for a long prompt
     */
    public OpenAiCompatibleClient(String baseUrl, String model, String apiKey, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("a base URL is required");
        if (model == null || model.isBlank()) throw new IllegalArgumentException("a model name is required (REPAIR_MODEL)");
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.timeout = timeout;
    }

    public String model() {
        return model;
    }

    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public Completion complete(String system, String user, int maxTokens, double temperature) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("stream", false);
        body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", user)));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (!apiKey.isBlank()) b.header("authorization", "Bearer " + apiKey);
        HttpRequest req = b.build();

        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 429 || code >= 500) {
                    last = new IOException("model server returned " + code + ": " + abbreviate(resp.body()));
                    Thread.sleep(500L << attempt);
                    continue;
                }
                if (code != 200) throw new IOException("model server returned " + code + ": " + abbreviate(resp.body()));
                Map<String, Object> json = Json.parseObject(resp.body());
                String text = "";
                if (json.get("choices") instanceof List<?> choices && !choices.isEmpty()) {
                    Object content = Json.path(choices.get(0), "message", "content");
                    if (content instanceof String s) text = s;
                }
                long in = number(Json.path(json, "usage", "prompt_tokens")), out = number(Json.path(json, "usage", "completion_tokens"));
                return new Completion(stripThinking(text), in, out);
            } catch (java.net.ConnectException e) {
                throw new IOException("cannot reach the model server at " + baseUrl + " (is it running?)", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
        throw last;
    }

    static String stripThinking(String text) {
        String t = THINK.matcher(text).replaceAll("");
        // A reply cut off inside its reasoning has an opening tag and no closing one: nothing usable follows it.
        int open = t.indexOf("<think>");
        if (open >= 0) t = t.substring(0, open);
        return t.strip();
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
