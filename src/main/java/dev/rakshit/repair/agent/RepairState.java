package dev.rakshit.repair.agent;

public enum RepairState {
    INGEST("Ingest", "Clone, index the code graph, run the failing tests once"),
    LOCALIZE("Fault localizer", "Rank suspect methods from the stack trace, call graph and retrieval"),
    SYNTHESIZE("Patch synthesizer", "Propose surgical unified diffs"),
    VERIFY("Sandbox verifier", "Apply each diff in an isolated sandbox, build, run all tests"),
    CRITIC("Critic", "Explain what is still wrong and feed it back"),
    PUBLISH("Publish", "Re-verify the final diff and prepare the pull request"),
    RESOLVED("Resolved", "A verified fix is ready"),
    UNRESOLVED("Unresolved", "No verified fix within the budget");

    public final String label;
    public final String description;

    RepairState(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public boolean terminal() {
        return this == RESOLVED || this == UNRESOLVED;
    }
}
