# system-design-primitives

The building blocks behind the classic system-design questions ("design a rate limiter", "design a
cache", "design a distributed cache"), implemented from scratch in Java with no dependencies and
checked against reference implementations. Each class explains the idea and its trade-offs in its
header comment.

```bash
./build.sh        # needs only a JDK 17+; compiles, then runs all 32 tests
```

| Component | Problem it solves | Complexity | Key idea |
|---|---|---|---|
| `LruCache` | Evict the least recently used entry | O(1) get/put | Hash map + doubly linked list with sentinels |
| `LfuCache` | Evict the least frequently used entry (ties: oldest use) | O(1) get/put | One ordered bucket of keys per use-count, plus a `minFreq` pointer |
| `TokenBucket` | Rate limit with bursts | O(1) per key | Refill lazily from elapsed time, no background thread |
| `SlidingWindowLog` | Exact rate limit | O(limit) memory per key | Keep timestamps, drop expired ones from the front |
| `SlidingWindowCounter` | Cheap approximate sliding limit | O(1) per key | `previous * (1 - elapsed) + current` |
| `ConsistentHashRing` | Spread keys over servers, move few when servers change | O(log n) lookup | Hash ring with virtual nodes |
| `BloomFilter` | "Definitely not here" in a few bits per item | O(k) | `k` bit probes from two hashes |

## What the tests check

The tests check edge cases and failure modes, not just the happy path.

* **Caches** are run for 20,000 random operations against a reference: Java's `LinkedHashMap` in access
  order for LRU, and a deliberately slow, obviously correct scan-for-the-victim version for LFU.
* **Rate limiters** use an injectable clock, so time moves without sleeping. Tests cover the burst
  limit, refill, the cap after a long idle period, and all-or-nothing multi-permit requests (a failed
  request must not drain tokens). With 200 threads racing for 50 tokens, exactly 50 win. A request
  burst at a window boundary, which fixed-window limiters allow at double the limit, is blocked.
* **Consistent hashing** is measured, not assumed. Adding a 6th node to 5 moved 17.1% of 20,000 keys
  (ideal: 1/6 = 16.7%), and every moved key went to the new node. `hash(key) % N` moves 93.2% for the
  same change. Removing a node only moves that node's keys.
* **Bloom filter**: no false negatives over 10,000 items; with a 1% target the measured false-positive
  rate over 100,000 probes was 0.97%, using 11.7 KB where a `HashSet` of the same strings would hold
  the strings themselves.

## Design notes

* **Virtual nodes and imbalance.** Over 30 random rings of 5 nodes, the worst node's load deviated from
  its fair share by 111% on average with 1 point per node, 37% with 10, 10% with 200 and 5% with 1,000
  (the spread falls roughly as 1/√replicas). The test asserts 15% at 1,000 replicas, not at 200.
* **Token bucket vs sliding window.** Token bucket allows a burst up to capacity and is O(1) memory.
  The log is exact but stores every timestamp. The counter is a good middle: O(1) memory with an error
  that only shows for very bursty traffic.
* **Single process only.** These are in-memory. A real rate limiter shared by many servers keeps the
  counters in something like Redis and makes the check-and-update atomic (a Lua script). The caches
  are not thread-safe; wrap them or stripe the lock.
* **Hashing.** MD5 is used only to spread keys (it is not used for security). Production rings usually
  use MurmurHash3 or xxHash for speed.

## Layout

```
src/main/java/dev/rakshit/sdp/{cache,ratelimit,hashing,bloom}/
src/test/java/dev/rakshit/sdp/        tests + testkit/ (a ~100-line stand-in for JUnit)
build.sh
```

The tests run on a small built-in runner instead of JUnit so the repo has no downloads at all.
To move to JUnit 5, replace `@Test` and the `Assert` helpers; the test bodies carry over unchanged.
