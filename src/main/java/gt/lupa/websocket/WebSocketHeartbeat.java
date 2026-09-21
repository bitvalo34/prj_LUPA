package gt.lupa.websocket;

import gt.lupa.concurrent.MonotonicScheduler;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One-at-a-time server WebSocket heartbeat.
 *
 * The pong deadline begins only after the ping frame has completed its socket
 * write. An unrelated pong never satisfies the pending heartbeat.
 */
final class WebSocketHeartbeat implements AutoCloseable {
    @FunctionalInterface
    interface PingSender {
        boolean send(byte[] payload, Runnable onWritten);
    }

    private final MonotonicScheduler scheduler;
    private final Duration pingInterval;
    private final Duration pongTimeout;
    private final PingSender sender;
    private final Runnable onTimeout;
    private final AtomicLong nonce = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    private final Object lock = new Object();
    private byte[] pendingPing;
    private MonotonicScheduler.Handle pingTimer;
    private MonotonicScheduler.Handle pongTimer;
    private long sentPings;
    private long validPongs;
    private long ignoredPongs;
    private long timeouts;

    WebSocketHeartbeat(
            MonotonicScheduler scheduler,
            Duration pingInterval,
            Duration pongTimeout,
            PingSender sender,
            Runnable onTimeout) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.pingInterval = requirePositive(pingInterval, "pingInterval");
        this.pongTimeout = requirePositive(pongTimeout, "pongTimeout");
        this.sender = Objects.requireNonNull(sender);
        this.onTimeout = Objects.requireNonNull(onTimeout);
    }

    void start() {
        synchronized (lock) {
            if (closed.get() || pingTimer != null || pendingPing != null) return;
            pingTimer = scheduler.schedule(pingInterval, this::sendPing);
        }
    }

    void onPong(byte[] payload) {
        Objects.requireNonNull(payload);
        boolean matched = false;

        synchronized (lock) {
            if (closed.get()) return;
            if (pendingPing != null && Arrays.equals(pendingPing, payload)) {
                pendingPing = null;
                validPongs++;
                cancelPongTimerLocked();
                matched = true;
            } else {
                ignoredPongs++;
            }
        }

        if (matched) {
            scheduleNextPing();
        }
    }

    Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(
                    sentPings,
                    validPongs,
                    ignoredPongs,
                    timeouts,
                    pendingPing != null);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (lock) {
            cancelPingTimerLocked();
            cancelPongTimerLocked();
            pendingPing = null;
        }
    }

    private void sendPing() {
        final byte[] payload;

        synchronized (lock) {
            pingTimer = null;
            if (closed.get() || pendingPing != null) return;

            long value = nonce.incrementAndGet();
            payload = ByteBuffer.allocate(Long.BYTES).putLong(value).array();
            pendingPing = payload;
            sentPings++;
        }

        boolean accepted = sender.send(
                payload,
                () -> onPingWritten(payload));

        if (!accepted) {
            synchronized (lock) {
                if (pendingPing != null && Arrays.equals(pendingPing, payload)) {
                    pendingPing = null;
                }
            }
            timeout();
        }
    }

    private void onPingWritten(byte[] payload) {
        synchronized (lock) {
            if (closed.get()) return;
            if (pendingPing == null || !Arrays.equals(pendingPing, payload)) {
                return;
            }
            cancelPongTimerLocked();
            pongTimer = scheduler.schedule(
                    pongTimeout,
                    () -> onPongDeadline(payload));
        }
    }

    private void onPongDeadline(byte[] payload) {
        synchronized (lock) {
            pongTimer = null;
            if (closed.get()) return;
            if (pendingPing == null || !Arrays.equals(pendingPing, payload)) {
                return;
            }
            pendingPing = null;
            timeouts++;
        }
        onTimeout.run();
    }

    private void timeout() {
        synchronized (lock) {
            if (closed.get()) return;
            timeouts++;
        }
        onTimeout.run();
    }

    private void scheduleNextPing() {
        synchronized (lock) {
            if (closed.get() || pendingPing != null || pingTimer != null) return;
            pingTimer = scheduler.schedule(pingInterval, this::sendPing);
        }
    }

    private void cancelPingTimerLocked() {
        if (pingTimer != null) {
            pingTimer.cancel();
            pingTimer = null;
        }
    }

    private void cancelPongTimerLocked() {
        if (pongTimer != null) {
            pongTimer.cancel();
            pongTimer = null;
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    record Snapshot(
            long sentPings,
            long validPongs,
            long ignoredPongs,
            long timeouts,
            boolean pingOutstanding) {}
}
