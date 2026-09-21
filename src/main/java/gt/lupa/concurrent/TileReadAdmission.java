package gt.lupa.concurrent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Fair, non-blocking admission for tile-read turns.
 *
 * Each accepted lease represents one tile turn. A named owner may have at most
 * one active or queued turn, so one session cannot fill the disk admission
 * window ahead of the others. Saturated owners wait FIFO without blocking a
 * thread. Releasing a turn transfers the permit directly to the oldest live
 * waiter.
 */
public final class TileReadAdmission {
    private final int maxInFlight;
    private final int maxWaiters;
    private final Deque<Waiter> waiters = new ArrayDeque<>();
    private final Set<Long> outstandingOwners = new HashSet<>();
    private final AtomicLong anonymousOwners = new AtomicLong(Long.MIN_VALUE);

    private int inFlight;
    private int highWatermark;
    private long turnsGranted;
    private long rejected;
    private long duplicateOwnerRejections;

    public TileReadAdmission(int maxInFlight, int maxWaiters) {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        if (maxWaiters < 1) {
            throw new IllegalArgumentException("maxWaiters must be positive");
        }
        this.maxInFlight = maxInFlight;
        this.maxWaiters = maxWaiters;
    }

    /**
     * Compatibility entry point for tests/components without a stable owner id.
     * Every invocation receives a unique synthetic owner.
     */
    public AcquireResult acquireOrQueue(Consumer<Lease> onGranted) {
        return acquireOrQueue(
                anonymousOwners.getAndIncrement(),
                onGranted);
    }

    public synchronized AcquireResult acquireOrQueue(
            long ownerId,
            Consumer<Lease> onGranted) {
        Objects.requireNonNull(onGranted);

        if (outstandingOwners.contains(ownerId)) {
            duplicateOwnerRejections++;
            rejected++;
            return AcquireResult.rejected();
        }

        if (inFlight < maxInFlight) {
            outstandingOwners.add(ownerId);
            inFlight++;
            turnsGranted++;
            highWatermark = Math.max(highWatermark, inFlight);
            return AcquireResult.granted(
                    new Lease(this, ownerId));
        }

        if (waiters.size() >= maxWaiters) {
            rejected++;
            return AcquireResult.rejected();
        }

        outstandingOwners.add(ownerId);
        Waiter waiter = new Waiter(ownerId, onGranted);
        waiters.addLast(waiter);
        return AcquireResult.queued(
                new WaitHandle(this, waiter));
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                maxInFlight,
                maxWaiters,
                inFlight,
                waiters.size(),
                outstandingOwners.size(),
                highWatermark,
                turnsGranted,
                rejected,
                duplicateOwnerRejections);
    }

    private void release(long ownerId) {
        Waiter next = null;

        synchronized (this) {
            if (!outstandingOwners.remove(ownerId)) {
                throw new IllegalStateException(
                        "tile turn released without an outstanding owner");
            }

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
                    throw new IllegalStateException(
                            "tile read admission released too many permits");
                }
                return;
            }

            /*
             * The queued owner's marker stays in outstandingOwners. The permit
             * transfers directly, so inFlight intentionally stays unchanged.
             */
            turnsGranted++;
        }

        Lease transferred = new Lease(this, next.ownerId);
        try {
            next.onGranted.accept(transferred);
        } catch (RuntimeException e) {
            transferred.close();
        }
    }

    private synchronized boolean cancel(Waiter waiter) {
        if (waiter.cancelled) return false;
        waiter.cancelled = true;

        boolean removed = waiters.remove(waiter);
        if (removed) {
            outstandingOwners.remove(waiter.ownerId);
        }
        return removed;
    }

    public record Snapshot(
            int maxInFlight,
            int maxWaiters,
            int inFlight,
            int waiting,
            int outstandingOwners,
            int highWatermark,
            long turnsGranted,
            long rejected,
            long duplicateOwnerRejections) {}

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
        private final long ownerId;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(
                TileReadAdmission owner,
                long ownerId) {
            this.owner = owner;
            this.ownerId = ownerId;
        }

        public long ownerId() {
            return ownerId;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                owner.release(ownerId);
            }
        }
    }

    public static final class WaitHandle {
        private final TileReadAdmission owner;
        private final Waiter waiter;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private WaitHandle(
                TileReadAdmission owner,
                Waiter waiter) {
            this.owner = owner;
            this.waiter = waiter;
        }

        public boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return false;
            }
            return owner.cancel(waiter);
        }
    }

    private static final class Waiter {
        private final long ownerId;
        private final Consumer<Lease> onGranted;
        private boolean cancelled;

        private Waiter(
                long ownerId,
                Consumer<Lease> onGranted) {
            this.ownerId = ownerId;
            this.onGranted = onGranted;
        }
    }
}
