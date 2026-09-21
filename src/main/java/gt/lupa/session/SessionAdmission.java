package gt.lupa.session;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global admission budget for LUPA application sessions.
 *
 * A TCP connection does not consume this budget. A WebSocket waiting for HELLO
 * does not consume it either. A valid HELLO acquires one slot and transport
 * termination releases it.
 */
public final class SessionAdmission {
    private final int limit;
    private final Semaphore permits;
    private final AtomicInteger active = new AtomicInteger();

    public SessionAdmission(int limit) {
        if (limit < 1) throw new IllegalArgumentException("session limit must be positive");
        this.limit = limit;
        this.permits = new Semaphore(limit, true);
    }

    public Lease tryAcquire() {
        if (!permits.tryAcquire()) return null;
        active.incrementAndGet();
        return new Lease(this);
    }

    public Snapshot snapshot() {
        return new Snapshot(limit, active.get(), permits.availablePermits());
    }

    private void release() {
        int remaining = active.decrementAndGet();
        if (remaining < 0) {
            active.incrementAndGet();
            throw new IllegalStateException("session admission released more than once");
        }
        permits.release();
    }

    public record Snapshot(int limit, int active, int available) {}

    public static final class Lease implements AutoCloseable {
        private final SessionAdmission owner;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(SessionAdmission owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) owner.release();
        }
    }
}
