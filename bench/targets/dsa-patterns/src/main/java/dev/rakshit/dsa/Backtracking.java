package dev.rakshit.dsa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PATTERN: backtracking ("choose, explore, un-choose").
 *
 * <p><b>Recognise it when:</b> you must enumerate all subsets, permutations, or placements satisfying constraints.
 * The search is a tree of decisions; walk it depth-first, and undo each choice on the way back up.
 *
 * <p><b>Template:</b>
 * <pre>
 *   void go(state) {
 *       if (complete(state)) { record(copy of state); return; }   // COPY: the live list keeps changing
 *       for (choice : choices(state)) { apply(choice); go(state); undo(choice); }
 *   }
 * </pre>
 * <b>Pruning</b> (skip a branch the moment it cannot succeed) and <b>duplicate skipping</b> (sort, then skip an equal
 * value that was already tried at this depth) are what turn exponential-but-hopeless into exponential-but-fine.
 */
public final class Backtracking {
    private Backtracking() {}

    /** All subsets (the power set) of distinct numbers: 2^n of them. */
    public static List<List<Integer>> subsets(int[] nums) {
        List<List<Integer>> out = new ArrayList<>();
        subsets(nums, 0, new ArrayList<>(), out);
        return out;
    }

    private static void subsets(int[] nums, int start, List<Integer> current, List<List<Integer>> out) {
        out.add(new ArrayList<>(current));                // every node of the tree is a valid subset
        for (int i = start; i < nums.length; i++) {
            current.add(nums[i]);
            subsets(nums, i + 1, current, out);
            current.remove(current.size() - 1);
        }
    }

    /** All distinct permutations of numbers that may contain duplicates (sort, then skip repeated values at the same depth). */
    public static List<List<Integer>> permutationsUnique(int[] nums) {
        int[] sorted = nums.clone();
        Arrays.sort(sorted);
        List<List<Integer>> out = new ArrayList<>();
        permute(sorted, new boolean[sorted.length], new ArrayList<>(), out);
        return out;
    }

    private static void permute(int[] nums, boolean[] used, List<Integer> current, List<List<Integer>> out) {
        if (current.size() == nums.length) {
            out.add(new ArrayList<>(current));
            return;
        }
        for (int i = 0; i < nums.length; i++) {
            if (used[i]) continue;
            // An equal value just before us that is NOT in use was already tried in this slot: skipping avoids duplicates.
            if (i > 0 && nums[i] == nums[i - 1] && !used[i - 1]) continue;
            used[i] = true;
            current.add(nums[i]);
            permute(nums, used, current, out);
            current.remove(current.size() - 1);
            used[i] = false;
        }
    }

    /**
     * All unique combinations of positive numbers (each reusable any number of times) that sum to {@code target}.
     * Sorting enables pruning: once one candidate overshoots, every later (larger) one does too, so stop the loop.
     */
    public static List<List<Integer>> combinationSum(int[] candidates, int target) {
        int[] sorted = candidates.clone();
        Arrays.sort(sorted);
        List<List<Integer>> out = new ArrayList<>();
        combine(sorted, target, 0, new ArrayList<>(), out);
        return out;
    }

    private static void combine(int[] c, int remaining, int start, List<Integer> current, List<List<Integer>> out) {
        if (remaining == 0) {
            out.add(new ArrayList<>(current));
            return;
        }
        for (int i = start; i < c.length; i++) {
            if (c[i] > remaining) break;                  // prune: sorted, so the rest overshoot too
            current.add(c[i]);
            combine(c, remaining - c[i], i, current, out); // i, not i + 1: the same number may be reused
            current.remove(current.size() - 1);
        }
    }

    /** Number of ways to place n queens on an n x n board so none attack each other. Column and both diagonals tracked in O(1). */
    public static int nQueens(int n) {
        return placeQueens(n, 0, new boolean[n], new boolean[2 * n], new boolean[2 * n]);
    }

    private static int placeQueens(int n, int row, boolean[] cols, boolean[] diag, boolean[] anti) {
        if (row == n) return 1;
        int count = 0;
        for (int c = 0; c < n; c++) {
            int d = row - c + n, a = row + c;             // cells on one diagonal share row-c; on the other, row+c
            if (cols[c] || diag[d] || anti[a]) continue;
            cols[c] = diag[d] = anti[a] = true;
            count += placeQueens(n, row + 1, cols, diag, anti);
            cols[c] = diag[d] = anti[a] = false;
        }
        return count;
    }

    /** Does the word appear in the grid as a path of adjacent (4-direction) cells, using each cell at most once? */
    public static boolean wordSearch(char[][] board, String word) {
        if (word.isEmpty()) return true;
        for (int r = 0; r < board.length; r++) {
            for (int c = 0; c < board[0].length; c++) {
                if (search(board, word, 0, r, c)) return true;
            }
        }
        return false;
    }

    private static boolean search(char[][] b, String w, int k, int r, int c) {
        if (r < 0 || r >= b.length || c < 0 || c >= b[0].length || b[r][c] != w.charAt(k)) return false;
        if (k == w.length() - 1) return true;
        char saved = b[r][c];
        b[r][c] = '#';                                    // mark visited in place ...
        boolean found = search(b, w, k + 1, r + 1, c) || search(b, w, k + 1, r - 1, c)
                || search(b, w, k + 1, r, c + 1) || search(b, w, k + 1, r, c - 1);
        b[r][c] = saved;                                  // ... and always restore it (the "un-choose")
        return found;
    }
}
