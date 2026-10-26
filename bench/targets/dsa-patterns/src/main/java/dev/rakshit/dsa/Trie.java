package dev.rakshit.dsa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PATTERN: trie (prefix tree) for "does anything start with this?" and autocomplete.
 *
 * <p><b>Recognise it when:</b> many strings are queried by prefix. A hash set answers "is this exact word present" but
 * cannot answer prefix questions without scanning every word; a trie answers both in O(length of the query),
 * independent of how many words are stored.
 *
 * <p>Children are stored in a map, so any characters (not just a-z) work.
 */
public final class Trie {
    private static final class Node {
        final Map<Character, Node> children = new HashMap<>();
        boolean isWord;
    }

    private final Node root = new Node();
    private int words;

    /** Adds a word; returns false if it was already present. */
    public boolean insert(String word) {
        Node n = root;
        for (int i = 0; i < word.length(); i++) n = n.children.computeIfAbsent(word.charAt(i), ch -> new Node());
        if (n.isWord) return false;
        n.isWord = true;
        words++;
        return true;
    }

    public boolean contains(String word) {
        Node n = find(word);
        return n != null && n.isWord;
    }

    /**
     * True if some stored word begins with {@code prefix}. Nodes only exist because a word passes through them, so a
     * non-root node always qualifies; the root (empty prefix) qualifies only once at least one word is stored.
     */
    public boolean startsWith(String prefix) {
        Node n = find(prefix);
        return n != null && (n.isWord || !n.children.isEmpty());
    }

    /** Up to {@code limit} stored words beginning with {@code prefix}, in alphabetical order (autocomplete). */
    public List<String> autocomplete(String prefix, int limit) {
        List<String> out = new ArrayList<>();
        Node start = find(prefix);
        if (start != null && limit > 0) collect(start, new StringBuilder(prefix), out, limit);
        return out;
    }

    private void collect(Node n, StringBuilder path, List<String> out, int limit) {
        if (out.size() >= limit) return;
        if (n.isWord) out.add(path.toString());
        List<Character> keys = new ArrayList<>(n.children.keySet());
        keys.sort(null);                                        // alphabetical, so results are deterministic
        for (char ch : keys) {
            if (out.size() >= limit) return;
            path.append(ch);
            collect(n.children.get(ch), path, out, limit);
            path.deleteCharAt(path.length() - 1);
        }
    }

    /** Number of distinct words stored. */
    public int size() {
        return words;
    }

    private Node find(String s) {
        Node n = root;
        for (int i = 0; i < s.length() && n != null; i++) n = n.children.get(s.charAt(i));
        return n;
    }
}
