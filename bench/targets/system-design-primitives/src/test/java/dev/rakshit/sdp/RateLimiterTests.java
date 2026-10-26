package dev.rakshit.sdp;

import static dev.rakshit.sdp.testkit.Assert.*;

import dev.rakshit.sdp.ratelimit.SlidingWindowCounter;
import dev.rakshit.sdp.ratelimit.SlidingWindowLog;
import dev.rakshit.sdp.ratelimit.TokenBucket;
import dev.rakshit.sdp.testkit.Test;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class RateLimiterTests {

    private static final long SEC = 1_000_000_000L;

    @Test
    void tokenBucketAllowsBurstThenRejects() {
        AtomicLong now = new AtomicLong(0);
        TokenBucket tb = new TokenBucket(5, 1.0, now::get);
        for (int i = 0; i < 5; i++) assertTrue(tb.tryAcquire("u"), "burst request " + i);
        assertFalse(tb.tryAcquire("u"), "bucket should be empty");
    }

    @Test
    void tokenBucketRefillsOverTimeAndCapsAtCapacity() {
        AtomicLong now = new AtomicLong(0);
        TokenBucket tb = new TokenBucket(5, 2.0, now::get); // 2 tokens per second
        for (int i = 0; i < 5; i++) tb.tryAcquire("u");
        now.set(SEC);                                       // +2 tokens
        assertTrue(tb.tryAcquire("u"));
        assertTrue(tb.tryAcquire("u"));
        assertFalse(tb.tryAcquire("u"));
        now.set(100 * SEC);                                 // long idle: refill is capped at 5
        int ok = 0;
        while (tb.tryAcquire("u")) ok++;
        assertEquals(5, ok);
    }

    @Test
    void tokenBucketKeysAreIndependent() {
        AtomicLong now = new AtomicLong(0);
        TokenBucket tb = new TokenBucket(1, 1.0, now::get);
        assertTrue(tb.tryAcquire("a"));
        assertFalse(tb.tryAcquire("a"));
        assertTrue(tb.tryAcquire("b"));
    }

    @Test
    void tokenBucketMultiPermitIsAllOrNothing() {
        AtomicLong now = new AtomicLong(0);
        TokenBucket tb = new TokenBucket(5, 1.0, now::get);
        assertFalse(tb.tryAcquire("u", 6), "more than capacity can never succeed");
        assertTrue(tb.tryAcquire("u", 3));
        assertFalse(tb.tryAcquire("u", 3), "only 2 left, and a failed attempt must not consume them");
        assertTrue(tb.tryAcquire("u", 2));
    }

    /** 200 threads race for 50 tokens with no refill time passing: exactly 50 must win. */
    @Test
    void tokenBucketNeverOverGrantsUnderConcurrency() throws Exception {
        AtomicLong now = new AtomicLong(0);
        TokenBucket tb = new TokenBucket(50, 1.0, now::get);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger granted = new AtomicInteger();
        for (int i = 0; i < 200; i++) {
            pool.submit(() -> {
                if (tb.tryAcquire("shared")) granted.incrementAndGet();
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "pool did not finish");
        assertEquals(50, granted.get());
    }

    @Test
    void slidingLogIsExactAtWindowEdges() {
        AtomicLong now = new AtomicLong(0);
        SlidingWindowLog sl = new SlidingWindowLog(3, 10 * SEC, now::get);
        assertTrue(sl.tryAcquire("u"));        // t=0
        now.set(4 * SEC);
        assertTrue(sl.tryAcquire("u"));        // t=4
        now.set(9 * SEC);
        assertTrue(sl.tryAcquire("u"));        // t=9
        now.set(9 * SEC + 1);
        assertFalse(sl.tryAcquire("u"));       // 3 in the last 10s
        now.set(10 * SEC);                     // the t=0 request has just left the window
        assertTrue(sl.tryAcquire("u"));
        assertFalse(sl.tryAcquire("u"));
    }

    /** The classic fixed-window flaw: a burst straddling the boundary doubles the limit. Sliding must not. */
    @Test
    void slidingLogHasNoBoundaryBurst() {
        AtomicLong now = new AtomicLong(0);
        SlidingWindowLog sl = new SlidingWindowLog(5, 10 * SEC, now::get);
        now.set(9 * SEC);
        int first = 0;
        for (int i = 0; i < 10; i++) if (sl.tryAcquire("u")) first++;
        now.set(11 * SEC);                     // a fixed window would reset here
        int second = 0;
        for (int i = 0; i < 10; i++) if (sl.tryAcquire("u")) second++;
        assertEquals(5, first);
        assertEquals(0, second);
    }

    @Test
    void slidingCounterBlocksBoundaryBurst() {
        AtomicLong now = new AtomicLong(0);
        SlidingWindowCounter sc = new SlidingWindowCounter(10, 10 * SEC, now::get);
        now.set(9 * SEC);                       // last second of window 0
        int first = 0;
        for (int i = 0; i < 20; i++) if (sc.tryAcquire("u")) first++;
        assertEquals(10, first);
        now.set(11 * SEC);                      // 10% into window 1: previous counts for 90% -> ~9 used
        int second = 0;
        for (int i = 0; i < 20; i++) if (sc.tryAcquire("u")) second++;
        assertTrue(second <= 1, "expected at most 1 request right after the boundary, got " + second);
    }

    @Test
    void slidingCounterRecoversAfterQuietPeriod() {
        AtomicLong now = new AtomicLong(0);
        SlidingWindowCounter sc = new SlidingWindowCounter(4, 10 * SEC, now::get);
        for (int i = 0; i < 4; i++) assertTrue(sc.tryAcquire("u"));
        assertFalse(sc.tryAcquire("u"));
        now.set(35 * SEC);                      // several windows later, history is stale
        for (int i = 0; i < 4; i++) assertTrue(sc.tryAcquire("u"));
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowLog(0, SEC));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(1, 1).tryAcquire("u", 0));
    }
}
