package dev.rakshit.repair.sandbox;

/** Picks the strongest sandbox this machine can provide. */
public final class Sandboxes {
    private Sandboxes() {}

    public static Sandbox auto() {
        if (DockerSandbox.available()) return DockerSandbox.temurin17();
        return new ProcessSandbox();
    }
}
