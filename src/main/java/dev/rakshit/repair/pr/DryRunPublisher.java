package dev.rakshit.repair.pr;

import dev.rakshit.repair.agent.RepairRun;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes the pull request (title and description) and the patch to disk instead of contacting GitHub. */
public final class DryRunPublisher implements PullRequestPublisher {
    private final Path outDir;

    public DryRunPublisher(Path outDir) {
        this.outDir = outDir;
    }

    @Override
    public Result publish(RepairRun run) throws IOException {
        if (run.diff().isBlank()) throw new IOException("run " + run.id() + " has no verified diff");
        Path dir = outDir.resolve(run.id());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("PR.md"), "# " + PrBody.title(run) + "\n\n" + run.prMarkdown(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("fix.patch"), run.diff(), StandardCharsets.UTF_8);
        return new Result(dir.resolve("PR.md").toString(), "dry run: wrote PR.md and fix.patch (set GITHUB_TOKEN to open real pull requests)");
    }
}
