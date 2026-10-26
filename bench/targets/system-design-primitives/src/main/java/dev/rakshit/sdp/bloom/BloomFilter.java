package dev.rakshit.sdp.bloom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Bloom filter: a compact set that can say "definitely not present" or "probably present".
 *
 * <p>Items are never stored. Each one sets {@code k} bits in a bit array; a lookup checks the same
 * {@code k} bits. If any is 0 the item was never added (no false negatives). If all are 1 it was
 * probably added, but other items may have set those bits (a false positive).
 *
 * <p>Sizing for {@code n} expected items and target false-positive rate {@code p}:
 * <pre>  m = -n * ln(p) / (ln 2)^2 bits,   k = (m / n) * ln 2 hash functions</pre>
 *
 * <p>Used in front of slow lookups, for example an LSM store skipping disk reads for keys that are
 * not in a file. Cannot delete items (use a counting Bloom filter for that).
 */
public final class BloomFilter {

    private final long[] bits;
    private final long numBits;
    private final int numHashes;
    private long insertions = 0;

    public BloomFilter(int expectedInsertions, double falsePositiveRate) {
        if (expectedInsertions <= 0) throw new IllegalArgumentException("expectedInsertions must be positive");
        if (falsePositiveRate <= 0 || falsePositiveRate >= 1) throw new IllegalArgumentException("rate must be in (0,1)");
        double ln2 = Math.log(2);
        this.numBits = Math.max(64, (long) Math.ceil(-expectedInsertions * Math.log(falsePositiveRate) / (ln2 * ln2)));
        this.numHashes = Math.max(1, (int) Math.round((double) numBits / expectedInsertions * ln2));
        this.bits = new long[(int) ((numBits + 63) / 64)];
    }

    public void add(String item) {
        long[] h = hashes(item);
        for (int i = 0; i < numHashes; i++) setBit(position(h, i));
        insertions++;
    }

    public boolean mightContain(String item) {
        long[] h = hashes(item);
        for (int i = 0; i < numHashes; i++) {
            if (!getBit(position(h, i))) return false;
        }
        return true;
    }

    public long bitCount() {
        return numBits;
    }

    public int hashCount() {
        return numHashes;
    }

    public long insertions() {
        return insertions;
    }

    /** Expected false-positive rate after the insertions so far: (1 - e^(-k*n/m))^k. */
    public double expectedFalsePositiveRate() {
        return Math.pow(1 - Math.exp(-(double) numHashes * insertions / numBits), numHashes);
    }

    // Kirsch-Mitzenmacher: two independent hashes are enough to simulate k, via g_i = h1 + i * h2.
    private long position(long[] h, int i) {
        long combined = h[0] + (long) i * h[1];
        return Math.floorMod(combined, numBits);
    }

    private void setBit(long pos) {
        bits[(int) (pos >>> 6)] |= 1L << (pos & 63);
    }

    private boolean getBit(long pos) {
        return (bits[(int) (pos >>> 6)] & (1L << (pos & 63))) != 0;
    }

    private static long[] hashes(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            long h1 = 0, h2 = 0;
            for (int i = 0; i < 8; i++) h1 = (h1 << 8) | (d[i] & 0xFF);
            for (int i = 8; i < 16; i++) h2 = (h2 << 8) | (d[i] & 0xFF);
            return new long[] {h1, h2 | 1}; // odd h2 so successive probes don't collapse onto few positions
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
