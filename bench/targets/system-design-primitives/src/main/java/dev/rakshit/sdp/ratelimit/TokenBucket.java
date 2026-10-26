package dev.rakshit.sdp.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Token bucket: each key owns a bucket holding up to {@code capacity} tokens that refills at
 * {@code refillPerSecond}. A request spends tokens; with none left it is rejected.
 *
 * <p>Trade-off: allows bursts of up to {@code capacity} and then a steady rate, which is usually what
 * an API wants. Memory is O(1) per key, and nothing runs in the background: the bucket is topped up
 * lazily from the elapsed time whenever it is touched.
 *
 * <p>Thread-safe: buckets live in a {@link ConcurrentHashMap} and each bucket is guarded by its own lock,
 * so different keys never contend.
 */
public final class TokenBucket implements RateLimiter {

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefillNanos = now;
        }
    }

    private final double capacity;
    private final double refillPerNano;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucket(int capacity, double refillPerSecond) {
        this(capacity, refillPerSecond, System::nanoTime);
    }

    /** The clock is injectable so tests can move time without sleeping. */
    public TokenBucket(int capacity, double refillPerSecond, LongSupplier nanoClock) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (refillPerSecond <= 0) throw new IllegalArgumentException("refill rate must be positive");
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000.0;
        this.nanoClock = nanoClock;
    }

    @Override
    public boolean tryAcquire(String key, int permits) {
        if (permits <= 0) throw new IllegalArgumentException("permits must be positive");
        if (permits > capacity) return false; // can never succeed, so don't drain the bucket trying
        long now = nanoClock.getAsLong();
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (b) {
            double refilled = (now - b.lastRefillNanos) * refillPerNano;
            if (refilled > 0) {
                b.tokens = Math.min(capacity, b.tokens + refilled);
                b.lastRefillNanos = now;
            }
            if (b.tokens >= permits) {
                b.tokens -= permits;
                return true;
            }
            return false;
        }
    }
}
