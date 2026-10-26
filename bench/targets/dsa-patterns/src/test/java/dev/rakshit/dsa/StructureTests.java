package dev.rakshit.dsa;

import static dev.rakshit.dsa.testkit.Assert.assertEquals;
import static dev.rakshit.dsa.testkit.Assert.assertFalse;
import static dev.rakshit.dsa.testkit.Assert.assertNull;
import static dev.rakshit.dsa.testkit.Assert.assertThrows;
import static dev.rakshit.dsa.testkit.Assert.assertTrue;

import dev.rakshit.dsa.testkit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Linked lists, trees, heaps and intervals. */
public class StructureTests {
    private static final int TRIALS = 1500;

    // ---- linked lists --------------------------------------------------------------------------------------

    @Test
    void reverseMiddleAndPalindromeMatchListOperations() {
        Random rnd = new Random(21);
        for (int t = 0; t < TRIALS; t++) {
            int[] a = Gen.ints(rnd, 9, 0, 3);
            List<Integer> asList = ListNode.toList(ListNode.of(a));

            List<Integer> reversed = new ArrayList<>(asList);
            Collections.reverse(reversed);
            assertEquals(reversed, ListNode.toList(LinkedLists.reverse(ListNode.of(a))));

            if (a.length > 0) assertEquals(asList.get(a.length / 2), LinkedLists.middle(ListNode.of(a)).val);

            ListNode list = ListNode.of(a);
            assertEquals(asList.equals(reversed), LinkedLists.isPalindrome(list));
            assertEquals(asList, ListNode.toList(list));           // isPalindrome must restore the list
        }
    }

    @Test
    void cycleDetectionFindsTheEntranceForEveryCycleShape() {
        assertFalse(LinkedLists.hasCycle(null));
        assertNull(LinkedLists.cycleStart(ListNode.of(1, 2, 3)));
        for (int n = 1; n <= 12; n++) {
            for (int entry = 0; entry < n; entry++) {
                ListNode head = ListNode.of(range(n));
                ListNode[] nodes = new ListNode[n];
                int i = 0;
                for (ListNode p = head; p != null; p = p.next) nodes[i++] = p;
                nodes[n - 1].next = nodes[entry];                    // tail points back to nodes[entry]
                assertTrue(LinkedLists.hasCycle(head), "n=" + n + " entry=" + entry);
                assertTrue(LinkedLists.cycleStart(head) == nodes[entry], "wrong entrance, n=" + n + " entry=" + entry);
            }
        }
    }

    private static int[] range(int n) {
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = i;
        return r;
    }

    @Test
    void mergeTwoSortedAndRemoveNthFromEnd() {
        Random rnd = new Random(22);
        for (int t = 0; t < TRIALS; t++) {
            int[] a = Gen.ints(rnd, 6, 0, 5), b = Gen.ints(rnd, 6, 0, 5);
            Arrays.sort(a);
            Arrays.sort(b);
            List<Integer> expected = new ArrayList<>();
            for (int x : a) expected.add(x);
            for (int x : b) expected.add(x);
            Collections.sort(expected);
            assertEquals(expected, ListNode.toList(LinkedLists.mergeTwoSorted(ListNode.of(a), ListNode.of(b))));

            int[] c = Gen.ints(rnd, 7, 0, 9);
            int n = 1 + rnd.nextInt(8);
            List<Integer> want = new ArrayList<>();
            for (int x : c) want.add(x);
            if (n <= c.length) want.remove(c.length - n);
            assertEquals(want, ListNode.toList(LinkedLists.removeNthFromEnd(ListNode.of(c), n)));
        }
    }

    // ---- trees ---------------------------------------------------------------------------------------------

    @Test
    void depthLevelOrderAndInorderOnAKnownTree() {
        TreeNode root = TreeNode.of(3, 9, 20, null, null, 15, 7);
        assertEquals(3, Trees.maxDepth(root));
        assertEquals(List.of(List.of(3), List.of(9, 20), List.of(15, 7)), Trees.levelOrder(root));
        assertEquals(List.of(9, 3, 15, 20, 7), Trees.inorder(root));
        assertEquals(0, Trees.maxDepth(null));
        assertEquals(new ArrayList<>(), Trees.levelOrder(null));
    }

    @Test
    void inorderSurvivesAVeryDeepSkewedTree() {
        TreeNode root = new TreeNode(0);
        TreeNode cur = root;
        for (int i = 1; i < 200_000; i++) {                       // would overflow the stack if done recursively
            cur.right = new TreeNode(i);
            cur = cur.right;
        }
        assertEquals(200_000, Trees.inorder(root).size());
    }

    @Test
    void bstValidationRejectsTheGrandchildTrap() {
        assertTrue(Trees.isValidBst(TreeNode.of(2, 1, 3)));
        assertFalse(Trees.isValidBst(TreeNode.of(5, 1, 4, null, null, 3, 6)));
        // Every parent/child pair is ordered, but 3 sits in the right subtree of 5 and is smaller than 5: invalid.
        assertFalse(Trees.isValidBst(TreeNode.of(5, 4, 6, null, null, 3, 7)));
        assertTrue(Trees.isValidBst(TreeNode.of(Integer.MIN_VALUE, null, Integer.MAX_VALUE)));
        assertFalse(Trees.isValidBst(TreeNode.of(2, 2, 2)));        // duplicates are not allowed
        assertFalse(Trees.isValidBst(TreeNode.of(2, null, 2)));     // equal value on the right is also invalid (checks the lower bound)
        assertFalse(Trees.isValidBst(TreeNode.of(2, 2)));           // ... and on the left (checks the upper bound)
        assertTrue(Trees.isValidBst(null));
    }

    @Test
    void lcaDiameterAndMaxPathSum() {
        TreeNode root = TreeNode.of(3, 5, 1, 6, 2, 0, 8, null, null, 7, 4);
        TreeNode five = root.left, one = root.right, four = root.left.right.right;
        assertTrue(Trees.lowestCommonAncestor(root, five, one) == root);
        assertTrue(Trees.lowestCommonAncestor(root, five, four) == five);    // a node can be its own ancestor
        assertEquals(5, Trees.diameter(root));                               // 7-2-5-3-1-8
        assertEquals(0, Trees.diameter(null));
        // The best path need not touch the root: 20 -> 15 -> 7 beats anything through -10.
        assertEquals(42L, Trees.maxPathSum(TreeNode.of(-10, 9, 20, null, null, 15, 7)));
        assertEquals(-3L, Trees.maxPathSum(TreeNode.of(-3)));                // all negative: best single node
        assertEquals(-1L, Trees.maxPathSum(TreeNode.of(-3, -1, -2)));
    }

    @Test
    void treeAnswersMatchBruteForceOnRandomTrees() {
        Random rnd = new Random(23);
        for (int t = 0; t < TRIALS; t++) {
            TreeNode root = randomTree(rnd, 1 + rnd.nextInt(10), -5, 5);
            List<TreeNode> nodes = new ArrayList<>();
            collect(root, nodes);
            // Diameter by trying every pair of nodes via parent maps.
            Map<TreeNode, TreeNode> parent = new HashMap<>();
            for (TreeNode n : nodes) {
                if (n.left != null) parent.put(n.left, n);
                if (n.right != null) parent.put(n.right, n);
            }
            int diameter = 0;
            for (TreeNode a : nodes) for (TreeNode b : nodes) diameter = Math.max(diameter, distance(a, b, parent));
            assertEquals(diameter, Trees.diameter(root));
            // LCA of two random nodes: the deepest node that is an ancestor of both.
            TreeNode p = nodes.get(rnd.nextInt(nodes.size())), q = nodes.get(rnd.nextInt(nodes.size()));
            assertTrue(Trees.lowestCommonAncestor(root, p, q) == bruteLca(p, q, parent), "lca mismatch");
            // Max path sum: best over every pair of endpoints (a path in a tree is determined by its two ends).
            long best = Long.MIN_VALUE;
            for (TreeNode a : nodes) for (TreeNode b : nodes) best = Math.max(best, pathSum(a, b, parent));
            assertEquals(best, Trees.maxPathSum(root));
        }
    }

    private static TreeNode randomTree(Random rnd, int size, int lo, int hi) {
        TreeNode root = new TreeNode(lo + rnd.nextInt(hi - lo + 1));
        List<TreeNode> open = new ArrayList<>(List.of(root));
        for (int i = 1; i < size; i++) {
            TreeNode host = open.get(rnd.nextInt(open.size()));
            TreeNode child = new TreeNode(lo + rnd.nextInt(hi - lo + 1));
            if (host.left == null && (host.right != null || rnd.nextBoolean())) host.left = child;
            else if (host.right == null) host.right = child;
            else host.left = child;
            open.add(child);
            open.removeIf(n -> n.left != null && n.right != null);
        }
        return root;
    }

    private static void collect(TreeNode n, List<TreeNode> out) {
        if (n == null) return;
        out.add(n);
        collect(n.left, out);
        collect(n.right, out);
    }

    private static List<TreeNode> ancestors(TreeNode n, Map<TreeNode, TreeNode> parent) {
        List<TreeNode> up = new ArrayList<>();
        for (TreeNode x = n; x != null; x = parent.get(x)) up.add(x);
        return up;
    }

    private static TreeNode bruteLca(TreeNode a, TreeNode b, Map<TreeNode, TreeNode> parent) {
        List<TreeNode> upB = ancestors(b, parent);
        for (TreeNode x : ancestors(a, parent)) if (upB.contains(x)) return x;
        throw new IllegalStateException();
    }

    private static int distance(TreeNode a, TreeNode b, Map<TreeNode, TreeNode> parent) {
        TreeNode lca = bruteLca(a, b, parent);
        return ancestors(a, parent).indexOf(lca) + ancestors(b, parent).indexOf(lca);
    }

    private static long pathSum(TreeNode a, TreeNode b, Map<TreeNode, TreeNode> parent) {
        TreeNode lca = bruteLca(a, b, parent);
        long sum = lca.val;
        for (TreeNode x = a; x != lca; x = parent.get(x)) sum += x.val;
        for (TreeNode x = b; x != lca; x = parent.get(x)) sum += x.val;
        return sum;
    }

    // ---- heaps ---------------------------------------------------------------------------------------------

    @Test
    void kthLargestAndTopKFrequentMatchSorting() {
        Random rnd = new Random(24);
        for (int t = 0; t < TRIALS; t++) {
            int[] a = Gen.ints(rnd, 12, -4, 4);
            if (a.length == 0) continue;
            int k = 1 + rnd.nextInt(a.length);
            int[] sorted = a.clone();
            Arrays.sort(sorted);
            assertEquals(sorted[sorted.length - k], Heaps.kthLargest(a, k));

            Map<Integer, Integer> freq = new HashMap<>();
            for (int x : a) freq.merge(x, 1, Integer::sum);
            List<Integer> keys = new ArrayList<>(freq.keySet());
            keys.sort((x, y) -> freq.get(x).equals(freq.get(y)) ? Integer.compare(x, y) : Integer.compare(freq.get(y), freq.get(x)));
            int kk = 1 + rnd.nextInt(keys.size());
            int[] expected = keys.subList(0, kk).stream().mapToInt(Integer::intValue).toArray();
            assertEquals(Arrays.toString(expected), Arrays.toString(Heaps.topKFrequent(a, kk)));
        }
        assertThrows(IllegalArgumentException.class, () -> Heaps.kthLargest(new int[] {1}, 2));
        assertThrows(IllegalArgumentException.class, () -> Heaps.kthLargest(new int[] {1}, 0));
    }

    @Test
    void mergeKListsMatchesSortingEverything() {
        Random rnd = new Random(25);
        for (int t = 0; t < TRIALS; t++) {
            List<ListNode> lists = new ArrayList<>();
            List<Integer> all = new ArrayList<>();
            for (int i = rnd.nextInt(6); i > 0; i--) {
                int[] a = Gen.ints(rnd, 5, 0, 9);
                Arrays.sort(a);
                for (int x : a) all.add(x);
                lists.add(ListNode.of(a));
            }
            Collections.sort(all);
            assertEquals(all, ListNode.toList(Heaps.mergeKLists(lists)));
        }
    }

    @Test
    void medianFinderMatchesSortingAfterEveryInsert() {
        Random rnd = new Random(26);
        for (int t = 0; t < 300; t++) {
            Heaps.MedianFinder mf = new Heaps.MedianFinder();
            assertThrows(IllegalStateException.class, mf::median);
            List<Integer> seen = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                int x = rnd.nextInt(21) - 10;
                mf.add(x);
                seen.add(x);
                List<Integer> s = new ArrayList<>(seen);
                Collections.sort(s);
                double expected = s.size() % 2 == 1 ? s.get(s.size() / 2) : (s.get(s.size() / 2 - 1) + (double) s.get(s.size() / 2)) / 2.0;
                assertEquals(expected, mf.median());
            }
        }
        // Two huge values: the mean must be computed in double, not int (MAX + MAX overflows).
        Heaps.MedianFinder big = new Heaps.MedianFinder();
        big.add(Integer.MAX_VALUE);
        big.add(Integer.MAX_VALUE);
        assertEquals((double) Integer.MAX_VALUE, big.median());
    }

    // ---- intervals -----------------------------------------------------------------------------------------

    @Test
    void mergeIntervalsMatchesPointCoverage() {
        Random rnd = new Random(27);
        for (int t = 0; t < TRIALS; t++) {
            int[][] iv = randomIntervals(rnd, 6, 12);
            int[][] merged = Intervals.merge(iv);
            for (int i = 1; i < merged.length; i++) assertTrue(merged[i][0] > merged[i - 1][1], "merged intervals must be disjoint and ordered");
            // Closed intervals: integer point p is covered by the input iff it is covered by the output.
            for (int p = -1; p <= 13; p++) assertEquals(covered(iv, p), covered(merged, p));
            // Touching intervals [a,b],[b,c] must merge, and non-integer gaps are not a concern for integer input;
            // but [1,2],[3,4] stay separate (3 > 2), which the disjointness check above enforces.
        }
        assertEquals("[[1, 4]]", Arrays.deepToString(Intervals.merge(new int[][] {{1, 2}, {2, 4}})));
        assertEquals("[[1, 2], [3, 4]]", Arrays.deepToString(Intervals.merge(new int[][] {{3, 4}, {1, 2}})));
        assertEquals("[[0, 10]]", Arrays.deepToString(Intervals.merge(new int[][] {{0, 10}, {2, 3}})));   // nested
    }

    private static int[][] randomIntervals(Random rnd, int maxCount, int range) {
        int[][] iv = new int[rnd.nextInt(maxCount) + 1][];
        for (int i = 0; i < iv.length; i++) {
            int s = rnd.nextInt(range), e = s + rnd.nextInt(4);
            iv[i] = new int[] {s, e};
        }
        return iv;
    }

    private static boolean covered(int[][] iv, int p) {
        for (int[] x : iv) if (x[0] <= p && p <= x[1]) return true;
        return false;
    }

    @Test
    void insertIntervalEqualsMergeOfEverything() {
        Random rnd = new Random(28);
        for (int t = 0; t < TRIALS; t++) {
            int[][] base = Intervals.merge(randomIntervals(rnd, 5, 14));    // sorted and disjoint, as insert requires
            int s = rnd.nextInt(14);
            int[] added = {s, s + rnd.nextInt(4)};
            int[][] all = new int[base.length + 1][];
            System.arraycopy(base, 0, all, 0, base.length);
            all[base.length] = added;
            assertEquals(Arrays.deepToString(Intervals.merge(all)), Arrays.deepToString(Intervals.insert(base, added)));
        }
    }

    @Test
    void meetingRoomsAndRemovalsMatchBruteForce() {
        Random rnd = new Random(29);
        for (int t = 0; t < TRIALS; t++) {
            int[][] m = randomIntervals(rnd, 7, 10);
            for (int[] x : m) if (x[1] == x[0]) x[1]++;                      // half-open: need positive length
            // Rooms = max number of meetings alive at any instant (check each integer instant).
            int rooms = 0;
            for (int p = 0; p <= 14; p++) {
                int alive = 0;
                for (int[] x : m) if (x[0] <= p && p < x[1]) alive++;
                rooms = Math.max(rooms, alive);
            }
            assertEquals(rooms, Intervals.minMeetingRooms(m));

            // Removals: try every subset to find the largest non-overlapping one.
            int n = m.length, bestKept = 0;
            for (int mask = 0; mask < (1 << n); mask++) {
                int kept = Integer.bitCount(mask);
                if (kept <= bestKept) continue;
                boolean ok = true;
                for (int i = 0; i < n && ok; i++) {
                    if ((mask >> i & 1) == 0) continue;
                    for (int j = i + 1; j < n && ok; j++) {
                        if ((mask >> j & 1) != 0 && m[i][0] < m[j][1] && m[j][0] < m[i][1]) ok = false;
                    }
                }
                if (ok) bestKept = kept;
            }
            assertEquals(n - bestKept, Intervals.minRemovalsToAvoidOverlap(m));
        }
        assertEquals(1, Intervals.minMeetingRooms(new int[][] {{0, 5}, {5, 10}}));     // back-to-back share a room
        assertEquals(0, Intervals.minMeetingRooms(new int[0][]));
    }
}
