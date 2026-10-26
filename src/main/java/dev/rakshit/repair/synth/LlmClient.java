package dev.rakshit.repair.synth;

import java.io.IOException;

/** The one thing the synthesizer needs from a language model: send a prompt, get text and token counts back. */
public interface LlmClient {
    record Completion(String text, long inputTokens, long outputTokens) {}

    Completion complete(String system, String user, int maxTokens, double temperature) throws IOException;
}
