package gt.lupa.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class E22MetricsTest {
    @Test
    void countersAreBoundedToFixedMonotonicScalars() {
        E22Metrics metrics = new E22Metrics();

        metrics.recordHeaderTimeout();
        metrics.recordHelloTimeout();
        metrics.recordPongTimeout();
        metrics.recordReleaseTimeout();
        metrics.recordWriteProgressTimeout();
        metrics.recordConnectionRelease();
        metrics.recordWebSocketRelease();
        metrics.recordSessionRelease();
        metrics.recordSessionCleanupRun();
        metrics.recordLateCallbackDiscarded();

        E22Metrics.Snapshot snapshot = metrics.snapshot();

        assertEquals(1, snapshot.headerTimeouts());
        assertEquals(1, snapshot.helloTimeouts());
        assertEquals(1, snapshot.pongTimeouts());
        assertEquals(1, snapshot.releaseTimeouts());
        assertEquals(1, snapshot.writeProgressTimeouts());
        assertEquals(1, snapshot.connectionReleases());
        assertEquals(1, snapshot.webSocketReleases());
        assertEquals(1, snapshot.sessionReleases());
        assertEquals(1, snapshot.sessionCleanupRuns());
        assertEquals(1, snapshot.lateCallbacksDiscarded());
    }
}
