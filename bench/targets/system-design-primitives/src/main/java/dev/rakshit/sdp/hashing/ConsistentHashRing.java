package dev.rakshit.sdp.hashing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Consistent hash ring with virtual nodes.
 *
 * <p>Problem it solves: with {@code hash(key) % N} servers, changing N remaps almost every key, so a
 * cache cluster loses nearly everything when one node is added or removed. On a ring, a key belongs to
 * the first node clockwise from its hash, so adding or removing a node only moves the keys in the arc
 * next to it, about {@code 1/N} of them.
 *
 * <p>Why virtual nodes: with one point per node the arcs are very uneven. Placing each node at
 * {@code replicas} pseudo-random points smooths the load, and gives a way to weight bigger machines.
 *
 * <p>Lookup is O(log(N * replicas)) using a {@link TreeMap}'s {@code ceilingEntry}. Not thread-safe
 * for concurrent add/remove; guard externally or swap in an immutable copy.
 */
public final class ConsistentHashRing<N> {

    private final int replicas;
    private final TreeMap<Long, N> ring = new TreeMap<>();
    private final Set<N> nodes = new HashSet<>();

    public ConsistentHashRing(int replicas) {
        if (replicas <= 0) throw new IllegalArgumentException("replicas must be positive");
        this.replicas = replicas;
    }

    public void addNode(N node) {
        if (!nodes.add(node)) return;
        for (int i = 0; i < replicas; i++) ring.put(hash(node + "#" + i), node);
    }

    public void removeNode(N node) {
        if (!nodes.remove(node)) return;
        for (int i = 0; i < replicas; i++) ring.remove(hash(node + "#" + i));
    }

    /** The node responsible for {@code key}. */
    public N getNode(String key) {
        if (ring.isEmpty()) throw new IllegalStateException("ring has no nodes");
        Map.Entry<Long, N> e = ring.ceilingEntry(hash(key));
        return (e != null ? e : ring.firstEntry()).getValue(); // wrap around past the largest point
    }

    /**
     * Up to {@code count} distinct nodes for replication: the owner, then the next distinct nodes
     * clockwise.
     */
    public List<N> getNodes(String key, int count) {
        if (ring.isEmpty()) throw new IllegalStateException("ring has no nodes");
        int want = Math.min(count, nodes.size());
        List<N> out = new ArrayList<>(want);
        Set<N> seen = new HashSet<>();
        long h = hash(key);
        for (N n : ring.tailMap(h, true).values()) {
            if (seen.add(n)) out.add(n);
            if (out.size() == want) return out;
        }
        for (N n : ring.values()) { // wrap around
            if (seen.add(n)) out.add(n);
            if (out.size() == want) return out;
        }
        return out;
    }

    public int nodeCount() {
        return nodes.size();
    }

    private static long hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) h = (h << 8) | (d[i] & 0xFF);
            return h;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // MD5 is required to exist on every JVM
        }
    }
}
