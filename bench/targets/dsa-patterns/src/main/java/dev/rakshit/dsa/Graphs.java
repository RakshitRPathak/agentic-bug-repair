package dev.rakshit.dsa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;

/**
 * PATTERN: graphs (pick the traversal from the question being asked).
 *
 * <table>
 *   <caption>Which algorithm?</caption>
 *   <tr><td>Fewest steps, unweighted</td><td>BFS</td></tr>
 *   <tr><td>Cheapest path, non-negative weights</td><td>Dijkstra (heap)</td></tr>
 *   <tr><td>Order tasks with prerequisites, or detect a cycle</td><td>Topological sort (Kahn)</td></tr>
 *   <tr><td>Are these connected? Merge groups over time</td><td>Union-Find</td></tr>
 *   <tr><td>Count or explore regions in a grid</td><td>DFS/BFS flood fill</td></tr>
 * </table>
 *
 * <p>Graphs here are adjacency lists: {@code List<List<int[]>>} for weighted ({@code {to, weight}}) or
 * edge arrays that are converted at the top of each method.
 */
public final class Graphs {
    private Graphs() {}

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * Union-Find (disjoint set union) with path compression and union by size: near-constant time per operation
     * (inverse-Ackermann). Finding the root also flattens the path so later finds are shorter.
     */
    public static final class UnionFind {
        private final int[] parent;
        private final int[] size;
        private int components;

        public UnionFind(int n) {
            parent = new int[n];
            size = new int[n];
            components = n;
            for (int i = 0; i < n; i++) {
                parent[i] = i;
                size[i] = 1;
            }
        }

        public int find(int x) {
            while (parent[x] != x) {
                parent[x] = parent[parent[x]];       // path halving
                x = parent[x];
            }
            return x;
        }

        /** Joins the sets of a and b; false if they were already together (which is how cycles are detected). */
        public boolean union(int a, int b) {
            int ra = find(a), rb = find(b);
            if (ra == rb) return false;
            if (size[ra] < size[rb]) {
                int t = ra;
                ra = rb;
                rb = t;
            }
            parent[rb] = ra;
            size[ra] += size[rb];
            components--;
            return true;
        }

        public boolean connected(int a, int b) {
            return find(a) == find(b);
        }

        public int components() {
            return components;
        }
    }

    /** Number of connected components of an undirected graph with n nodes labelled 0..n-1. */
    public static int countComponents(int n, int[][] edges) {
        UnionFind uf = new UnionFind(n);
        for (int[] e : edges) uf.union(e[0], e[1]);
        return uf.components();
    }

    /** Number of islands: 4-directionally connected groups of '1' in a grid. Flood fill with an explicit stack (no deep recursion). */
    public static int numIslands(char[][] grid) {
        if (grid.length == 0) return 0;
        int rows = grid.length, cols = grid[0].length;
        boolean[][] seen = new boolean[rows][cols];
        int count = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (grid[r][c] != '1' || seen[r][c]) continue;
                count++;
                ArrayDeque<int[]> stack = new ArrayDeque<>();
                stack.push(new int[] {r, c});
                seen[r][c] = true;
                while (!stack.isEmpty()) {
                    int[] cell = stack.pop();
                    for (int[] d : DIRS) {
                        int nr = cell[0] + d[0], nc = cell[1] + d[1];
                        if (nr >= 0 && nr < rows && nc >= 0 && nc < cols && grid[nr][nc] == '1' && !seen[nr][nc]) {
                            seen[nr][nc] = true;               // mark when pushing, not when popping, or cells get queued many times
                            stack.push(new int[] {nr, nc});
                        }
                    }
                }
            }
        }
        return count;
    }

    /**
     * Minutes until no fresh orange (1) remains, given rotten oranges (2) infect 4-neighbours each minute; 0 is empty.
     * MULTI-SOURCE BFS: start with every rotten orange in the queue at once, and each BFS layer is one minute.
     * Returns -1 if some fresh orange can never be reached.
     */
    public static int orangesRotting(int[][] grid) {
        int rows = grid.length, cols = rows == 0 ? 0 : grid[0].length;
        int[][] g = new int[rows][];
        for (int r = 0; r < rows; r++) g[r] = grid[r].clone();   // do not mutate the caller's grid
        Queue<int[]> queue = new ArrayDeque<>();
        int fresh = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (g[r][c] == 2) queue.add(new int[] {r, c});
                else if (g[r][c] == 1) fresh++;
            }
        }
        int minutes = 0;
        while (!queue.isEmpty() && fresh > 0) {
            minutes++;
            for (int i = queue.size(); i > 0; i--) {
                int[] cell = queue.poll();
                for (int[] d : DIRS) {
                    int nr = cell[0] + d[0], nc = cell[1] + d[1];
                    if (nr >= 0 && nr < rows && nc >= 0 && nc < cols && g[nr][nc] == 1) {
                        g[nr][nc] = 2;
                        fresh--;
                        queue.add(new int[] {nr, nc});
                    }
                }
            }
        }
        return fresh == 0 ? minutes : -1;
    }

    /**
     * Topological order of courses 0..n-1 where {@code prereqs[i] = {course, mustTakeFirst}} (Kahn's algorithm:
     * repeatedly take a node with in-degree 0). If fewer than n nodes come out, there is a cycle: returns an empty array.
     */
    public static int[] topologicalOrder(int n, int[][] prereqs) {
        List<List<Integer>> next = new ArrayList<>();
        for (int i = 0; i < n; i++) next.add(new ArrayList<>());
        int[] indegree = new int[n];
        for (int[] p : prereqs) {
            next.get(p[1]).add(p[0]);
            indegree[p[0]]++;
        }
        Queue<Integer> ready = new ArrayDeque<>();
        for (int i = 0; i < n; i++) if (indegree[i] == 0) ready.add(i);
        int[] order = new int[n];
        int taken = 0;
        while (!ready.isEmpty()) {
            int u = ready.poll();
            order[taken++] = u;
            for (int v : next.get(u)) if (--indegree[v] == 0) ready.add(v);
        }
        return taken == n ? order : new int[0];
    }

    /**
     * Shortest distances from {@code source} to every node in a directed graph with non-negative weights
     * ({@code edges[i] = {from, to, weight}}). Unreachable nodes get {@link Long#MAX_VALUE}.
     * Greedy: the unsettled node with the smallest tentative distance is final, because no later path can be shorter
     * (this is exactly why negative weights break it). A stale heap entry (a longer distance already improved on) is skipped.
     */
    public static long[] dijkstra(int n, int[][] edges, int source) {
        List<List<int[]>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (int[] e : edges) {
            if (e[2] < 0) throw new IllegalArgumentException("negative weight: " + Arrays.toString(e));
            adj.get(e[0]).add(new int[] {e[1], e[2]});
        }
        long[] dist = new long[n];
        Arrays.fill(dist, Long.MAX_VALUE);
        dist[source] = 0;
        PriorityQueue<long[]> heap = new PriorityQueue<>((a, b) -> Long.compare(a[1], b[1]));
        heap.add(new long[] {source, 0});
        while (!heap.isEmpty()) {
            long[] top = heap.poll();
            int u = (int) top[0];
            if (top[1] > dist[u]) continue;           // stale entry
            for (int[] edge : adj.get(u)) {
                long candidate = dist[u] + edge[1];
                if (candidate < dist[edge[0]]) {
                    dist[edge[0]] = candidate;
                    heap.add(new long[] {edge[0], candidate});
                }
            }
        }
        return dist;
    }

    /** Fewest edges from {@code source} to {@code target} in an unweighted undirected graph, or -1. Plain BFS. */
    public static int shortestPathLength(int n, int[][] edges, int source, int target) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (int[] e : edges) {
            adj.get(e[0]).add(e[1]);
            adj.get(e[1]).add(e[0]);
        }
        int[] dist = new int[n];
        Arrays.fill(dist, -1);
        dist[source] = 0;
        Queue<Integer> queue = new ArrayDeque<>();
        queue.add(source);
        while (!queue.isEmpty()) {
            int u = queue.poll();
            if (u == target) return dist[u];
            for (int v : adj.get(u)) {
                if (dist[v] < 0) {
                    dist[v] = dist[u] + 1;
                    queue.add(v);
                }
            }
        }
        return -1;
    }
}
