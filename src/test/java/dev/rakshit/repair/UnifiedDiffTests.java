package dev.rakshit.repair;

import static dev.rakshit.repair.testkit.Assert.assertEquals;
import static dev.rakshit.repair.testkit.Assert.assertFalse;
import static dev.rakshit.repair.testkit.Assert.assertTrue;

import dev.rakshit.repair.patch.UnifiedDiff;
import dev.rakshit.repair.testkit.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class UnifiedDiffTests {
    private static String apply(String before, String diff) throws Exception {
        return UnifiedDiff.apply(before, UnifiedDiff.parse(diff).get(0));
    }

    @Test
    public void createThenApplyReproducesTheNewText() throws Exception {
        String before = "a\nb\nc\nd\ne\nf\ng\nh\n", after = "a\nB\nc\nd\ne\nf\ng\nh\ni\n";
        String diff = UnifiedDiff.create("x.txt", before, after);
        assertTrue(diff.startsWith("--- a/x.txt\n+++ b/x.txt\n@@"), diff);
        assertEquals(after, apply(before, diff));
    }

    @Test
    public void identicalTextsGiveAnEmptyDiff() {
        assertEquals("", UnifiedDiff.create("f", "same\n", "same\n"));
    }

    @Test
    public void handlesMissingTrailingNewlineInEitherDirection() throws Exception {
        for (String[] pair : new String[][] {{"x\ny", "x\ny\n"}, {"x\ny\n", "x\ny"}, {"x", "y"}, {"", "a\n"}, {"a\n", ""}, {"a", "a\nb"}}) {
            String d = UnifiedDiff.create("f", pair[0], pair[1]);
            assertEquals(pair[1], apply(pair[0], d));
        }
    }

    @Test
    public void distantChangesGetSeparateHunks() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 40; i++) lines.add("line" + i);
        String before = String.join("\n", lines) + "\n";
        lines.set(2, "CHANGED-A");
        lines.set(35, "CHANGED-B");
        String after = String.join("\n", lines) + "\n";
        String diff = UnifiedDiff.create("f", before, after);
        assertEquals(2, diff.split("\n@@", -1).length - 1);
        assertEquals(after, apply(before, diff));
    }

    @Test
    public void toleratesASmallLineOffset() throws Exception {
        String before = "1\n2\n3\n4\n5\n6\n7\n8\n";
        String diff = UnifiedDiff.create("f", before, "1\n2\n3\nFOUR\n5\n6\n7\n8\n");
        String shifted = "NEW0\nNEW1\n" + before;
        assertEquals("NEW0\nNEW1\n1\n2\n3\nFOUR\n5\n6\n7\n8\n", apply(shifted, diff));
    }

    @Test
    public void rejectsContextThatDoesNotMatchAndSaysWhere() throws Exception {
        String diff = "--- a/f\n+++ b/f\n@@ -1,3 +1,3 @@\n one\n-two\n+TWO\n three\n";
        try {
            apply("uno\ndos\ntres\n", diff);
            throw new AssertionError("should not apply");
        } catch (UnifiedDiff.PatchException e) {
            assertTrue(e.getMessage().contains("does not apply"), e.getMessage());
            assertTrue(e.getMessage().contains("one"), "names the lines it wanted: " + e.getMessage());
        }
    }

    @Test
    public void acceptsBlankContextLinesWithoutTheLeadingSpace() throws Exception {
        String diff = "--- a/f\n+++ b/f\n@@ -1,4 +1,4 @@\n a\n\n-b\n+B\n c\n";
        assertEquals("a\n\nB\nc\n", apply("a\n\nb\nc\n", diff));
    }

    @Test
    public void refusesCreatingDeletingAndRenamingFiles() {
        for (String bad : new String[] {
                "--- /dev/null\n+++ b/new.txt\n@@ -0,0 +1 @@\n+hi\n",
                "diff --git a/x b/x\nnew file mode 100644\n--- /dev/null\n+++ b/x\n@@ -0,0 +1 @@\n+hi\n",
                "--- a/old\n+++ b/new\n@@ -1 +1 @@\n-a\n+b\n"}) {
            try {
                UnifiedDiff.parse(bad);
                throw new AssertionError("accepted: " + bad);
            } catch (UnifiedDiff.PatchException expected) {
                // good
            }
        }
    }

    @Test
    public void applyToRefusesPathsOutsideTheRepository() throws Exception {
        Path root = Fixtures.tempDir("diff-root-");
        Files.writeString(root.resolve("ok.txt"), "a\n");
        for (String path : new String[] {"../escape.txt", "/etc/passwd", ".git/config", "sub/../../x"}) {
            try {
                UnifiedDiff.applyTo(root, "--- a/" + path + "\n+++ b/" + path + "\n@@ -1 +1 @@\n-a\n+b\n");
                throw new AssertionError("accepted " + path);
            } catch (UnifiedDiff.PatchException expected) {
                // good
            }
        }
    }

    @Test
    public void multiFileDiffIsAllOrNothing() throws Exception {
        Path root = Fixtures.tempDir("diff-aon-");
        Files.writeString(root.resolve("a.txt"), "1\n2\n3\n");
        Files.writeString(root.resolve("b.txt"), "x\ny\nz\n");
        String good = UnifiedDiff.create("a.txt", "1\n2\n3\n", "1\nTWO\n3\n");
        String bad = "--- a/b.txt\n+++ b/b.txt\n@@ -1,3 +1,3 @@\n x\n-WRONG\n+y2\n z\n";
        try {
            UnifiedDiff.applyTo(root, good + bad);
            throw new AssertionError("should fail");
        } catch (UnifiedDiff.PatchException expected) {
            assertEquals("1\n2\n3\n", Files.readString(root.resolve("a.txt")));      // the good half was not written
        }
        UnifiedDiff.applyTo(root, good);
        assertEquals("1\nTWO\n3\n", Files.readString(root.resolve("a.txt")));
    }

    @Test
    public void statsCountAddedAndRemovedLines() {
        int[] s = UnifiedDiff.stats(UnifiedDiff.create("f", "a\nb\nc\n", "a\nB\nc\nd\n"));
        assertEquals(2, s[0]);
        assertEquals(1, s[1]);
    }

    /** The diffs we write must be accepted by the real git, and git must produce the same result as our own applier. */
    @Test
    public void generatedDiffsAreAcceptedByGitApply() throws Exception {
        if (!gitAvailable()) return;
        Random r = new Random(7);
        Path dir = Fixtures.tempDir("diff-git-");
        run(dir, "git", "init", "-q");
        for (int t = 0; t < 120; t++) {
            String before = randomText(r), after = mutate(r, before);
            String diff = UnifiedDiff.create("f.txt", before, after);
            if (diff.isEmpty()) continue;
            assertEquals(after, apply(before, diff));
            Files.writeString(dir.resolve("f.txt"), before);
            Files.writeString(dir.resolve("p.diff"), diff);
            assertTrue(run(dir, "git", "apply", "--check", "p.diff") == 0, "git rejected:\n" + diff + "\nbefore=[" + before + "]");
            run(dir, "git", "apply", "p.diff");
            assertEquals(after, Files.readString(dir.resolve("f.txt")));
        }
    }

    @Test
    public void gitProducedDiffsApplyWithOurApplier() throws Exception {
        if (!gitAvailable()) return;
        Path dir = Fixtures.tempDir("diff-gitmade-");
        run(dir, "git", "init", "-q");
        Files.writeString(dir.resolve("f.txt"), "alpha\nbeta\ngamma\ndelta\nepsilon\nzeta\neta\ntheta\n");
        run(dir, "git", "add", "f.txt");
        run(dir, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "x");
        Files.writeString(dir.resolve("f.txt"), "alpha\nBETA\ngamma\ndelta\nepsilon\nzeta\neta\ntheta\niota\n");
        Process p = new ProcessBuilder("git", "diff").directory(dir.toFile()).start();
        String gitDiff = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        assertEquals("alpha\nBETA\ngamma\ndelta\nepsilon\nzeta\neta\ntheta\niota\n",
                apply("alpha\nbeta\ngamma\ndelta\nepsilon\nzeta\neta\ntheta\n", gitDiff));
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static int run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }

    private static String randomText(Random r) {
        int n = r.nextInt(30);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(r.nextInt(6) == 0 ? "" : "line" + r.nextInt(7)).append('\n');
        String s = sb.toString();
        return n > 0 && r.nextInt(5) == 0 ? s.substring(0, s.length() - 1) : s;
    }

    private static String mutate(Random r, String text) {
        List<String> l = new ArrayList<>(List.of(text.split("\n", -1)));
        if (!l.isEmpty() && l.get(l.size() - 1).isEmpty()) l.remove(l.size() - 1);
        for (int e = r.nextInt(5); e > 0; e--) {
            int k = r.nextInt(3);
            if (k == 0 && !l.isEmpty()) l.remove(r.nextInt(l.size()));
            else if (k == 1) l.add(l.isEmpty() ? 0 : r.nextInt(l.size() + 1), "new" + r.nextInt(5));
            else if (!l.isEmpty()) l.set(r.nextInt(l.size()), "chg" + r.nextInt(5));
        }
        String s = String.join("\n", l) + (l.isEmpty() ? "" : "\n");
        return !l.isEmpty() && r.nextInt(6) == 0 ? s.substring(0, s.length() - 1) : s;
    }

    @Test
    public void unusedAssertionHelperKeepsImportsHonest() {
        assertFalse(UnifiedDiff.create("f", "a\n", "b\n").isEmpty());
    }
}
