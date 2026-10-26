package dev.rakshit.repair.service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A bounded worker pool. A burst of webhooks cannot exhaust memory or CPU: at most {@code workers} repairs run at once,
 * at most {@code capacity} wait, and anything beyond is refused immediately so the caller can answer 429 and the sender
 * retry later (backpressure), instead of the queue growing without limit.
 */
public final class JobQueue implements AutoCloseable {
    public static final class QueueFullException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public QueueFullException() {
            super("job queue is full");
        }
    }

    private final ThreadPoolExecutor pool;
    private final AtomicInteger running = new AtomicInteger(), completed = new AtomicInteger(), rejected = new AtomicInteger();

    public JobQueue(int workers, int capacity) {
        this.pool = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity),
                r -> {
                    Thread t = new Thread(r, "repair-worker");
                    t.setDaemon(true);
                    return t;
                });
    }

    public void submit(Runnable job) {
        try {
            pool.execute(() -> {
                running.incrementAndGet();
                try {
                    job.run();
                } catch (RuntimeException e) {
                    System.err.println("repair job failed: " + e);   // the worker survives; RepairService records the failure on the run
                } finally {
                    running.decrementAndGet();
                    completed.incrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            rejected.incrementAndGet();
            throw new QueueFullException();
        }
    }

    public int queued() {
        return pool.getQueue().size();
    }

    public int running() {
        return running.get();
    }

    public int completed() {
        return completed.get();
    }

    public int rejected() {
        return rejected.get();
    }

    /** Waits for queued and running jobs to finish (used by tests and shutdown). */
    public boolean drain(long timeoutMs) throws InterruptedException {
        pool.shutdown();
        return pool.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
