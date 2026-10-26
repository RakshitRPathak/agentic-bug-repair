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

/** Calls the Anthropic Messages API with the JDK's HTTP client. Retries rate-limit and overload responses with backoff. */
public final class AnthropicClient implements LlmClient {
    public static final String DEFAULT_URL = "https://api.anthropic.com";
    private final String apiKey, model, baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public AnthropicClient(String apiKey, String model, String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("an API key is required");
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public Completion complete(String system, String user, int maxTokens, double temperature) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("system", system);
        body.put("messages", List.of(Map.of("role", "user", "content", user)));
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                .timeout(Duration.ofSeconds(120))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int code = resp.statusCode();
                if (code == 429 || code == 529 || code >= 500) {
                    last = new IOException("model API returned " + code + ": " + abbreviate(resp.body()));
                    Thread.sleep(500L << attempt);
                    continue;
                }
                if (code != 200) throw new IOException("model API returned " + code + ": " + abbreviate(resp.body()));
                Map<String, Object> json = Json.parseObject(resp.body());
                StringBuilder text = new StringBuilder();
                if (json.get("content") instanceof List<?> blocks) {
                    for (Object b : blocks) if ("text".equals(Json.str(b, "type"))) text.append(Json.str(b, "text"));
                }
                long in = number(Json.path(json, "usage", "input_tokens")), out = number(Json.path(json, "usage", "output_tokens"));
                return new Completion(text.toString(), in, out);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
        throw last;
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static String abbreviate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
