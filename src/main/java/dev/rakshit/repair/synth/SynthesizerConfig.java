package dev.rakshit.repair.synth;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Chooses the patch synthesizer from environment variables, in one place for the CLI, the service and the benchmark.
 *
 * <ul>
 *   <li>{@code REPAIR_LLM_URL} set: any OpenAI-compatible server, e.g. a local model under Ollama
 *       ({@code http://localhost:11434/v1}) or LM Studio. {@code REPAIR_MODEL} names the model (required);
 *       {@code REPAIR_LLM_API_KEY} is optional; {@code REPAIR_LLM_TIMEOUT_S} (default 300) bounds one request.</li>
 *   <li>otherwise {@code ANTHROPIC_API_KEY} set: the Anthropic API; {@code REPAIR_MODEL} defaults to claude-sonnet-5-5.</li>
 *   <li>otherwise: the offline mutation synthesizer only.</li>
 * </ul>
 * {@code REPAIR_SAMPLES} (default 2) is how many replies the model is asked for per round.
 */
public final class SynthesizerConfig {
    /** How the model, if any, is combined with the free mutation synthesizer. */
    public enum Mode {
        /** The model is used after the mutation synthesizer runs dry or fails, when one is configured (the default). */
        CASCADE,
        /** Mutation synthesizer only, even when a model is configured. */
        MUTATION,
        /** The model alone, to measure what it can do without the mutation synthesizer's help. */
        MODEL_ONLY;

        public static Mode parse(String s) {
            return switch (s) {
                case "cascade", "model" -> CASCADE;
                case "mutation" -> MUTATION;
                case "model-only" -> MODEL_ONLY;
                default -> throw new IllegalArgumentException("unknown synthesizer '" + s + "' (mutation, model, model-only)");
            };
        }
    }

    private SynthesizerConfig() {}

    /** Returns null when no model is configured. */
    public static LlmClient modelFromEnv(Map<String, String> env) {
        String url = env.getOrDefault("REPAIR_LLM_URL", "");
        if (!url.isBlank()) {
            long timeout = Long.parseLong(env.getOrDefault("REPAIR_LLM_TIMEOUT_S", "300"));
            return new OpenAiCompatibleClient(url, env.get("REPAIR_MODEL"), env.get("REPAIR_LLM_API_KEY"), Duration.ofSeconds(timeout));
        }
        String key = env.getOrDefault("ANTHROPIC_API_KEY", "");
        if (!key.isBlank())
            return new AnthropicClient(key, env.getOrDefault("REPAIR_MODEL", "claude-sonnet-5-5"), AnthropicClient.DEFAULT_URL);
        return null;
    }

    /** A short label for reports: which model on which server, never the key. */
    public static String describeModel(Map<String, String> env) {
        String url = env.getOrDefault("REPAIR_LLM_URL", "");
        if (!url.isBlank()) return env.getOrDefault("REPAIR_MODEL", "?") + " via " + url;
        if (!env.getOrDefault("ANTHROPIC_API_KEY", "").isBlank())
            return env.getOrDefault("REPAIR_MODEL", "claude-sonnet-5-5") + " via the Anthropic API";
        return "none";
    }

    public static Supplier<PatchSynthesizer> fromEnv(Map<String, String> env, Mode mode) {
        LlmClient model = mode == Mode.MUTATION ? null : modelFromEnv(env);
        if (model == null) {
            if (mode == Mode.MODEL_ONLY)
                throw new IllegalArgumentException("model-only needs a model: set REPAIR_LLM_URL and REPAIR_MODEL, or ANTHROPIC_API_KEY");
            return MutationSynthesizer::new;
        }
        int samples = Integer.parseInt(env.getOrDefault("REPAIR_SAMPLES", "2"));
        if (mode == Mode.MODEL_ONLY) return () -> new LlmSynthesizer(model, samples);
        return () -> new CascadeSynthesizer(new MutationSynthesizer(), new LlmSynthesizer(model, samples), 2);
    }
}
