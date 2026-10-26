package dev.rakshit.repair.sandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * Runs an untrusted command against a copy of a repository. The caller's directory is never modified: whatever the
 * command writes (compiled classes, test output, or anything hostile) lands in a throwaway copy that is deleted.
 */
public interface Sandbox {
    SandboxResult run(Path repo, List<String> command, Limits limits) throws java.io.IOException;

    /** One line saying what isolation is actually in force, shown on the dashboard and in every pull request. */
    String describe();
}
