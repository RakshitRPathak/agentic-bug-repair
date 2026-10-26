package dev.rakshit.sdp;

import static dev.rakshit.sdp.testkit.Assert.*;

import dev.rakshit.sdp.cache.LfuCache;
import dev.rakshit.sdp.cache.LruCache;
import dev.rakshit.sdp.testkit.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class CacheTests {

    @Test
    void lruEvictsLeastRecentlyUsed() {
        LruCache<String, Integer> c = new LruCache<>(2);
        c.put("a", 1);
        c.put("b", 2);
        c.get("a");          // a is now most recent, so b is the victim
        c.put("c", 3);
        assertNull(c.get("b"));
        assertEquals(1, c.get("a"));
        assertEquals(3, c.get("c"));
    }

    @Test
    void lruUpdateRefreshesRecencyAndValue() {
        LruCache<String, Integer> c = new LruCache<>(2);
        c.put("a", 1);
        c.put("b", 2);
        c.put("a", 10);      // update counts as a use
        c.put("c", 3);       // evicts b
        assertEquals(10, c.get("a"));
        assertNull(c.get("b"));
        assertEquals(2, c.size());
    }

    @Test
    void lruEvictionCallbackReceivesVictim() {
        List<String> evicted = new ArrayList<>();
        LruCache<String, Integer> c = new LruCache<>(1, (k, v) -> evicted.add(k + "=" + v));
        c.put("a", 1);
        c.put("b", 2);
        assertEquals(List.of("a=1"), evicted);
    }

    @Test
    void lruRemoveAndCapacityOne() {
        LruCache<Integer, Integer> c = new LruCache<>(1);
        c.put(1, 1);
        assertEquals(1, c.remove(1));
        assertNull(c.remove(1));
        assertEquals(0, c.size());
        c.put(2, 2);
        c.put(3, 3);
        assertFalse(c.containsKey(2));
        assertEquals(List.of(3), c.keysByRecency());
    }

    @Test
    void lruRejectsBadCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new LruCache<String, String>(0));
    }

    /** Compare against java.util.LinkedHashMap in access order, the textbook LRU, on random operations. */
    @Test
    void lruMatchesLinkedHashMapReference() {
        final int cap = 8;
        LruCache<Integer, Integer> mine = new LruCache<>(cap);
        Map<Integer, Integer> ref = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Integer> e) {
                return size() > cap;
            }
        };
        Random rnd = new Random(42);
        for (int i = 0; i < 20_000; i++) {
            int key = rnd.nextInt(20);
            if (rnd.nextBoolean()) {
                mine.put(key, i);
                ref.put(key, i);
            } else {
                assertEquals(ref.get(key), mine.get(key));
            }
            assertEquals(ref.size(), mine.size());
        }
    }

    @Test
    void lfuEvictsLeastFrequent() {
        LfuCache<String, Integer> c = new LfuCache<>(2);
        c.put("a", 1);
        c.put("b", 2);
        c.get("a");
        c.get("a");          // a used 3 times, b once
        c.put("c", 3);       // evicts b
        assertNull(c.get("b"));
        assertEquals(1, c.get("a"));
        assertEquals(3, c.get("c"));
    }

    @Test
    void lfuBreaksTiesByRecency() {
        LfuCache<String, Integer> c = new LfuCache<>(2);
        c.put("a", 1);
        c.put("b", 2);       // both used once; a is older
        c.put("c", 3);       // evicts a
        assertFalse(c.containsKey("a"));
        assertTrue(c.containsKey("b"));
        assertTrue(c.containsKey("c"));
    }

    @Test
    void lfuMinFreqResetsAfterInsert() {
        LfuCache<String, Integer> c = new LfuCache<>(2);
        c.put("a", 1);
        c.get("a");
        c.get("a");          // a: freq 3
        c.put("b", 2);       // b: freq 1
        c.put("c", 3);       // must evict b (freq 1), not a
        assertTrue(c.containsKey("a"));
        assertFalse(c.containsKey("b"));
        assertEquals(3, c.frequency("a"));
    }

    @Test
    void lfuUpdateCountsAsUse() {
        LfuCache<String, Integer> c = new LfuCache<>(2);
        c.put("a", 1);
        c.put("b", 2);
        c.put("a", 5);       // a: freq 2
        c.put("c", 3);       // evicts b
        assertEquals(5, c.get("a"));
        assertFalse(c.containsKey("b"));
    }

    /** Slow but obviously-correct LFU: scan for min freq, break ties by last-use time. */
    @Test
    void lfuMatchesNaiveReference() {
        final int cap = 6;
        LfuCache<Integer, Integer> mine = new LfuCache<>(cap);
        Map<Integer, int[]> ref = new HashMap<>(); // key -> {value, freq, lastUse}
        Random rnd = new Random(7);
        int clock = 0;
        for (int i = 0; i < 20_000; i++) {
            int key = rnd.nextInt(15);
            clock++;
            if (rnd.nextBoolean()) {
                mine.put(key, i);
                if (ref.containsKey(key)) {
                    int[] e = ref.get(key);
                    e[0] = i; e[1]++; e[2] = clock;
                } else {
                    if (ref.size() == cap) {
                        int victim = -1;
                        for (Map.Entry<Integer, int[]> en : ref.entrySet()) {
                            if (victim == -1) { victim = en.getKey(); continue; }
                            int[] a = en.getValue(), b = ref.get(victim);
                            if (a[1] < b[1] || (a[1] == b[1] && a[2] < b[2])) victim = en.getKey();
                        }
                        ref.remove(victim);
                    }
                    ref.put(key, new int[] {i, 1, clock});
                }
            } else {
                Integer got = mine.get(key);
                int[] e = ref.get(key);
                if (e == null) {
                    assertNull(got);
                } else {
                    e[1]++; e[2] = clock;
                    assertEquals(e[0], got);
                }
            }
            assertEquals(ref.size(), mine.size());
        }
    }
}
