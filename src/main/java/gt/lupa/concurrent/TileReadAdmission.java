package gt.lupa.concurrent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Fair non-blocking admission for tile reads.
 *
 * A permit covers one read from submission until storage returns. Callers never
 * block on a semaphore. When capacity is busy, a bounded FIFO waiter may receive
 * the next transferred permit.
 */
public final class TileReadAdmission {
    private final int maxInFlight;
    private final int maxWaiters;
    private final Deque<Waiter> waiters = new ArrayDeque<>();

    private int inFlight;
    private int highWatermark;
    private long rejected;

    public TileReadAdmission(int maxInFlight, int maxWaiters) {
        if (maxInFlight < 1) throw new IllegalArgumentException("maxInFlight must be positive");
        if (maxWaiters < 1) throw new IllegalArgumentException("maxWaiters must be positive");
        this.maxInFlight = maxInFlight;
        this.maxWaiters = maxWaiters;
    }

    public synchronized AcquireResult acquireOrQueue(Consumer<Lease> onGranted) {
        Objects.requireNonNull(onGranted);

        if (inFlight < maxInFlight) {
            inFlight++;
            highWatermark = Math.max(highWatermark, inFlight);
            return AcquireResult.granted(new Lease(this));
        }

        if (waiters.size() >= maxWaiters) {
            rejected++;
            return AcquireResult.rejected();
        }

        Waiter waiter = new Waiter(onGranted);
        waiters.addLast(waiter);
        return AcquireResult.queued(new WaitHandle(this, waiter));
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                maxInFlight,
                maxWaiters,
                inFlight,
                waiters.size(),
                highWatermark,
                rejected);
    }

    private void release() {
        Waiter next = null;
        synchronized (this) {
            while (!waiters.isEmpty()) {
                Waiter candidate = waiters.removeFirst();
                if (!candidate.cancelled) {
                    next = candidate;
                    break;
                }
            }

            if (next == null) {
                inFlight--;
                if (inFlight < 0) {
                    inFlight++;
                    throw new IllegalStateException("tile read admission released too many permits");
                }
                return;
            }
            // Permit transfers directly; inFlight intentionally remains unchanged.
        }

        Lease transferred = new Lease(this);
        try {
            next.onGranted.accept(transferred);
        } catch (RuntimeException e) {
            transferred.close();
        }
    }

    private synchronized boolean cancel(Waiter waiter) {
        if (waiter.cancelled) return false;
        waiter.cancelled = true;
        return waiters.remove(waiter);
    }

    public record Snapshot(
            int maxInFlight,
            int maxWaiters,
            int inFlight,
            int waiting,
            int highWatermark,
            long rejected) {}

    public record AcquireResult(
            boolean accepted,
            Lease lease,
            WaitHandle waitHandle) {
        private static AcquireResult granted(Lease lease) {
            return new AcquireResult(true, lease, null);
        }

        private static AcquireResult queued(WaitHandle waitHandle) {
            return new AcquireResult(true, null, waitHandle);
        }

        private static AcquireResult rejected() {
            return new AcquireResult(false, null, null);
        }

        public boolean grantedImmediately() {
            return lease != null;
        }

        public boolean queued() {
            return waitHandle != null;
        }
    }

    public static final class Lease implements AutoCloseable {
        private final TileReadAdmission owner;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(TileReadAdmission owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) owner.release();
        }
    }

    public static final class WaitHandle {
        private final TileReadAdmission owner;
        private final Waiter waiter;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private WaitHandle(TileReadAdmission owner, Waiter waiter) {
            this.owner = owner;
            this.waiter = waiter;
        }

        public boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) return false;
            return owner.cancel(waiter);
        }
    }

    private static final class Waiter {
        private final Consumer<Lease> onGranted;
        private boolean cancelled;

        private Waiter(Consumer<Lease> onGranted) {
            this.onGranted = onGranted;
        }
    }
}
