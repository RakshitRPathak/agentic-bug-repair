package dev.rakshit.sdp.cache;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Least-frequently-used cache with O(1) get and put. Ties between equally frequent keys are broken
 * by recency: the key that was used longest ago is evicted first.
 *
 * <p>Pattern: keep, for every use-count, an insertion-ordered set of the keys that have that count,
 * and track the smallest count currently present ({@code minFreq}). Using a key moves it from bucket
 * f to bucket f+1. The eviction victim is the first key of bucket {@code minFreq}. {@code minFreq}
 * only ever needs updating in two places: it resets to 1 on insert, and it advances by one when the
 * bucket it pointed at empties because its last key was promoted.
 *
 * <p>Not thread-safe.
 */
public final class LfuCache<K, V> {

    private static final class Entry<V> {
        V value;
        int freq = 1;

        Entry(V value) {
            this.value = value;
        }
    }

    private final int capacity;
    private final Map<K, Entry<V>> entries = new HashMap<>();
    private final Map<Integer, LinkedHashSet<K>> buckets = new HashMap<>();
    private int minFreq = 0;

    public LfuCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public V get(K key) {
        Entry<V> e = entries.get(key);
        if (e == null) return null;
        touch(key, e);
        return e.value;
    }

    public void put(K key, V value) {
        Entry<V> e = entries.get(key);
        if (e != null) {
            e.value = value;
            touch(key, e);
            return;
        }
        if (entries.size() == capacity) evictOne();
        entries.put(key, new Entry<>(value));
        buckets.computeIfAbsent(1, f -> new LinkedHashSet<>()).add(key);
        minFreq = 1;
    }

    public int size() {
        return entries.size();
    }

    public boolean containsKey(K key) {
        return entries.containsKey(key);
    }

    /** Use count of a key, or 0 if absent. Intended for tests and debugging. */
    public int frequency(K key) {
        Entry<V> e = entries.get(key);
        return e == null ? 0 : e.freq;
    }

    private void touch(K key, Entry<V> e) {
        LinkedHashSet<K> old = buckets.get(e.freq);
        old.remove(key);
        if (old.isEmpty()) {
            buckets.remove(e.freq);
            if (minFreq == e.freq) minFreq++;
        }
        e.freq++;
        buckets.computeIfAbsent(e.freq, f -> new LinkedHashSet<>()).add(key);
    }

    private void evictOne() {
        LinkedHashSet<K> bucket = buckets.get(minFreq);
        Iterator<K> it = bucket.iterator();
        K victim = it.next();
        it.remove();
        if (bucket.isEmpty()) buckets.remove(minFreq);
        entries.remove(victim);
    }
}
