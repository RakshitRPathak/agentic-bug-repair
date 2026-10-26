package dev.rakshit.dsa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Queue;

/**
 * PATTERN: binary trees (recursion on the shape, BFS by level).
 *
 * <p><b>Recognise it when:</b> the input is a tree. Two moves cover nearly everything:
 * <ol>
 *   <li><b>DFS recursion:</b> solve for the left subtree, solve for the right, combine at the node. Decide
 *       first what each call <em>returns</em> (a height, a boolean, a count) and what the base case (null) returns.</li>
 *   <li><b>BFS with a queue:</b> process level by level by snapshotting {@code queue.size()} at the start of each level.</li>
 * </ol>
 * Pitfall for "best path" problems: the value a call <em>returns</em> to its parent (one downward branch) is not the
 * same as the value it <em>records</em> as a candidate answer (a path bending through this node, using both branches).
 */
public final class Trees {
    private Trees() {}

    /** Number of nodes on the longest root-to-leaf path. */
    public static int maxDepth(TreeNode root) {
        return root == null ? 0 : 1 + Math.max(maxDepth(root.left), maxDepth(root.right));
    }

    /** Level-order traversal: one list per level, left to right. */
    public static List<List<Integer>> levelOrder(TreeNode root) {
        List<List<Integer>> out = new ArrayList<>();
        if (root == null) return out;
        Queue<TreeNode> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            int width = queue.size();               // exactly the nodes of this level
            List<Integer> level = new ArrayList<>(width);
            for (int i = 0; i < width; i++) {
                TreeNode n = queue.poll();
                level.add(n.val);
                if (n.left != null) queue.add(n.left);
                if (n.right != null) queue.add(n.right);
            }
            out.add(level);
        }
        return out;
    }

    /** In-order traversal done iteratively with an explicit stack (no recursion depth limit on skewed trees). */
    public static List<Integer> inorder(TreeNode root) {
        List<Integer> out = new ArrayList<>();
        Deque<TreeNode> stack = new ArrayDeque<>();
        TreeNode cur = root;
        while (cur != null || !stack.isEmpty()) {
            while (cur != null) {                   // go as far left as possible
                stack.push(cur);
                cur = cur.left;
            }
            cur = stack.pop();
            out.add(cur.val);
            cur = cur.right;
        }
        return out;
    }

    /**
     * Is this a valid binary SEARCH tree? Comparing a node only with its direct children is the classic wrong answer:
     * every node in the left subtree must be below the node, not just its child. So pass down the open interval
     * (lo, hi) of values that are allowed at each position. Bounds are boxed so Integer.MIN/MAX values still work.
     */
    public static boolean isValidBst(TreeNode root) {
        return validate(root, null, null);
    }

    private static boolean validate(TreeNode n, Integer lo, Integer hi) {
        if (n == null) return true;
        if (lo != null && n.val <= lo) return false;
        if (hi != null && n.val >= hi) return false;
        return validate(n.left, lo, n.val) && validate(n.right, n.val, hi);
    }

    /**
     * Lowest common ancestor of nodes p and q (both must be in the tree). If the current node is p or q, it is
     * the answer for its subtree; if p and q are found on different sides, the current node is the LCA.
     */
    public static TreeNode lowestCommonAncestor(TreeNode root, TreeNode p, TreeNode q) {
        if (root == null || root == p || root == q) return root;
        TreeNode left = lowestCommonAncestor(root.left, p, q);
        TreeNode right = lowestCommonAncestor(root.right, p, q);
        if (left != null && right != null) return root;
        return left != null ? left : right;
    }

    /**
     * Diameter in edges: the longest path between any two nodes, which need not pass through the root.
     * Each call returns its height (one downward branch) but records left height + right height (the path that bends here).
     */
    public static int diameter(TreeNode root) {
        int[] best = {0};
        height(root, best);
        return best[0];
    }

    private static int height(TreeNode n, int[] best) {
        if (n == null) return 0;
        int l = height(n.left, best), r = height(n.right, best);
        best[0] = Math.max(best[0], l + r);
        return 1 + Math.max(l, r);
    }

    /**
     * Maximum sum over any non-empty path (a path may start and end anywhere and may bend once).
     * A negative branch hurts, so clamp each branch gain at 0 (meaning "do not extend that way").
     */
    public static long maxPathSum(TreeNode root) {
        long[] best = {Long.MIN_VALUE};
        gain(root, best);
        return best[0];
    }

    private static long gain(TreeNode n, long[] best) {
        if (n == null) return 0;
        long l = Math.max(0, gain(n.left, best));
        long r = Math.max(0, gain(n.right, best));
        best[0] = Math.max(best[0], n.val + l + r);
        return n.val + Math.max(l, r);
    }
}
