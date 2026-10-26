package dev.rakshit.repair.synth;

/** Model usage: calls and tokens. Zero for synthesizers that do not call a model. */
public record Usage(int calls, long inputTokens, long outputTokens) {
    public static final Usage NONE = new Usage(0, 0, 0);

    public Usage plus(Usage o) {
        return new Usage(calls + o.calls, inputTokens + o.inputTokens, outputTokens + o.outputTokens);
    }
}
