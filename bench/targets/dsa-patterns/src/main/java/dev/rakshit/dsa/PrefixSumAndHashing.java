package dev.rakshit.dsa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PATTERN: hashing and prefix sums ("remember what I have already seen").
 *
 * <p><b>Recognise it when:</b> a brute force would re-scan the array to find a partner, a count or a duplicate.
 * Replace the inner scan with a hash lookup. For "subarray with sum K" the partner is a <em>prefix sum</em>:
 * the sum of a[i..j] equals prefix[j+1] - prefix[i], so we are looking for an earlier prefix equal to
 * (current prefix - K).
 *
 * <p><b>Template:</b> one pass; before inserting the current element, ask the map whether the element that
 * would complete the answer was already seen. Asking before inserting stops an element pairing with itself.
 */
public final class PrefixSumAndHashing {
    private PrefixSumAndHashing() {}

    /** Indices of two different elements summing to {@code target}, or null. O(n) instead of O(n^2). */
    public static int[] twoSum(int[] nums, int target) {
        Map<Integer, Integer> seen = new HashMap<>(); // value -> index
        for (int i = 0; i < nums.length; i++) {
            Integer j = seen.get(target - nums[i]);
            if (j != null) return new int[] {j, i};
            seen.put(nums[i], i);
        }
        return null;
    }

    /**
     * Number of contiguous subarrays whose sum equals {@code k}. Works with negative numbers, which is why a
     * sliding window cannot be used here. {@code freq} maps a prefix-sum value to how many times it occurred.
     */
    public static int subarraySumEqualsK(int[] nums, int k) {
        Map<Long, Integer> freq = new HashMap<>();
        freq.put(0L, 1);                // the empty prefix
        long prefix = 0;
        int count = 0;
        for (int x : nums) {
            prefix += x;
            count += freq.getOrDefault(prefix - k, 0);
            freq.merge(prefix, 1, Integer::sum);
        }
        return count;
    }

    /**
     * Length of the longest run of consecutive integers (in any order), in O(n).
     * Only start counting from a number with no predecessor in the set, so each run is walked exactly once.
     * Values are widened to {@code long} so that Integer.MAX_VALUE + 1 does not wrap around to Integer.MIN_VALUE
     * and falsely join two ends of the int range into one run.
     */
    public static int longestConsecutive(int[] nums) {
        Set<Long> set = new HashSet<>();
        for (int x : nums) set.add((long) x);
        int best = 0;
        for (long x : set) {
            if (set.contains(x - 1)) continue;   // not the start of a run
            int len = 1;
            while (set.contains(x + len)) len++;
            best = Math.max(best, len);
        }
        return best;
    }

    /** Groups words that are anagrams of each other. The sorted letters are the canonical key. */
    public static List<List<String>> groupAnagrams(String[] words) {
        Map<String, List<String>> groups = new HashMap<>();
        for (String w : words) {
            char[] cs = w.toCharArray();
            Arrays.sort(cs);
            groups.computeIfAbsent(new String(cs), key -> new ArrayList<>()).add(w);
        }
        return new ArrayList<>(groups.values());
    }

    /**
     * out[i] = product of all elements except nums[i], without division (so zeros are fine).
     * Product of everything to the left of i, times product of everything to the right of i.
     */
    public static int[] productExceptSelf(int[] nums) {
        int n = nums.length;
        int[] out = new int[n];
        int left = 1;
        for (int i = 0; i < n; i++) {
            out[i] = left;
            left *= nums[i];
        }
        int right = 1;
        for (int i = n - 1; i >= 0; i--) {
            out[i] *= right;
            right *= nums[i];
        }
        return out;
    }
}
