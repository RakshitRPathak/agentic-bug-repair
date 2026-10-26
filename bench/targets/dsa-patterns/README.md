# dsa-patterns

Data-structures-and-algorithms interview patterns in Java 21, organised by **how to recognise the problem**, not by
data structure. Each class opens with the signal that tells you the pattern applies, the template, and the trap
people fall into; every method then explains *why* its invariant holds.

Everything is checked against a slow, obviously-correct brute force on thousands of random small inputs
(seeded, so any failure is reproducible). Needs only a JDK, no Maven or JUnit.

```
./build.sh          # compiles with -Xlint:all and runs all tests
```

## Which pattern? (the recognition table)

| If the problem says... | Reach for | Class | Typical cost |
|---|---|---|---|
| sorted array, find a pair / triplet, or shrink from both ends | two pointers | `TwoPointers` | O(n) after sort |
| longest / shortest **contiguous** run with a property, all values positive | sliding window | `SlidingWindow` | O(n) |
| count or find subarrays with a given sum (negatives allowed), duplicates, anagrams | hash map / prefix sum | `PrefixSumAndHashing` | O(n) |
| "next greater / smaller element", span, largest rectangle | monotonic stack | `MonotonicStack` | O(n) |
| sorted or rotated input, or "smallest X such that it works" | binary search (also on the answer) | `BinarySearch` | O(log n) / O(n log range) |
| ranges, meetings, overlaps | sort + sweep | `Intervals` | O(n log n) |
| linked list: middle, cycle, n-th from end, reverse | dummy node, slow/fast pointers | `LinkedLists` | O(n), O(1) space |
| top-k, k-th largest, merge k sorted, running median | heap | `Heaps` | O(n log k) |
| tree: depth, validity, ancestor, path | DFS recursion / BFS by level | `Trees` | O(n) |
| fewest steps / cheapest path / ordering / connectivity | BFS, Dijkstra, Kahn, Union-Find | `Graphs` | O(V + E) (log factor for Dijkstra) |
| enumerate subsets, permutations, placements | backtracking | `Backtracking` | exponential, pruned |
| overlapping sub-problems, "minimum / maximum / number of ways" | dynamic programming | `DynamicProgramming` | O(states) |
| many strings queried by prefix | trie | `Trie` | O(length of query) |

## Traps this repo encodes in tests

These are the mistakes that quietly pass the sample cases and fail the hidden ones.

- `longestConsecutive`: `Integer.MAX_VALUE + 1` wraps to `MIN_VALUE`, falsely joining the two ends of the int range. Values are widened to `long`.
- `subarraySumEqualsK`: prefix sums overflow `int` even when every element fits. A sliding window is also wrong here once negatives appear.
- `largestRectangle`: the area of 100,000 bars of height 100,000 is 10^10 and needs a `long`.
- `isValidBst`: comparing a node only with its direct children accepts a tree where a grandchild breaks the ordering; bounds must be passed down.
- `knapsack01`: iterating capacity upwards silently turns 0/1 into unbounded knapsack. A test with one item and a large capacity pins it.
- `coinChange`: greedy (largest coin first) fails for coins {1, 3, 4} and amount 6.
- `permutationsUnique`: duplicate values need sort + skip, checked against the multiset-permutation count n!/(c1! c2! ...).
- `isPalindrome` (list): reverses the second half in place, so it must restore the list; the test checks the caller sees it unchanged.
- `Heaps.MedianFinder`: the mean of two `Integer.MAX_VALUE` values must be computed in `double`.
- `inorder`: iterative, so a 200,000-node skewed tree does not overflow the stack.

## How the tests work

`ArrayPatternTests`, `StructureTests`, `SearchAndGraphTests`, `DynamicProgrammingTests`: 52 tests. Typical shape:

1. generate thousands of small random inputs with a fixed seed and a small value range, which forces duplicates, ties, and empty or single-element cases;
2. run the fast solution and a brute force (try every subset, every substring, every permutation, Bellman-Ford instead of Dijkstra, Floyd-Warshall instead of BFS...);
3. assert they agree.

Where an oracle is itself nontrivial, it is a different algorithm rather than a copy of the solution (for example,
topological order is validated by trying every permutation of the nodes; diameter and max path sum by enumerating every pair of tree nodes).

**Do the tests catch bugs?** I planted 11 deliberate bugs, one at a time (a `>=` flipped to `>`, a loop direction reversed,
a rebalancing step removed, ...). 8 were caught immediately and 1 was caught after adding two more BST cases. The remaining 2 are behaviourally equivalent to the original, so no test can fail them:
Dijkstra's "skip stale heap entry" line is a pure speed optimisation, so removing it cannot change an answer. Likewise `>=` vs `>` in the histogram stack gives the same areas. Run the same experiment yourself by editing any comparison in `src/main` and re-running `./build.sh`.

## Layout

```
src/main/java/dev/rakshit/dsa/   one class per pattern (+ ListNode, TreeNode)
src/test/java/dev/rakshit/dsa/   differential tests, Gen (seeded random inputs), testkit (tiny @Test/Assert/Runner)
build.sh                         javac -Xlint:all, then run every test
```
