package dev.rakshit.repair.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The code property graph of a repository: nodes are classes, methods and fields; edges are CONTAINS, CALLS, IMPORTS,
 * INHERITS and MODIFIES. In memory, with adjacency lists in both directions so that "who calls this" and "what does this
 * call" are both O(degree).
 */
public final class CodeGraph {
    public enum Direction { OUT, IN, BOTH }

    private final Map<String, CodeNode> nodes = new LinkedHashMap<>();
    private final Map<String, List<CodeEdge>> out = new LinkedHashMap<>();
    private final Map<String, List<CodeEdge>> in = new LinkedHashMap<>();
    private final Set<CodeEdge> edgeSet = new HashSet<>();
    private final List<String> compileErrors = new ArrayList<>();

    public void addNode(CodeNode n) {
        nodes.putIfAbsent(n.id(), n);
    }

    /** Adds an edge between two existing nodes; duplicates and self-loops on CALLS are ignored. Returns whether it was added. */
    public boolean addEdge(String from, EdgeKind kind, String to) {
        if (!nodes.containsKey(from) || !nodes.containsKey(to)) return false;
        if (from.equals(to) && kind != EdgeKind.CALLS) return false;
        CodeEdge e = new CodeEdge(from, kind, to);
        if (!edgeSet.add(e)) return false;
        out.computeIfAbsent(from, k -> new ArrayList<>()).add(e);
        in.computeIfAbsent(to, k -> new ArrayList<>()).add(e);
        return true;
    }

    public CodeNode node(String id) {
        return nodes.get(id);
    }

    public boolean contains(String id) {
        return nodes.containsKey(id);
    }

    public Collection<CodeNode> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edgeSet.size();
    }

    public int edgeCount(EdgeKind k) {
        int c = 0;
        for (CodeEdge e : edgeSet) if (e.kind() == k) c++;
        return c;
    }

    public List<CodeEdge> edgesFrom(String id) {
        return out.getOrDefault(id, List.of());
    }

    public List<CodeEdge> edgesTo(String id) {
        return in.getOrDefault(id, List.of());
    }

    public List<String> compileErrors() {
        return compileErrors;
    }

    public List<CodeNode> methods() {
        List<CodeNode> r = new ArrayList<>();
        for (CodeNode n : nodes.values()) if (n.kind() == NodeKind.METHOD) r.add(n);
        return r;
    }

    public List<String> callees(String id) {
        return neighbours(id, EdgeKind.CALLS, Direction.OUT);
    }

    public List<String> callers(String id) {
        return neighbours(id, EdgeKind.CALLS, Direction.IN);
    }

    public List<String> neighbours(String id, EdgeKind kind, Direction dir) {
        List<String> r = new ArrayList<>();
        if (dir != Direction.IN) for (CodeEdge e : edgesFrom(id)) if (e.kind() == kind) r.add(e.to());
        if (dir != Direction.OUT) for (CodeEdge e : edgesTo(id)) if (e.kind() == kind) r.add(e.from());
        return r;
    }

    /**
     * Breadth-first traversal from {@code start} over edges of the given kinds, up to {@code hops} edges away.
     * Returns node id to hop distance (the start itself at 0). This is the "2-hop graph traversal" of GraphRAG.
     */
    public Map<String, Integer> traverse(String start, int hops, Set<EdgeKind> kinds, Direction dir) {
        Map<String, Integer> dist = new LinkedHashMap<>();
        if (!nodes.containsKey(start)) return dist;
        dist.put(start, 0);
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String u = queue.poll();
            int d = dist.get(u);
            if (d == hops) continue;
            List<CodeEdge> around = new ArrayList<>();
            if (dir != Direction.IN) around.addAll(edgesFrom(u));
            if (dir != Direction.OUT) around.addAll(edgesTo(u));
            for (CodeEdge e : around) {
                if (!kinds.contains(e.kind())) continue;
                String v = e.from().equals(u) ? e.to() : e.from();
                if (dist.putIfAbsent(v, d + 1) == null) queue.add(v);
            }
        }
        return dist;
    }

    /** Everything {@code start} can reach by following CALLS edges, with the length of the shortest call chain. */
    public Map<String, Integer> reachableByCalls(String start, int maxDepth) {
        return traverse(start, maxDepth, Set.of(EdgeKind.CALLS), Direction.OUT);
    }

    /** The innermost method whose source range contains {@code line} of {@code file} (relative path), or null. */
    public CodeNode methodAt(String file, int line) {
        CodeNode best = null;
        for (CodeNode n : nodes.values()) {
            if (n.kind() != NodeKind.METHOD || !n.file().equals(file)) continue;
            if (line < n.startLine() || line > n.endLine()) continue;
            if (best == null || (n.endLine() - n.startLine()) < (best.endLine() - best.startLine())) best = n;
        }
        return best;
    }

    /** Looks a method up by class simple-or-qualified name and method name; returns all matches (overloads). */
    public List<CodeNode> findMethods(String className, String methodName) {
        List<CodeNode> r = new ArrayList<>();
        for (CodeNode n : nodes.values()) {
            if (n.kind() != NodeKind.METHOD || !n.name().equals(methodName)) continue;
            if (n.className().equals(className) || n.className().endsWith("." + className)) r.add(n);
        }
        return r;
    }

    /**
     * What a change to {@code id} can affect. {@code upstream}: methods that (transitively) call it, within
     * {@code maxDepth} hops. {@code downstream}: what it calls. {@code tests}: upstream methods that are tests.
     */
    public record BlastRadius(String id, Map<String, Integer> upstream, Map<String, Integer> downstream, List<String> tests) {}

    public BlastRadius blastRadius(String id, int maxDepth) {
        Map<String, Integer> up = traverse(id, maxDepth, Set.of(EdgeKind.CALLS), Direction.IN);
        Map<String, Integer> down = traverse(id, maxDepth, Set.of(EdgeKind.CALLS), Direction.OUT);
        up.remove(id);
        down.remove(id);
        List<String> tests = new ArrayList<>();
        for (String u : up.keySet()) if (nodes.get(u).isTest()) tests.add(u);
        return new BlastRadius(id, up, down, tests);
    }
}
