package gt.lupa.concurrent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Global accounting budget for compressed TILE envelopes retained outside the
 * shared JPEG cache while queued for / being written to a socket.
 */
public final class TransientBufferBudget {
    private final long maxBytes;

    private long reservedBytes;
    private long highWatermarkBytes;
    private long activeLeases;
    private long rejections;

    public TransientBufferBudget(long maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxBytes = maxBytes;
    }

    public synchronized Lease tryReserve(long bytes) {
        if (bytes < 1 || bytes > maxBytes || reservedBytes > maxBytes - bytes) {
            rejections++;
            return null;
        }

        reservedBytes += bytes;
        activeLeases++;
        highWatermarkBytes = Math.max(highWatermarkBytes, reservedBytes);
        return new Lease(this, bytes);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                maxBytes,
                reservedBytes,
                highWatermarkBytes,
                activeLeases,
                rejections);
    }

    private synchronized void release(long bytes) {
        reservedBytes -= bytes;
        activeLeases--;
        if (reservedBytes < 0 || activeLeases < 0) {
            throw new IllegalStateException("transient buffer accounting underflow");
        }
    }

    public record Snapshot(
            long maxBytes,
            long reservedBytes,
            long highWatermarkBytes,
            long activeLeases,
            long rejections) {}

    public static final class Lease implements AutoCloseable {
        private final TransientBufferBudget owner;
        private final long bytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(TransientBufferBudget owner, long bytes) {
            this.owner = owner;
            this.bytes = bytes;
        }

        public long bytes() {
            return bytes;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) owner.release(bytes);
        }
    }
}
