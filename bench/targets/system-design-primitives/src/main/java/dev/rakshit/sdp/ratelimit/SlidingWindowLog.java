package dev.rakshit.sdp.ratelimit;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Sliding window log: remembers the timestamp of every accepted request in the last
 * {@code windowNanos} and allows a new one only if fewer than {@code limit} are still inside the window.
 *
 * <p>Trade-off: exact (never lets more than {@code limit} through in any window of that length, which
 * fixed windows cannot promise at their boundaries) but memory is O(limit) per key.
 * See {@link SlidingWindowCounter} for the cheap approximation.
 */
public final class SlidingWindowLog implements RateLimiter {

    private final int limit;
    private final long windowNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, ArrayDeque<Long>> logs = new ConcurrentHashMap<>();

    public SlidingWindowLog(int limit, long windowNanos) {
        this(limit, windowNanos, System::nanoTime);
    }

    public SlidingWindowLog(int limit, long windowNanos, LongSupplier nanoClock) {
        if (limit <= 0 || windowNanos <= 0) throw new IllegalArgumentException("limit and window must be positive");
        this.limit = limit;
        this.windowNanos = windowNanos;
        this.nanoClock = nanoClock;
    }

    @Override
    public boolean tryAcquire(String key, int permits) {
        if (permits <= 0) throw new IllegalArgumentException("permits must be positive");
        if (permits > limit) return false;
        long now = nanoClock.getAsLong();
        ArrayDeque<Long> log = logs.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (log) {
            // Timestamps are appended in order, so expired ones are always at the front.
            while (!log.isEmpty() && now - log.peekFirst() >= windowNanos) log.pollFirst();
            if (log.size() + permits > limit) return false;
            for (int i = 0; i < permits; i++) log.addLast(now);
            return true;
        }
    }
}
