package dev.rakshit.sdp.ratelimit;

/** Decides whether a request from a given client key may proceed right now. */
public interface RateLimiter {

    /** Consumes one unit of allowance for {@code key} if available. */
    default boolean tryAcquire(String key) {
        return tryAcquire(key, 1);
    }

    /** Consumes {@code permits} units atomically: all or nothing. */
    boolean tryAcquire(String key, int permits);
}
