package dev.rakshit.dsa;

import static dev.rakshit.dsa.testkit.Assert.assertEquals;
import static dev.rakshit.dsa.testkit.Assert.assertFalse;
import static dev.rakshit.dsa.testkit.Assert.assertThrows;
import static dev.rakshit.dsa.testkit.Assert.assertTrue;

import dev.rakshit.dsa.testkit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/** Graphs, backtracking and tries. */
public class SearchAndGraphTests {
    private static final int TRIALS = 1000;

    // ---- union-find and components -------------------------------------------------------------------------

    @Test
    void unionFindMatchesFloodFillOnRandomGraphs() {
        Random rnd = new Random(31);
        for (int t = 0; t < TRIALS; t++) {
            int n = 1 + rnd.nextInt(10);
            int[][] edges = new int[rnd.nextInt(10)][];
            for (int i = 0; i < edges.length; i++) edges[i] = new int[] {rnd.nextInt(n), rnd.nextInt(n)};
            boolean[][] reach = closure(n, edges);
            Graphs.UnionFind uf = new Graphs.UnionFind(n);
            for (int[] e : edges) uf.union(e[0], e[1]);
            int components = 0;
            for (int i = 0; i < n; i++) {
                boolean root = true;
                for (int j = 0; j < i; j++) if (reach[i][j]) root = false;
                if (root) components++;
                for (int j = 0; j < n; j++) assertEquals(reach[i][j], uf.connected(i, j));
            }
            assertEquals(components, uf.components());
            assertEquals(components, Graphs.countComponents(n, edges));
        }
    }

    /** Warshall transitive closure of an undirected graph: the slow, obviously-right oracle. */
    private static boolean[][] closure(int n, int[][] edges) {
        boolean[][] r = new boolean[n][n];
        for (int i = 0; i < n; i++) r[i][i] = true;
        for (int[] e : edges) r[e[0]][e[1]] = r[e[1]][e[0]] = true;
        for (int k = 0; k < n; k++) for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) if (r[i][k] && r[k][j]) r[i][j] = true;
        return r;
    }

    @Test
    void unionReportsRedundantEdgesWhichIsHowCyclesAreDetected() {
        Graphs.UnionFind uf = new Graphs.UnionFind(4);
        assertTrue(uf.union(0, 1));
        assertTrue(uf.union(1, 2));
        assertFalse(uf.union(0, 2));            // 0 and 2 already connected: this edge would close a cycle
        assertEquals(2, uf.components());
    }

    // ---- grids ---------------------------------------------------------------------------------------------

    @Test
    void numIslandsMatchesRecursiveFloodFillAndDoesNotMutateInput() {
        Random rnd = new Random(32);
        for (int t = 0; t < TRIALS; t++) {
            int rows = 1 + rnd.nextInt(6), cols = 1 + rnd.nextInt(6);
            char[][] g = new char[rows][cols];
            for (char[] row : g) for (int c = 0; c < cols; c++) row[c] = rnd.nextInt(3) == 0 ? '0' : '1';
            char[][] copy = new char[rows][];
            for (int r = 0; r < rows; r++) copy[r] = g[r].clone();
            char[][] scratch = new char[rows][];
            for (int r = 0; r < rows; r++) scratch[r] = g[r].clone();
            int expected = 0;
            for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) if (scratch[r][c] == '1') { expected++; sink(scratch, r, c); }
            assertEquals(expected, Graphs.numIslands(g));
            assertEquals(Arrays.deepToString(copy), Arrays.deepToString(g));
        }
        assertEquals(0, Graphs.numIslands(new char[0][]));
    }

    private static void sink(char[][] g, int r, int c) {
        if (r < 0 || c < 0 || r >= g.length || c >= g[0].length || g[r][c] != '1') return;
        g[r][c] = '0';
        sink(g, r + 1, c);
        sink(g, r - 1, c);
        sink(g, r, c + 1);
        sink(g, r, c - 1);
    }

    @Test
    void orangesRottingMatchesMinuteBySimulation() {
        Random rnd = new Random(33);
        for (int t = 0; t < TRIALS; t++) {
            int rows = 1 + rnd.nextInt(5), cols = 1 + rnd.nextInt(5);
            int[][] g = new int[rows][cols];
            for (int[] row : g) for (int c = 0; c < cols; c++) row[c] = rnd.nextInt(3);
            int[][] before = new int[rows][];
            for (int r = 0; r < rows; r++) before[r] = g[r].clone();
            // Simulation: each minute, every fresh orange next to a rotten one rots.
            int[][] s = new int[rows][];
            for (int r = 0; r < rows; r++) s[r] = g[r].clone();
            int minutes = 0;
            while (true) {
                List<int[]> toRot = new ArrayList<>();
                for (int r = 0; r < rows; r++)
                    for (int c = 0; c < cols; c++)
                        if (s[r][c] == 1 && (at(s, r + 1, c) == 2 || at(s, r - 1, c) == 2 || at(s, r, c + 1) == 2 || at(s, r, c - 1) == 2)) toRot.add(new int[] {r, c});
                if (toRot.isEmpty()) break;
                for (int[] p : toRot) s[p[0]][p[1]] = 2;
                minutes++;
            }
            boolean freshLeft = Arrays.stream(s).flatMapToInt(Arrays::stream).anyMatch(v -> v == 1);
            assertEquals(freshLeft ? -1 : minutes, Graphs.orangesRotting(g));
            assertEquals(Arrays.deepToString(before), Arrays.deepToString(g));
        }
    }

    private static int at(int[][] g, int r, int c) {
        return r < 0 || c < 0 || r >= g.length || c >= g[0].length ? 0 : g[r][c];
    }

    // ---- ordering and shortest paths -----------------------------------------------------------------------

    @Test
    void topologicalOrderIsValidIffTheGraphIsAcyclic() {
        Random rnd = new Random(34);
        for (int t = 0; t < TRIALS; t++) {
            int n = 1 + rnd.nextInt(7);
            int[][] pre = new int[rnd.nextInt(9)][];
            for (int i = 0; i < pre.length; i++) pre[i] = new int[] {rnd.nextInt(n), rnd.nextInt(n)};   // {course, mustTakeFirst}
            boolean acyclic = isAcyclicByPermutation(n, pre);
            int[] order = Graphs.topologicalOrder(n, pre);
            if (!acyclic) assertEquals(0, order.length);
            else {
                assertEquals(n, order.length);
                int[] pos = new int[n];
                for (int i = 0; i < n; i++) pos[order[i]] = i;
                for (int[] p : pre) assertTrue(pos[p[1]] < pos[p[0]], "prerequisite order violated");
            }
        }
    }

    /** Oracle: acyclic iff some ordering of all nodes respects every edge. Tries every permutation (n is tiny). */
    private static boolean isAcyclicByPermutation(int n, int[][] pre) {
        return permute(new int[n], new boolean[n], 0, pre);
    }

    private static boolean permute(int[] order, boolean[] used, int k, int[][] pre) {
        if (k == order.length) {
            int[] pos = new int[order.length];
            for (int i = 0; i < order.length; i++) pos[order[i]] = i;
            for (int[] p : pre) if (pos[p[1]] >= pos[p[0]]) return false;
            return true;
        }
        for (int v = 0; v < order.length; v++) {
            if (used[v]) continue;
            used[v] = true;
            order[k] = v;
            if (permute(order, used, k + 1, pre)) return true;
            used[v] = false;
        }
        return false;
    }

    @Test
    void dijkstraMatchesBellmanFordOnRandomGraphs() {
        Random rnd = new Random(35);
        for (int t = 0; t < TRIALS; t++) {
            int n = 1 + rnd.nextInt(8);
            int[][] edges = new int[rnd.nextInt(16)][];
            for (int i = 0; i < edges.length; i++) edges[i] = new int[] {rnd.nextInt(n), rnd.nextInt(n), rnd.nextInt(10)};
            int source = rnd.nextInt(n);
            long[] want = new long[n];
            Arrays.fill(want, Long.MAX_VALUE);
            want[source] = 0;
            for (int round = 0; round < n; round++)
                for (int[] e : edges) if (want[e[0]] != Long.MAX_VALUE && want[e[0]] + e[2] < want[e[1]]) want[e[1]] = want[e[0]] + e[2];
            assertEquals(Arrays.toString(want), Arrays.toString(Graphs.dijkstra(n, edges, source)));
        }
        assertThrows(IllegalArgumentException.class, () -> Graphs.dijkstra(2, new int[][] {{0, 1, -1}}, 0));
    }

    @Test
    void dijkstraBeatsTheGreedyLookingDirectEdge() {
        // 0 -> 2 directly costs 10, but 0 -> 1 -> 2 costs 3.
        long[] d = Graphs.dijkstra(3, new int[][] {{0, 2, 10}, {0, 1, 1}, {1, 2, 2}}, 0);
        assertEquals(3L, d[2]);
    }

    @Test
    void bfsShortestPathMatchesFloydWarshall() {
        Random rnd = new Random(36);
        for (int t = 0; t < TRIALS; t++) {
            int n = 1 + rnd.nextInt(8);
            int[][] edges = new int[rnd.nextInt(10)][];
            for (int i = 0; i < edges.length; i++) edges[i] = new int[] {rnd.nextInt(n), rnd.nextInt(n)};
            int inf = 1000;
            int[][] d = new int[n][n];
            for (int[] row : d) Arrays.fill(row, inf);
            for (int i = 0; i < n; i++) d[i][i] = 0;
            for (int[] e : edges) d[e[0]][e[1]] = d[e[1]][e[0]] = Math.min(d[e[0]][e[1]], e[0] == e[1] ? 0 : 1);
            for (int k = 0; k < n; k++) for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) d[i][j] = Math.min(d[i][j], d[i][k] + d[k][j]);
            int s = rnd.nextInt(n), target = rnd.nextInt(n);
            assertEquals(d[s][target] >= inf ? -1 : d[s][target], Graphs.shortestPathLength(n, edges, s, target));
        }
    }

    // ---- backtracking --------------------------------------------------------------------------------------

    @Test
    void subsetsAreAllDistinctAndCountTwoToTheN() {
        for (int n = 0; n <= 10; n++) {
            int[] nums = new int[n];
            for (int i = 0; i < n; i++) nums[i] = i;
            List<List<Integer>> got = Backtracking.subsets(nums);
            assertEquals(1 << n, got.size());
            assertEquals(1 << n, new HashSet<>(got).size());
        }
    }

    @Test
    void uniquePermutationsMatchTheDistinctPermutationCountFormula() {
        Random rnd = new Random(37);
        for (int t = 0; t < 300; t++) {
            int[] a = Gen.ints(rnd, 6, 0, 2);
            List<List<Integer>> got = Backtracking.permutationsUnique(a);
            long expected = factorial(a.length);
            int[] counts = new int[3];
            for (int x : a) counts[x]++;
            for (int c : counts) expected /= factorial(c);       // n! / (c0! c1! c2!) distinct arrangements
            assertEquals((int) expected, got.size());
            assertEquals(got.size(), new HashSet<>(got).size());   // and no duplicates
        }
    }

    private static long factorial(int n) {
        long f = 1;
        for (int i = 2; i <= n; i++) f *= i;
        return f;
    }

    @Test
    void combinationSumMatchesBruteForceOverMultisets() {
        Random rnd = new Random(38);
        for (int t = 0; t < 300; t++) {
            int[] c = Arrays.stream(Gen.ints(rnd, 4, 1, 6)).distinct().toArray();
            int target = 1 + rnd.nextInt(12);
            Set<List<Integer>> expected = new HashSet<>();
            enumerate(c, target, 0, new ArrayList<>(), expected);
            List<List<Integer>> got = Backtracking.combinationSum(c, target);
            assertEquals(expected.size(), got.size());
            Set<List<Integer>> gotSet = new HashSet<>();
            for (List<Integer> g : got) {
                List<Integer> sorted = new ArrayList<>(g);
                sorted.sort(null);
                gotSet.add(sorted);
            }
            assertEquals(expected, gotSet);
        }
    }

    private static void enumerate(int[] c, int remaining, int idx, List<Integer> cur, Set<List<Integer>> out) {
        if (remaining == 0) {
            List<Integer> s = new ArrayList<>(cur);
            s.sort(null);
            out.add(s);
            return;
        }
        if (idx == c.length || remaining < 0) return;
        enumerate(c, remaining, idx + 1, cur, out);                 // skip this value entirely
        cur.add(c[idx]);
        enumerate(c, remaining - c[idx], idx, cur, out);            // use it once more (may repeat)
        cur.remove(cur.size() - 1);
    }

    @Test
    void nQueensMatchesTheKnownSolutionCounts() {
        int[] known = {1, 1, 0, 0, 2, 10, 4, 40, 92, 352};          // OEIS A000170 for n = 0..9
        for (int n = 0; n < known.length; n++) assertEquals(known[n], Backtracking.nQueens(n));
    }

    @Test
    void wordSearchFindsPathsWithoutReusingCellsAndRestoresTheBoard() {
        char[][] board = {{'A', 'B', 'C', 'E'}, {'S', 'F', 'C', 'S'}, {'A', 'D', 'E', 'E'}};
        String before = Arrays.deepToString(board);
        assertTrue(Backtracking.wordSearch(board, "ABCCED"));
        assertTrue(Backtracking.wordSearch(board, "SEE"));
        assertFalse(Backtracking.wordSearch(board, "ABCB"));         // would need the same B twice
        assertEquals(before, Arrays.deepToString(board));            // visited marks were undone
        assertTrue(Backtracking.wordSearch(board, ""));
    }

    // ---- trie ----------------------------------------------------------------------------------------------

    @Test
    void trieMatchesASortedSetOnRandomWords() {
        Random rnd = new Random(39);
        for (int t = 0; t < 400; t++) {
            Trie trie = new Trie();
            TreeSet<String> model = new TreeSet<>();
            for (int i = rnd.nextInt(15); i > 0; i--) {
                String w = Gen.string(rnd, 5, 3);
                assertEquals(model.add(w), trie.insert(w));
            }
            assertEquals(model.size(), trie.size());
            for (int q = 0; q < 20; q++) {
                String s = Gen.string(rnd, 5, 3);
                assertEquals(model.contains(s), trie.contains(s));
                boolean anyPrefix = model.stream().anyMatch(w -> w.startsWith(s));
                assertEquals(anyPrefix, trie.startsWith(s));
                List<String> expected = new ArrayList<>();
                for (String w : model) if (w.startsWith(s) && expected.size() < 3) expected.add(w);
                assertEquals(expected, trie.autocomplete(s, 3));
            }
        }
    }

    @Test
    void trieEmptyStringBehaviour() {
        Trie trie = new Trie();
        assertFalse(trie.startsWith(""));                // nothing stored, so no word begins with anything
        assertFalse(trie.contains(""));
        assertTrue(trie.insert(""));
        assertTrue(trie.contains(""));
        assertTrue(trie.startsWith(""));
        assertEquals(List.of(), trie.autocomplete("x", 5));
        assertEquals(List.of(), trie.autocomplete("", 0));
    }
}
