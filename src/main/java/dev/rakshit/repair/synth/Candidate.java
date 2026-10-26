package dev.rakshit.repair.synth;

/**
 * One proposed fix: a unified diff against the workspace plus a human-readable reason. {@code key} identifies the edit
 * so the same change is never tried twice.
 */
public record Candidate(String key, String operator, String description, String methodId, String file, int line, String diff) {}
