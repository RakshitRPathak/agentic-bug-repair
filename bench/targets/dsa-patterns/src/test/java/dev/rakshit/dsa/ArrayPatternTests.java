package dev.rakshit.dsa;

import static dev.rakshit.dsa.testkit.Assert.assertEquals;
import static dev.rakshit.dsa.testkit.Assert.assertNull;
import static dev.rakshit.dsa.testkit.Assert.assertTrue;

import dev.rakshit.dsa.testkit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Differential tests: every fast solution is checked against an obviously-correct brute force on thousands of
 * small random inputs. Small value ranges force duplicates, ties and empty/one-element inputs, which is where
 * pattern solutions usually break.
 */
public class ArrayPatternTests {
    private static final int TRIALS = 2000;

    // ---- two pointers --------------------------------------------------------------------------------------

    @Test
    void twoSumSortedMatchesBruteForce() {
        Random rnd = new Random(1);
        for (int t = 0; t < TRIALS; t++) {
            int[] a = Gen.ints(rnd, 8, -5, 5);
            Arrays.sort(a);
            int target = rnd.nextInt(15) - 7;
            boolean exists = false;
            for (int i = 0; i < a.length; i++) for (int j = i + 1; j < a.length; j++) if (a[i] + a[j] == target) exists = true;
            int[] got = TwoPointers.twoSumSorted(a, target);
            if (!exists) assertNull(got);
            else {
                assertTrue(got != null, "missed a pair in " + Arrays.toString(a) + " target " + target);
                assertTrue(got[0] < got[1] && a[got[0]] + a[got[1]] == target, "bad pair for " + Arrays.toString(a));
            }
        }
    }

    @Test
    void threeSumMatchesBruteForce() {
        Random rnd = new Random(2);
        for (int t = 0; t < TRIALS; t++) {
            int[] a = Gen.ints(rnd, 9, -4, 4);
            Set<List<Integer>> expected = new HashSet<>();
            for (int i = 0; i < a.length; i++)
                for (int j = i + 1; j < a.length; j++)
                    for (int k = j + 1; k < a.length; k++)
                        if (a[i] + a[j] + a[k] == 0) {
                            List<Integer> trip = new ArrayList<>(List.of(a[i], a[j], a[k]));
                            trip.sort(null);
                            expected.add(trip);
                        }
            List<List<Integer>> got = TwoPointers.threeSum(a);
            assertEquals(expected.size(), got.size());          // no duplicates in the output
            assertEquals(expected, new HashSet<>(got));
        }
    }

    @Test
    void maxAreaAndTrapMatchBruteForce() {
        Random rnd = new Random(3);
        for (int t = 0; t < TRIALS; t++) {
            int[] h = Gen.ints(rnd, 10, 0, 9);
            int bestArea = 0;
            for (int i = 0; i < h.length; i++) for (int j = i + 1; j < h.length; j++) bestArea = Math.max(bestArea, Math.min(h[i], h[j]) * (j - i));
            assertEquals(bestArea, TwoPointers.maxArea(h));

            int water = 0;
            for (int i = 0; i < h.length; i++) {
                int lmax = 0, rmax = 0;
                for (int j = 0; j <= i; j++) lmax = Math.max(lmax, h[j]);
                for (int j = i; j < h.length; j++) rmax = Math.max(rmax, h[j]);
                water += Math.min(lmax, rmax) - h[i];
            }
            assertEquals(water, TwoPointers.trap(h));
        }
    }

    @Test
    void removeDuplicatesAndSortColors() {
        Random rnd = new Random(4);
        for (int t = 0; t < TRIALS; t++) {
            int[] a = Gen.ints(rnd, 10, 0, 4);
            Arrays.sort(a);
            int[] distinct = Arrays.stream(a).distinct().toArray();
            int[] work = a.clone();
            int n = TwoPointers.removeDuplicatesSorted(work);
            assertEquals(Arrays.toString(distinct), Arrays.toString(Arrays.copyOf(work, n)));

            int[] colors = Gen.ints(rnd, 12, 0, 2);
            int[] sortedCopy = colors.clone();
            Arrays.sort(sortedCopy);
            TwoPointers.sortColors(colors);
            assertEquals(Arrays.toString(sortedCopy), Arrays.toString(colors));
        }
    }

    // ---- sliding window ------------------------------------------------------------------------------------

    @Test
    void longestUniqueAndAtMostKDistinctMatchBruteForce() {
        Random rnd = new Random(5);
        for (int t = 0; t < TRIALS; t++) {
            String s = Gen.string(rnd, 12, 4);
            int k = rnd.nextInt(4);
            int bestUnique = 0, bestK = 0;
            for (int i = 0; i < s.length(); i++) {
                for (int j = i; j < s.length(); j++) {
                    long distinct = s.substring(i, j + 1).chars().distinct().count();
                    int len = j - i + 1;
                    if (distinct == len) bestUnique = Math.max(bestUnique, len);
                    if (distinct <= k) bestK = Math.max(bestK, len);
                }
            }
            assertEquals(bestUnique, SlidingWindow.longestUniqueSubstring(s));
            assertEquals(bestK, SlidingWindow.longestAtMostKDistinct(s, k));
        }
    }

    @Test
    void minWindowMatchesBruteForce() {
        Random rnd = new Random(6);
        for (int t = 0; t < TRIALS; t++) {
            String s = Gen.string(rnd, 12, 3);
            String need = Gen.string(rnd, 4, 3);
            String expected = "";
            search:
            for (int len = Math.max(1, need.length()); len <= s.length(); len++) {
                for (int i = 0; i + len <= s.length(); i++) {
                    if (covers(s.substring(i, i + len), need)) {
                        expected = s.substring(i, i + len);
                        break search;                              // shortest first, then leftmost
                    }
                }
            }
            if (need.isEmpty()) expected = "";
            assertEquals(expected, SlidingWindow.minWindow(s, need));
        }
    }

    private static boolean covers(String window, String need) {
        int[] count = new int[128];
        for (char c : window.toCharArray()) count[c]++;
        for (char c : need.toCharArray()) if (--count[c] < 0) return false;
        return true;
    }

    @Test
    void minSubArrayLenAndMaxSlidingWindowMatchBruteForce() {
        Random rnd = new Random(7);
        for (int t = 0; t < TRIALS; t++) {
            int[] a = Gen.ints(rnd, 10, 1, 6);
            int target = 1 + rnd.nextInt(20);
            int best = 0;
            for (int i = 0; i < a.length; i++) {
                int sum = 0;
                for (int j = i; j < a.length; j++) {
                    sum += a[j];
                    if (sum >= target) {
                        if (best == 0 || j - i + 1 < best) best = j - i + 1;
                        break;
                    }
                }
            }
            assertEquals(best, SlidingWindow.minSubArrayLen(target, a));

            int[] b = Gen.ints(rnd, 12, -5, 5);
            if (b.length == 0) continue;
            int k = 1 + rnd.nextInt(b.length);
            int[] expected = new int[b.length - k + 1];
            for (int i = 0; i < expected.length; i++) {
                int m = Integer.MIN_VALUE;
                for (int j = i; j < i + k; j++) m = Math.max(m, b[j]);
                expected[i] = m;
            }
            assertEquals(Arrays.toString(expected), Arrays.toString(SlidingWindow.maxSlidingWindow(b, k)));
        }
    }

    // ---- hashing and prefix sums ---------------------------------------------------------------------------

    @Test
    void twoSumFindsAValidPairIffOneExists() {
        Random rnd = new Random(8);
        for (int t = 0; t < TRIALS; t++) {
            int[] a = Gen.ints(rnd, 8, -5, 5);
            int target = rnd.nextInt(15) - 7;
            boolean exists = false;
            for (int i = 0; i < a.length; i++) for (int j = i + 1; j < a.length; j++) if (a[i] + a[j] == target) exists = true;
            int[] got = PrefixSumAndHashing.twoSum(a, target);
            if (!exists) assertNull(got);
            else assertTrue(got != null && got[0] != got[1] && a[got[0]] + a[got[1]] == target, "bad pair for " + Arrays.toString(a));
        }
    }

    @Test
    void subarraySumEqualsKHandlesNegativesAndMatchesBruteForce() {
        Random rnd = new Random(9);
        for (int t = 0; t < TRIALS; t++) {
            int[] a = Gen.ints(rnd, 10, -3, 3);
            int k = rnd.nextInt(7) - 3;
            int count = 0;
            for (int i = 0; i < a.length; i++) {
                int sum = 0;
                for (int j = i; j < a.length; j++) {
                    sum += a[j];
                    if (sum == k) count++;
                }
            }
            assertEquals(count, PrefixSumAndHashing.subarraySumEqualsK(a, k));
        }
        // Prefix sums can exceed int range even when each element fits: this must not overflow.
        // Subarrays summing to MAX: [0], [1] and [0..2] (whose running prefix 2*MAX does not fit in an int).
        assertEquals(3, PrefixSumAndHashing.subarraySumEqualsK(new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE, -Integer.MAX_VALUE}, Integer.MAX_VALUE));
    }

    @Test
    void longestConsecutiveMatchesSortBasedBruteForceAndSurvivesIntExtremes() {
        Random rnd = new Random(10);
        for (int t = 0; t < TRIALS; t++) {
            int[] a = Gen.ints(rnd, 12, -6, 6);
            int[] s = Arrays.stream(a).distinct().sorted().toArray();
            int best = 0, run = 0;
            for (int i = 0; i < s.length; i++) {
                run = i > 0 && s[i] == s[i - 1] + 1 ? run + 1 : 1;
                best = Math.max(best, run);
            }
            assertEquals(best, PrefixSumAndHashing.longestConsecutive(a));
        }
        // MAX_VALUE + 1 wraps to MIN_VALUE in int arithmetic; these two are NOT consecutive.
        assertEquals(1, PrefixSumAndHashing.longestConsecutive(new int[] {Integer.MAX_VALUE, Integer.MIN_VALUE}));
        assertEquals(2, PrefixSumAndHashing.longestConsecutive(new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE - 1}));
    }

    @Test
    void groupAnagramsAndProductExceptSelf() {
        List<List<String>> groups = PrefixSumAndHashing.groupAnagrams(new String[] {"eat", "tea", "tan", "ate", "nat", "bat"});
        Set<Set<String>> got = new HashSet<>();
        for (List<String> g : groups) got.add(new HashSet<>(g));
        assertEquals(Set.of(Set.of("eat", "tea", "ate"), Set.of("tan", "nat"), Set.of("bat")), got);

        Random rnd = new Random(11);
        for (int t = 0; t < TRIALS; t++) {
            int[] a = Gen.ints(rnd, 8, -3, 3);       // includes zeros: division-based solutions fail here
            int[] expected = new int[a.length];
            for (int i = 0; i < a.length; i++) {
                int p = 1;
                for (int j = 0; j < a.length; j++) if (j != i) p *= a[j];
                expected[i] = p;
            }
            assertEquals(Arrays.toString(expected), Arrays.toString(PrefixSumAndHashing.productExceptSelf(a)));
        }
    }

    // ---- monotonic stack -----------------------------------------------------------------------------------

    @Test
    void monotonicStackProblemsMatchBruteForce() {
        Random rnd = new Random(12);
        for (int t = 0; t < TRIALS; t++) {
            int[] a = Gen.ints(rnd, 12, 0, 6);
            int[] next = new int[a.length], days = new int[a.length];
            for (int i = 0; i < a.length; i++) {
                next[i] = -1;
                for (int j = i + 1; j < a.length; j++) {
                    if (a[j] > a[i]) {
                        next[i] = a[j];
                        days[i] = j - i;
                        break;
                    }
                }
            }
            assertEquals(Arrays.toString(next), Arrays.toString(MonotonicStack.nextGreater(a)));
            assertEquals(Arrays.toString(days), Arrays.toString(MonotonicStack.dailyTemperatures(a)));

            long best = 0;
            for (int i = 0; i < a.length; i++) {
                int min = Integer.MAX_VALUE;
                for (int j = i; j < a.length; j++) {
                    min = Math.min(min, a[j]);
                    best = Math.max(best, (long) min * (j - i + 1));
                }
            }
            assertEquals(best, MonotonicStack.largestRectangle(a));
        }
        // Area does not fit in an int: 100000 bars of height 100000.
        int[] big = new int[100_000];
        Arrays.fill(big, 100_000);
        assertEquals(10_000_000_000L, MonotonicStack.largestRectangle(big));
    }

    // ---- binary search -------------------------------------------------------------------------------------

    @Test
    void boundsAndFirstLastMatchLinearScan() {
        Random rnd = new Random(13);
        for (int t = 0; t < TRIALS; t++) {
            int[] a = Gen.ints(rnd, 12, 0, 8);
            Arrays.sort(a);
            int x = rnd.nextInt(11) - 1;
            int lower = a.length, upper = a.length, first = -1, last = -1;
            for (int i = a.length - 1; i >= 0; i--) {
                if (a[i] >= x) lower = i;
                if (a[i] > x) upper = i;
            }
            for (int i = 0; i < a.length; i++) {
                if (a[i] == x) {
                    if (first < 0) first = i;
                    last = i;
                }
            }
            assertEquals(lower, BinarySearch.lowerBound(a, x));
            assertEquals(upper, BinarySearch.upperBound(a, x));
            assertEquals(Arrays.toString(new int[] {first, last}), Arrays.toString(BinarySearch.firstAndLast(a, x)));
        }
    }

    @Test
    void rotatedArrayProblemsTryEveryRotation() {
        Random rnd = new Random(14);
        for (int t = 0; t < 500; t++) {
            int n = 1 + rnd.nextInt(10);
            int[] sorted = Arrays.stream(Gen.ints(rnd, 30, 0, 50)).distinct().sorted().limit(n).toArray();
            if (sorted.length == 0) continue;
            for (int shift = 0; shift < sorted.length; shift++) {
                int[] a = new int[sorted.length];
                for (int i = 0; i < a.length; i++) a[i] = sorted[(i + shift) % a.length];
                assertEquals(sorted[0], BinarySearch.findMinRotated(a));
                for (int target = -1; target <= 51; target++) {
                    int expected = -1;
                    for (int i = 0; i < a.length; i++) if (a[i] == target) expected = i;
                    assertEquals(expected, BinarySearch.searchRotated(a, target));
                }
            }
        }
    }

    @Test
    void binarySearchOnTheAnswerMatchesTryingEveryAnswer() {
        Random rnd = new Random(15);
        for (int t = 0; t < TRIALS; t++) {
            int[] w = Gen.ints(rnd, 8, 1, 9);
            if (w.length == 0) continue;
            int days = 1 + rnd.nextInt(w.length);
            int cap = Arrays.stream(w).max().getAsInt();
            while (daysFor(w, cap) > days) cap++;
            assertEquals(cap, BinarySearch.shipWithinDays(w, days));

            int hours = w.length + rnd.nextInt(10);
            int speed = 1;
            while (hoursFor(w, speed) > hours) speed++;
            assertEquals(speed, BinarySearch.minEatingSpeed(w, hours));
        }
    }

    private static int daysFor(int[] w, int cap) {
        int days = 1, load = 0;
        for (int x : w) {
            if (load + x > cap) {
                days++;
                load = 0;
            }
            load += x;
        }
        return days;
    }

    private static long hoursFor(int[] piles, int speed) {
        long h = 0;
        for (int p : piles) h += (p + speed - 1) / speed;
        return h;
    }

    @Test
    void sqrtIsExactIncludingTheTopOfTheIntRange() {
        for (int x = 0; x <= 5000; x++) assertEquals((int) Math.floor(Math.sqrt(x)), BinarySearch.sqrt(x));
        assertEquals(46340, BinarySearch.sqrt(Integer.MAX_VALUE));   // 46340^2 = 2147395600 <= MAX < 46341^2
        assertEquals(46340, BinarySearch.sqrt(46340 * 46340));
        assertEquals(46339, BinarySearch.sqrt(46340 * 46340 - 1));
    }
}
