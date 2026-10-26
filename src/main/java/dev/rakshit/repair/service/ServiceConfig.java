package dev.rakshit.repair.service;

import dev.rakshit.repair.agent.RepairConfig;
import dev.rakshit.repair.pr.PullRequestPublisher;
import dev.rakshit.repair.sandbox.Sandbox;
import dev.rakshit.repair.synth.PatchSynthesizer;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * @param webhookSecret shared secret for GitHub's HMAC signature; empty disables the webhook endpoint
 * @param allowedRepos "owner/name" values webhooks may trigger repairs for; anything else gets 403
 * @param localRoots directories that {@code POST /api/runs} may read from (the API copies whatever path it is given)
 * @param apiKey required in {@code X-API-Key} for state-changing API calls; empty only allowed on a loopback address
 * @param cloneUrl maps "owner/name" to the URL to clone (production: github.com; tests: a local bare repository)
 */
public record ServiceConfig(Path dataDir, String webhookSecret, Set<String> allowedRepos, List<Path> localRoots, String apiKey,
                            int workers, int queueCapacity, RepairConfig repair, Supplier<PatchSynthesizer> synthesizer,
                            Sandbox sandbox, PullRequestPublisher publisher, Function<String, String> cloneUrl) {}
