package dev.rakshit.sdp.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Least-recently-used cache with O(1) get and put.
 *
 * <p>Pattern: a hash map for lookup plus a doubly linked list for recency order. The map gives O(1)
 * access to a node; the list lets us move that node to the front, or drop the tail, in O(1) because
 * a doubly linked list can unlink a node it already holds a reference to.
 *
 * <p>Not thread-safe. Wrap calls in {@code synchronized} or use a striped lock if shared.
 */
public final class LruCache<K, V> {

    private static final class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev, next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> index = new HashMap<>();
    // Sentinels remove every null check: head.next is the most recent, tail.prev the least recent.
    private final Node<K, V> head = new Node<>(null, null);
    private final Node<K, V> tail = new Node<>(null, null);
    private final BiConsumer<K, V> onEvict;

    public LruCache(int capacity) {
        this(capacity, null);
    }

    /** @param onEvict called with the key and value of each entry dropped to make room (not on remove) */
    public LruCache(int capacity, BiConsumer<K, V> onEvict) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.onEvict = onEvict;
        head.next = tail;
        tail.prev = head;
    }

    /** Returns the value and marks the key most recently used, or null if absent. */
    public V get(K key) {
        Node<K, V> n = index.get(key);
        if (n == null) return null;
        unlink(n);
        pushFront(n);
        return n.value;
    }

    /** Inserts or updates the key, making it most recently used. Evicts the LRU entry if full. */
    public void put(K key, V value) {
        Node<K, V> n = index.get(key);
        if (n != null) {
            n.value = value;
            unlink(n);
            pushFront(n);
            return;
        }
        if (index.size() == capacity) {
            Node<K, V> lru = tail.prev;
            unlink(lru);
            index.remove(lru.key);
            if (onEvict != null) onEvict.accept(lru.key, lru.value);
        }
        n = new Node<>(key, value);
        index.put(key, n);
        pushFront(n);
    }

    public V remove(K key) {
        Node<K, V> n = index.remove(key);
        if (n == null) return null;
        unlink(n);
        return n.value;
    }

    public boolean containsKey(K key) {
        return index.containsKey(key); // does not change recency
    }

    public int size() {
        return index.size();
    }

    /** Keys from most to least recently used. Intended for tests and debugging. */
    public List<K> keysByRecency() {
        List<K> out = new ArrayList<>(index.size());
        for (Node<K, V> n = head.next; n != tail; n = n.next) out.add(n.key);
        return out;
    }

    private void unlink(Node<K, V> n) {
        n.prev.next = n.next;
        n.next.prev = n.prev;
    }

    private void pushFront(Node<K, V> n) {
        n.next = head.next;
        n.prev = head;
        head.next.prev = n;
        head.next = n;
    }
}
