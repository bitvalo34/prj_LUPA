package gt.lupa.concurrent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Relative-deadline scheduler backed by a monotonic time source.
 *
 * Production uses System.nanoTime plus ScheduledExecutorService. Tests can
 * inject a deterministic implementation without sleeping.
 */
public interface MonotonicScheduler {
    long nowNanos();

    Handle schedule(Duration delay, Runnable task);

    interface Handle {
        boolean cancel();
    }

    static MonotonicScheduler system(ScheduledExecutorService executor) {
        Objects.requireNonNull(executor);
        return new MonotonicScheduler() {
            @Override
            public long nowNanos() {
                return System.nanoTime();
            }

            @Override
            public Handle schedule(Duration delay, Runnable task) {
                Objects.requireNonNull(delay);
                Objects.requireNonNull(task);
                if (delay.isNegative()) {
                    throw new IllegalArgumentException("delay must not be negative");
                }

                ScheduledFuture<?> future = executor.schedule(
                        task,
                        delay.toNanos(),
                        TimeUnit.NANOSECONDS);
                return () -> future.cancel(false);
            }
        };
    }
}
