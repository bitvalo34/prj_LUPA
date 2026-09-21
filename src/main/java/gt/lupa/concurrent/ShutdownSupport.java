package gt.lupa.concurrent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Small bounded shutdown helper used by E22-owned executors. */
public final class ShutdownSupport {
    private ShutdownSupport() {}

    public static boolean shutdownNowAndAwait(
            ExecutorService executor,
            Duration timeout) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(timeout);
        if (timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "timeout must not be negative");
        }

        executor.shutdownNow();

        try {
            return executor.awaitTermination(
                    timeout.toNanos(),
                    TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return executor.isTerminated();
        }
    }
}
