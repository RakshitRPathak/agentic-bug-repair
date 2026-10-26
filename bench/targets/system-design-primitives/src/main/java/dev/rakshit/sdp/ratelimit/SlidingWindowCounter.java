package dev.rakshit.sdp.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Sliding window counter: keeps just two integers per key, the count in the current fixed window and
 * in the previous one, and estimates the sliding count as
 *
 * <pre>  previous * (1 - fractionOfCurrentWindowElapsed) + current</pre>
 *
 * <p>Trade-off: O(1) memory per key, like a fixed window, but without the boundary burst where a client
 * sends {@code limit} at the end of one window and {@code limit} at the start of the next. The price is
 * that the estimate assumes the previous window's requests were evenly spread, so it can be slightly
 * off for very bursty traffic.
 */
public final class SlidingWindowCounter implements RateLimiter {

    private static final class State {
        long windowIndex;
        long current;
        long previous;
    }

    private final int limit;
    private final long windowNanos;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public SlidingWindowCounter(int limit, long windowNanos) {
        this(limit, windowNanos, System::nanoTime);
    }

    public SlidingWindowCounter(int limit, long windowNanos, LongSupplier nanoClock) {
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
        long index = Math.floorDiv(now, windowNanos);
        State s = states.computeIfAbsent(key, k -> {
            State fresh = new State();
            fresh.windowIndex = index;
            return fresh;
        });
        synchronized (s) {
            if (index != s.windowIndex) {
                // One window later the current count becomes "previous"; two or more later it is stale.
                s.previous = (index - s.windowIndex == 1) ? s.current : 0;
                s.current = 0;
                s.windowIndex = index;
            }
            double elapsedFraction = (double) Math.floorMod(now, windowNanos) / windowNanos;
            double estimate = s.previous * (1.0 - elapsedFraction) + s.current;
            if (estimate + permits > limit) return false;
            s.current += permits;
            return true;
        }
    }
}
