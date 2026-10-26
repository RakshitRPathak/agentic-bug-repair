package dev.rakshit.repair.pr;

import dev.rakshit.repair.agent.RepairRun;
import java.io.IOException;

/** Turns a verified run into something a human can review. */
public interface PullRequestPublisher {
    record Result(String url, String note) {}

    Result publish(RepairRun run) throws IOException;
}
