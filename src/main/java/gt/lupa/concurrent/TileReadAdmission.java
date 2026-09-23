package gt.lupa.concurrent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Non-blocking Deficit Round Robin admission for tile-read turns.
 *
 * A named owner may have at most one active or queued turn. The scheduler learns
 * the compressed byte cost reported by completed reads and uses that value as
 * the next packet-cost estimate for the owner. Smaller flows can therefore make
 * progress without being charged the same as sessions producing much larger
 * JPEG tiles.
 *
 * The implementation never blocks a network callback and keeps the waiter set
 * bounded independently from the executor queue.
 */
public final class TileReadAdmission {
    public static final int DEFAULT_QUANTUM_BYTES = 128 * 1024;
    private static final int MAX_COST_MULTIPLIER = 4;

    private final int maxInFlight;
    private final int maxWaiters;
    private final int quantumBytes;
    private final Deque<Waiter> waiters = new ArrayDeque<>();
    private final Set<Long> outstandingOwners = new HashSet<>();
    private final Map<Long, FlowState> flows = new HashMap<>();
    private final AtomicLong anonymousOwners =
            new AtomicLong(Long.MIN_VALUE);

    private int inFlight;
    private int highWatermark;
    private long turnsGranted;
    private long rejected;
    private long duplicateOwnerRejections;
    private long chargedBytes;
    private long drrVisits;

    public TileReadAdmission(
            int maxInFlight,
            int maxWaiters) {
        this(
                maxInFlight,
                maxWaiters,
                DEFAULT_QUANTUM_BYTES);
    }

    public TileReadAdmission(
            int maxInFlight,
            int maxWaiters,
            int quantumBytes) {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException(
                    "maxInFlight must be positive");
        }
        if (maxWaiters < 1) {
            throw new IllegalArgumentException(
                    "maxWaiters must be positive");
        }
        if (quantumBytes < 1) {
            throw new IllegalArgumentException(
                    "quantumBytes must be positive");
        }

        this.maxInFlight = maxInFlight;
        this.maxWaiters = maxWaiters;
        this.quantumBytes = quantumBytes;
    }

    /**
     * Compatibility entry point for components without a stable owner id.
     * Every invocation receives a synthetic owner and therefore does not reuse
     * historical byte cost.
     */
    public AcquireResult acquireOrQueue(
            Consumer<Lease> onGranted) {
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
            return AcquireResult.rejected(
                    RejectReason.DUPLICATE_OWNER);
        }

        FlowState flow =
                flows.computeIfAbsent(
                        ownerId,
                        ignored ->
                                new FlowState(
                                        quantumBytes));

        if (inFlight < maxInFlight
                && waiters.isEmpty()) {
            outstandingOwners.add(ownerId);
            inFlight++;
            grantAccounting(flow);
            highWatermark =
                    Math.max(
                            highWatermark,
                            inFlight);

            return AcquireResult.granted(
                    new Lease(
                            this,
                            ownerId));
        }

        if (waiters.size() >= maxWaiters) {
            rejected++;
            return AcquireResult.rejected(
                    RejectReason.QUEUE_FULL);
        }

        outstandingOwners.add(ownerId);
        Waiter waiter =
                new Waiter(
                        ownerId,
                        onGranted);

        waiters.addLast(waiter);

        return AcquireResult.queued(
                new WaitHandle(
                        this,
                        waiter));
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                maxInFlight,
                maxWaiters,
                quantumBytes,
                inFlight,
                waiters.size(),
                outstandingOwners.size(),
                highWatermark,
                turnsGranted,
                rejected,
                duplicateOwnerRejections,
                chargedBytes,
                drrVisits);
    }

    private void release(
            long ownerId,
            int actualBytes) {
        Waiter next;

        synchronized (this) {
            if (!outstandingOwners.remove(ownerId)) {
                throw new IllegalStateException(
                        "tile turn released without an outstanding owner");
            }

            FlowState completed =
                    flows.computeIfAbsent(
                            ownerId,
                            ignored ->
                                    new FlowState(
                                            quantumBytes));

            if (actualBytes > 0) {
                int boundedCost =
                        Math.min(
                                actualBytes,
                                Math.multiplyExact(
                                        quantumBytes,
                                        MAX_COST_MULTIPLIER));

                completed.lastCostBytes =
                        boundedCost;
                completed.deficitBytes -=
                        boundedCost;

                long floor =
                        -(long) quantumBytes
                                * MAX_COST_MULTIPLIER;

                if (completed.deficitBytes < floor) {
                    completed.deficitBytes =
                            floor;
                }

                chargedBytes += actualBytes;
            }

            next = selectNextWaiterLocked();

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
             * The permit transfers directly to the selected owner. inFlight
             * therefore remains unchanged.
             */
        }

        Lease transferred =
                new Lease(
                        this,
                        next.ownerId);

        try {
            next.onGranted.accept(
                    transferred);
        } catch (RuntimeException e) {
            transferred.close();
        }
    }

    private Waiter selectNextWaiterLocked() {
        if (waiters.isEmpty()) return null;

        while (!waiters.isEmpty()) {
            Waiter candidate =
                    waiters.removeFirst();

            if (candidate.cancelled) {
                outstandingOwners.remove(
                        candidate.ownerId);
                continue;
            }

            drrVisits++;

            FlowState flow =
                    flows.computeIfAbsent(
                            candidate.ownerId,
                            ignored ->
                                    new FlowState(
                                            quantumBytes));

            flow.deficitBytes =
                    Math.min(
                            (long) quantumBytes
                                    * MAX_COST_MULTIPLIER,
                            flow.deficitBytes
                                    + quantumBytes);

            if (flow.deficitBytes
                    >= flow.lastCostBytes) {
                grantAccounting(flow);
                return candidate;
            }

            /*
             * Not enough deficit yet: rotate this owner to the tail. With a
             * bounded lastCost and positive quantum this loop is guaranteed to
             * grant some live waiter after a finite number of visits.
             */
            waiters.addLast(candidate);
        }

        return null;
    }

    private void grantAccounting(
            FlowState flow) {
        turnsGranted++;

        /*
         * An uncontended flow also receives one quantum for the turn. Exact
         * charging happens on Lease.complete(actualBytes).
         */
        if (flow.deficitBytes
                < flow.lastCostBytes) {
            flow.deficitBytes =
                    Math.min(
                            (long) quantumBytes
                                    * MAX_COST_MULTIPLIER,
                            flow.deficitBytes
                                    + quantumBytes);
        }
    }

    private synchronized boolean cancel(
            Waiter waiter) {
        if (waiter.cancelled) return false;

        waiter.cancelled = true;

        boolean removed =
                waiters.remove(waiter);

        if (removed) {
            outstandingOwners.remove(
                    waiter.ownerId);
        }

        return removed;
    }

    public record Snapshot(
            int maxInFlight,
            int maxWaiters,
            int quantumBytes,
            int inFlight,
            int waiting,
            int outstandingOwners,
            int highWatermark,
            long turnsGranted,
            long rejected,
            long duplicateOwnerRejections,
            long chargedBytes,
            long drrVisits) {}

    public enum RejectReason {
        DUPLICATE_OWNER,
        QUEUE_FULL
    }

    public record AcquireResult(
            boolean accepted,
            Lease lease,
            WaitHandle waitHandle,
            RejectReason rejectReason) {
        private static AcquireResult granted(
                Lease lease) {
            return new AcquireResult(
                    true,
                    lease,
                    null,
                    null);
        }

        private static AcquireResult queued(
                WaitHandle waitHandle) {
            return new AcquireResult(
                    true,
                    null,
                    waitHandle,
                    null);
        }

        private static AcquireResult rejected(
                RejectReason reason) {
            return new AcquireResult(
                    false,
                    null,
                    null,
                    Objects.requireNonNull(reason));
        }

        public boolean grantedImmediately() {
            return lease != null;
        }

        public boolean queued() {
            return waitHandle != null;
        }
    }

    public static final class Lease
            implements AutoCloseable {
        private final TileReadAdmission owner;
        private final long ownerId;
        private final AtomicBoolean released =
                new AtomicBoolean();

        private Lease(
                TileReadAdmission owner,
                long ownerId) {
            this.owner = owner;
            this.ownerId = ownerId;
        }

        public long ownerId() {
            return ownerId;
        }

        /**
         * Completes the turn and charges its actual compressed byte cost.
         */
        public void complete(int actualBytes) {
            if (actualBytes < 0) {
                throw new IllegalArgumentException(
                        "actualBytes must not be negative");
            }

            if (released.compareAndSet(false, true)) {
                owner.release(
                        ownerId,
                        actualBytes);
            }
        }

        /**
         * Releases a failed/cancelled turn without a byte charge.
         */
        @Override
        public void close() {
            complete(0);
        }
    }

    public static final class WaitHandle {
        private final TileReadAdmission owner;
        private final Waiter waiter;
        private final AtomicBoolean cancelled =
                new AtomicBoolean();

        private WaitHandle(
                TileReadAdmission owner,
                Waiter waiter) {
            this.owner = owner;
            this.waiter = waiter;
        }

        public boolean cancel() {
            if (!cancelled.compareAndSet(
                    false,
                    true)) {
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

    private static final class FlowState {
        private long deficitBytes;
        private int lastCostBytes;

        private FlowState(
                int initialCostBytes) {
            this.lastCostBytes =
                    initialCostBytes;
        }
    }
}
