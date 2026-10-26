package dev.rakshit.sdp;

import static dev.rakshit.sdp.testkit.Assert.*;

import dev.rakshit.sdp.bloom.BloomFilter;
import dev.rakshit.sdp.hashing.ConsistentHashRing;
import dev.rakshit.sdp.testkit.Test;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HashingAndBloomTests {

    private static final int KEYS = 20_000;

    private static Map<String, String> assign(ConsistentHashRing<String> ring) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < KEYS; i++) m.put("key-" + i, ring.getNode("key-" + i));
        return m;
    }

    @Test
    void sameKeyAlwaysMapsToSameNode() {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(100);
        for (String n : List.of("a", "b", "c")) ring.addNode(n);
        assertEquals(ring.getNode("user:42"), ring.getNode("user:42"));
    }

    /** Largest relative deviation of any node's load from the ideal share. */
    private static double maxLoadDeviation(int replicas) {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(replicas);
        for (int i = 0; i < 5; i++) ring.addNode("node" + i);
        Map<String, Integer> load = new HashMap<>();
        for (String owner : assign(ring).values()) load.merge(owner, 1, Integer::sum);
        double ideal = KEYS / 5.0, worst = 0;
        for (int count : load.values()) worst = Math.max(worst, Math.abs(count / ideal - 1));
        return worst;
    }

    /**
     * Imbalance shrinks roughly as 1/sqrt(replicas). Measured over 30 random rings: 200 virtual nodes give
     * a mean worst-node deviation of ~10% (up to ~20%), 1000 give ~5% (up to ~10%), so 15% is a safe bound there.
     */
    @Test
    void loadIsBalancedWithEnoughVirtualNodes() {
        assertInRange(maxLoadDeviation(1000), 0.0, 0.15, "worst-node load deviation at 1000 replicas");
    }

    @Test
    void virtualNodesImproveBalanceOverOnePointPerNode() {
        assertTrue(maxLoadDeviation(200) < maxLoadDeviation(1), "200 replicas should balance better than 1");
    }

    /** The point of the data structure: adding a 6th node should move about 1/6 of the keys, not ~5/6. */
    @Test
    void addingANodeMovesOnlyAboutOneNthOfKeys() {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(200);
        for (int i = 0; i < 5; i++) ring.addNode("node" + i);
        Map<String, String> before = assign(ring);
        ring.addNode("node5");
        Map<String, String> after = assign(ring);
        int moved = 0;
        for (String k : before.keySet()) {
            if (!before.get(k).equals(after.get(k))) {
                moved++;
                assertEquals("node5", after.get(k)); // keys only ever move to the new node
            }
        }
        assertInRange(moved / (double) KEYS, 0.10, 0.25, "fraction of keys moved");
    }

    @Test
    void removingANodeMovesOnlyItsOwnKeys() {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(200);
        for (int i = 0; i < 5; i++) ring.addNode("node" + i);
        Map<String, String> before = assign(ring);
        ring.removeNode("node2");
        Map<String, String> after = assign(ring);
        for (String k : before.keySet()) {
            if (!before.get(k).equals("node2")) assertEquals(before.get(k), after.get(k));
            else assertFalse(after.get(k).equals("node2"));
        }
    }

    @Test
    void moduloHashingMovesMostKeysForComparison() {
        int moved = 0;
        for (int i = 0; i < KEYS; i++) {
            int h = ("key-" + i).hashCode() & 0x7fffffff;
            if (h % 5 != h % 6) moved++;
        }
        assertTrue(moved / (double) KEYS > 0.7, "hash % N should remap most keys, remapped " + moved);
    }

    @Test
    void replicaListHasDistinctNodesOwnerFirst() {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>(50);
        for (String n : List.of("a", "b", "c", "d")) ring.addNode(n);
        List<String> three = ring.getNodes("some-key", 3);
        assertEquals(3, three.size());
        assertEquals(3, (int) three.stream().distinct().count());
        assertEquals(ring.getNode("some-key"), three.get(0));
        assertEquals(4, ring.getNodes("some-key", 10).size()); // capped at node count
    }

    @Test
    void emptyRingThrows() {
        assertThrows(IllegalStateException.class, () -> new ConsistentHashRing<String>(10).getNode("x"));
    }

    @Test
    void bloomHasNoFalseNegatives() {
        BloomFilter bf = new BloomFilter(10_000, 0.01);
        for (int i = 0; i < 10_000; i++) bf.add("item-" + i);
        for (int i = 0; i < 10_000; i++) assertTrue(bf.mightContain("item-" + i), "lost item-" + i);
    }

    @Test
    void bloomFalsePositiveRateIsNearTarget() {
        BloomFilter bf = new BloomFilter(10_000, 0.01);
        for (int i = 0; i < 10_000; i++) bf.add("in-" + i);
        int fp = 0, probes = 100_000;
        for (int i = 0; i < probes; i++) if (bf.mightContain("out-" + i)) fp++;
        double rate = fp / (double) probes;
        assertInRange(rate, 0.003, 0.02, "measured false-positive rate");
        assertInRange(bf.expectedFalsePositiveRate(), 0.005, 0.015, "predicted false-positive rate");
    }

    @Test
    void bloomSizingMatchesFormula() {
        BloomFilter bf = new BloomFilter(1000, 0.01);
        assertEquals(9586L, bf.bitCount());  // ceil(-1000 ln(0.01) / ln(2)^2)
        assertEquals(7, bf.hashCount());     // round(9.586 * ln 2)
    }
}
