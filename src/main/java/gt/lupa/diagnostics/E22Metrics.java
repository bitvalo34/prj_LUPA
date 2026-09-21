package gt.lupa.diagnostics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local, bounded counters for E22 evidence.
 *
 * No payloads, buffers, request bodies or histories are retained here. Every
 * metric is monotonic and represented by one AtomicLong.
 */
public final class E22Metrics {
    private final AtomicLong headerTimeouts = new AtomicLong();
    private final AtomicLong helloTimeouts = new AtomicLong();
    private final AtomicLong pongTimeouts = new AtomicLong();
    private final AtomicLong releaseTimeouts = new AtomicLong();
    private final AtomicLong writeProgressTimeouts = new AtomicLong();
    private final AtomicLong connectionReleases = new AtomicLong();
    private final AtomicLong webSocketReleases = new AtomicLong();
    private final AtomicLong sessionReleases = new AtomicLong();
    private final AtomicLong sessionCleanupRuns = new AtomicLong();
    private final AtomicLong lateCallbacksDiscarded = new AtomicLong();

    public void recordHeaderTimeout() {
        headerTimeouts.incrementAndGet();
    }

    public void recordHelloTimeout() {
        helloTimeouts.incrementAndGet();
    }

    public void recordPongTimeout() {
        pongTimeouts.incrementAndGet();
    }

    public void recordReleaseTimeout() {
        releaseTimeouts.incrementAndGet();
    }

    public void recordWriteProgressTimeout() {
        writeProgressTimeouts.incrementAndGet();
    }

    public void recordConnectionRelease() {
        connectionReleases.incrementAndGet();
    }

    public void recordWebSocketRelease() {
        webSocketReleases.incrementAndGet();
    }

    public void recordSessionRelease() {
        sessionReleases.incrementAndGet();
    }

    public void recordSessionCleanupRun() {
        sessionCleanupRuns.incrementAndGet();
    }

    public void recordLateCallbackDiscarded() {
        lateCallbacksDiscarded.incrementAndGet();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                headerTimeouts.get(),
                helloTimeouts.get(),
                pongTimeouts.get(),
                releaseTimeouts.get(),
                writeProgressTimeouts.get(),
                connectionReleases.get(),
                webSocketReleases.get(),
                sessionReleases.get(),
                sessionCleanupRuns.get(),
                lateCallbacksDiscarded.get());
    }

    public record Snapshot(
            long headerTimeouts,
            long helloTimeouts,
            long pongTimeouts,
            long releaseTimeouts,
            long writeProgressTimeouts,
            long connectionReleases,
            long webSocketReleases,
            long sessionReleases,
            long sessionCleanupRuns,
            long lateCallbacksDiscarded) {}
}
