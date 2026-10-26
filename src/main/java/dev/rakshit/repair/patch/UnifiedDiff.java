package dev.rakshit.repair.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates and applies unified diffs (the format {@code git diff} prints and {@code git apply} reads). Generated diffs
 * are accepted by {@code git apply}; the applier is strict about context, so a patch written against the wrong lines is
 * rejected with a message precise enough to feed back to the model that wrote it.
 */
public final class UnifiedDiff {
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");
    private static final int CONTEXT = 3;
    private static final int MAX_OFFSET = 40;

    private UnifiedDiff() {}

    public static final class PatchException extends Exception {
        private static final long serialVersionUID = 1L;

        public PatchException(String message) {
            super(message);
        }
    }

    public record Hunk(int oldStart, List<String> lines) {}   // lines keep their leading ' ', '-' or '+'

    public record FileDiff(String path, List<Hunk> hunks) {}

    // ------------------------------------------------------------------------------------------------- create

    /** The diff turning {@code oldText} into {@code newText} for the file {@code path}; empty if they are equal. */
    public static String create(String path, String oldText, String newText) {
        if (oldText.equals(newText)) return "";
        List<String> a = lines(oldText), b = lines(newText);
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) prefix++;
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) suffix++;
        List<String> ma = a.subList(prefix, a.size() - suffix), mb = b.subList(prefix, b.size() - suffix);
        // edit script over the differing middle: ops of ' ', '-', '+'
        List<String> ops = new ArrayList<>();
        for (int i = 0; i < prefix; i++) ops.add(" " + a.get(i));
        lcsOps(ma, mb, ops);
        for (int i = a.size() - suffix; i < a.size(); i++) ops.add(" " + a.get(i));

        StringBuilder out = new StringBuilder();
        out.append("--- a/").append(path).append('\n').append("+++ b/").append(path).append('\n');
        int n = ops.size();
        int i = 0;
        int oldLine = 1, newLine = 1;
        int[] oldAt = new int[n + 1], newAt = new int[n + 1];       // line numbers (1-based) at the start of each op
        for (int k = 0; k < n; k++) {
            oldAt[k] = oldLine;
            newAt[k] = newLine;
            char c = ops.get(k).charAt(0);
            if (c != '+') oldLine++;
            if (c != '-') newLine++;
        }
        while (i < n) {
            while (i < n && ops.get(i).charAt(0) == ' ') i++;
            if (i >= n) break;
            int start = Math.max(0, i - CONTEXT);
            int end = i;
            int lastChange = i;
            int k = i;
            while (k < n) {
                if (ops.get(k).charAt(0) != ' ') lastChange = k;
                if (k - lastChange > 2 * CONTEXT) break;
                k++;
            }
            end = Math.min(n, lastChange + 1 + CONTEXT);
            int oldCount = 0, newCount = 0;
            for (int j = start; j < end; j++) {
                char c = ops.get(j).charAt(0);
                if (c != '+') oldCount++;
                if (c != '-') newCount++;
            }
            out.append("@@ -").append(oldCount == 0 ? oldAt[start] - 1 : oldAt[start]).append(',').append(oldCount)
                    .append(" +").append(newCount == 0 ? newAt[start] - 1 : newAt[start]).append(',').append(newCount).append(" @@\n");
            for (int j = start; j < end; j++) {
                String op = ops.get(j);
                boolean noNewline = op.endsWith(NO_NL);
                out.append(noNewline ? op.substring(0, op.length() - 1) : op).append('\n');
                if (noNewline) out.append("\\ No newline at end of file\n");
            }
            i = end;
        }
        return out.toString();
    }

    private static void lcsOps(List<String> a, List<String> b, List<String> ops) {
        int n = a.size(), m = b.size();
        if ((long) n * m > 4_000_000L) {                      // pathological size: replace the whole region
            for (String s : a) ops.add("-" + s);
            for (String s : b) ops.add("+" + s);
            return;
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                ops.add(" " + a.get(i));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add("-" + a.get(i++));
            } else {
                ops.add("+" + b.get(j++));
            }
        }
        while (i < n) ops.add("-" + a.get(i++));
        while (j < m) ops.add("+" + b.get(j++));
    }

    /** Marks a last line that has no trailing newline, so "x" and "x\n" differ as lines. */
    private static final String NO_NL = "\u0000";

    static List<String> lines(String text) {
        if (text.isEmpty()) return new ArrayList<>();
        boolean nl = text.endsWith("\n");
        List<String> l = new ArrayList<>(List.of(text.split("\n", -1)));
        if (nl) l.remove(l.size() - 1);                    // text ending in \n has no extra empty line
        else l.set(l.size() - 1, l.get(l.size() - 1) + NO_NL);
        return l;
    }

    // -------------------------------------------------------------------------------------------------- parse

    public static List<FileDiff> parse(String diff) throws PatchException {
        List<FileDiff> files = new ArrayList<>();
        String[] lines = diff.split("\n", -1);
        int i = 0;
        String oldPath = null;
        while (i < lines.length) {
            String line = lines[i];
            if (line.startsWith("diff --git ") || line.startsWith("index ") || line.isEmpty() && i == lines.length - 1) {
                i++;
            } else if (line.startsWith("new file mode") || line.startsWith("deleted file mode") || line.startsWith("rename ")
                    || line.startsWith("Binary files")) {
                throw new PatchException("only in-place edits of existing files are supported (found: " + line + ")");
            } else if (line.startsWith("--- ")) {
                oldPath = stripPrefix(line.substring(4));
                i++;
            } else if (line.startsWith("+++ ")) {
                if (oldPath == null) throw new PatchException("'+++' line without a preceding '---' line");
                String newPath = stripPrefix(line.substring(4));
                if (oldPath.equals("/dev/null") || newPath.equals("/dev/null")) {
                    throw new PatchException("creating or deleting files is not supported");
                }
                if (!oldPath.equals(newPath)) throw new PatchException("renames are not supported: " + oldPath + " -> " + newPath);
                i++;
                List<Hunk> hunks = new ArrayList<>();
                while (i < lines.length && lines[i].startsWith("@@")) {
                    Matcher m = HUNK.matcher(lines[i]);
                    if (!m.matches()) throw new PatchException("malformed hunk header: " + lines[i]);
                    int oldStart = Integer.parseInt(m.group(1));
                    int oldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
                    i++;
                    List<String> body = new ArrayList<>();
                    while (i < lines.length) {
                        String l = lines[i];
                        if (l.startsWith("@@") || l.startsWith("--- ") && i + 1 < lines.length && lines[i + 1].startsWith("+++ ")
                                || l.startsWith("diff --git ")) break;
                        if (l.startsWith("\\")) {
                            if (!body.isEmpty()) body.set(body.size() - 1, body.get(body.size() - 1) + NO_NL);
                            i++;
                            continue;
                        }
                        if (l.isEmpty()) {
                            if (i == lines.length - 1) break;           // trailing newline of the whole diff
                            l = " ";                                    // models often drop the space of a blank context line
                        }
                        char c = l.charAt(0);
                        if (c != ' ' && c != '-' && c != '+') break;
                        body.add(l);
                        i++;
                    }
                    if (body.isEmpty()) throw new PatchException("empty hunk at " + lines[i - 1]);
                    hunks.add(new Hunk(oldCount == 0 ? oldStart + 1 : oldStart, body));
                }
                if (hunks.isEmpty()) throw new PatchException("no hunks for " + newPath);
                files.add(new FileDiff(newPath, hunks));
                oldPath = null;
            } else {
                i++;                                                     // prose around a diff, commit message, etc.
            }
        }
        if (files.isEmpty()) throw new PatchException("no file changes found in the diff");
        return files;
    }

    private static String stripPrefix(String p) {
        String s = p.trim();
        int tab = s.indexOf('\t');
        if (tab >= 0) s = s.substring(0, tab);
        if (s.startsWith("a/") || s.startsWith("b/")) return s.substring(2);
        return s;
    }

    // --------------------------------------------------------------------------------------------------- apply

    /** Applies the hunks of one file to {@code original}. Context and removed lines must match exactly. */
    public static String apply(String original, FileDiff fd) throws PatchException {
        List<String> src = lines(original);
        List<String> result = new ArrayList<>();
        int cursor = 0;                                              // next unread index in src
        for (Hunk h : fd.hunks()) {
            List<String> expected = new ArrayList<>(), replacement = new ArrayList<>();
            for (String l : h.lines()) {
                String text = l.substring(1);
                if (l.charAt(0) != '+') expected.add(text);
                if (l.charAt(0) != '-') replacement.add(text);
            }
            int want = Math.max(0, h.oldStart() - 1);
            int at = locate(src, expected, want, cursor);
            if (at < 0) {
                throw new PatchException("hunk at line " + h.oldStart() + " of " + fd.path() + " does not apply: the file has no "
                        + "lines matching\n" + String.join("\n", expected.subList(0, Math.min(expected.size(), 6)))
                        + (expected.size() > 6 ? "\n..." : "") + "\nnear that position. Context lines must be copied exactly from the file.");
            }
            result.addAll(src.subList(cursor, at));
            result.addAll(replacement);
            cursor = at + expected.size();
        }
        result.addAll(src.subList(cursor, src.size()));
        if (result.isEmpty()) return "";
        String last = result.get(result.size() - 1);
        boolean noNewline = last.endsWith(NO_NL);
        if (noNewline) result.set(result.size() - 1, last.substring(0, last.length() - 1));
        return String.join("\n", result) + (noNewline ? "" : "\n");
    }

    private static int locate(List<String> src, List<String> expected, int want, int minStart) {
        if (matches(src, expected, want) && want >= minStart) return want;
        for (int off = 1; off <= MAX_OFFSET; off++) {
            if (want - off >= minStart && matches(src, expected, want - off)) return want - off;
            if (matches(src, expected, want + off)) return want + off;
        }
        return -1;
    }

    private static boolean matches(List<String> src, List<String> expected, int at) {
        if (at < 0 || at + expected.size() > src.size()) return false;
        for (int i = 0; i < expected.size(); i++) if (!src.get(at + i).equals(expected.get(i))) return false;
        return true;
    }

    /** Applies a (possibly multi-file) diff to the tree under {@code root}, in place. All-or-nothing. */
    public static List<String> applyTo(Path root, String diff) throws PatchException, IOException {
        List<FileDiff> files = parse(diff);
        Map<Path, String> updated = new LinkedHashMap<>();
        Path normRoot = root.toAbsolutePath().normalize();
        for (FileDiff fd : files) {
            if (fd.path().startsWith("/") || fd.path().contains("..") || fd.path().startsWith(".git/")) {
                throw new PatchException("refusing to touch path '" + fd.path() + "'");
            }
            Path target = normRoot.resolve(fd.path()).normalize();
            if (!target.startsWith(normRoot)) throw new PatchException("path escapes the repository: " + fd.path());
            if (!Files.isRegularFile(target)) throw new PatchException("no such file: " + fd.path());
            updated.put(target, apply(Files.readString(target, StandardCharsets.UTF_8), fd));
        }
        for (Map.Entry<Path, String> e : updated.entrySet()) Files.writeString(e.getKey(), e.getValue(), StandardCharsets.UTF_8);
        List<String> paths = new ArrayList<>();
        for (FileDiff fd : files) paths.add(fd.path());
        return paths;
    }

    /** {added, removed} line counts of a diff. */
    public static int[] stats(String diff) {
        int add = 0, del = 0;
        for (String l : diff.split("\n")) {
            if (l.startsWith("+++") || l.startsWith("---")) continue;
            if (l.startsWith("+")) add++;
            else if (l.startsWith("-")) del++;
        }
        return new int[] {add, del};
    }
}
