package gt.lupa.concurrent;

import gt.lupa.config.ServerConfig;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded executors for potentially blocking storage operations.
 *
 * Saturation always rejects. It never executes storage work in the caller,
 * because the caller may be a network callback or serialized session task.
 */
public final class StorageExecutors implements AutoCloseable {
    private final ThreadPoolExecutor disk;
    private final ThreadPoolExecutor metadata;
    private final AtomicBoolean closed = new AtomicBoolean();

    public StorageExecutors(ServerConfig config) {
        this(
                config.diskThreads(),
                config.diskQueueCapacity(),
                config.metadataThreads(),
                config.metadataQueueCapacity());
    }

    public StorageExecutors(
            int diskThreads,
            int diskQueueCapacity,
            int metadataThreads,
            int metadataQueueCapacity) {
        if (diskThreads < 1
                || diskQueueCapacity < 1
                || metadataThreads < 1
                || metadataQueueCapacity < 1) {
            throw new IllegalArgumentException("storage thread and queue limits must be positive");
        }

        RejectedExecutionHandler reject = new ThreadPoolExecutor.AbortPolicy();
        this.disk = new ThreadPoolExecutor(
                diskThreads,
                diskThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(diskQueueCapacity),
                namedFactory("lupa-disk-"),
                reject);
        this.metadata = new ThreadPoolExecutor(
                metadataThreads,
                metadataThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(metadataQueueCapacity),
                namedFactory("lupa-metadata-"),
                reject);
    }

    public Executor diskExecutor() {
        return disk;
    }

    public Executor metadataExecutor() {
        return metadata;
    }

    public Snapshot snapshot() {
        return new Snapshot(
                disk.getActiveCount(),
                disk.getQueue().size(),
                metadata.getActiveCount(),
                metadata.getQueue().size());
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        disk.shutdownNow();
        metadata.shutdownNow();
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public record Snapshot(
            int diskActive,
            int diskQueued,
            int metadataActive,
            int metadataQueued) {}
}
