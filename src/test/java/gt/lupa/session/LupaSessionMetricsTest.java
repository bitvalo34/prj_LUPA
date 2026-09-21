package gt.lupa.session;

import gt.lupa.concurrent.TileReadAdmission;
import gt.lupa.concurrent.TransientBufferBudget;
import gt.lupa.diagnostics.E22Metrics;
import gt.lupa.storage.PublishedImageStore;
import gt.lupa.storage.TileData;
import gt.lupa.storage.TileReader;
import gt.lupa.test.ManualMonotonicScheduler;
import gt.lupa.websocket.WebSocketEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LupaSessionMetricsTest {
    @TempDir Path temp;

    @Test
    void helloTimeoutAndCleanupAreCountedOnce() {
        ManualMonotonicScheduler scheduler =
                new ManualMonotonicScheduler();
        E22Metrics metrics =
                new E22Metrics();

        LupaSession session =
                session(scheduler, metrics);

        Sender sender = new Sender();
        session.onOpen(sender);

        scheduler.advance(Duration.ofSeconds(5));
        session.onClosed(1008, "timeout cleanup");
        session.onClosed(1008, "duplicate cleanup");

        E22Metrics.Snapshot snapshot =
                metrics.snapshot();

        assertEquals(1, snapshot.helloTimeouts());
        assertEquals(1, snapshot.sessionCleanupRuns());
        assertEquals(0, snapshot.sessionReleases());
    }

    @Test
    void admittedSessionReleaseIsCountedExactlyOnce() {
        ManualMonotonicScheduler scheduler =
                new ManualMonotonicScheduler();
        E22Metrics metrics =
                new E22Metrics();

        LupaSession session =
                session(scheduler, metrics);

        Sender sender = new Sender();
        session.onOpen(sender);
        session.onText(
                sender,
                "{\"type\":\"HELLO\",\"version\":1,"
                        + "\"windowBytes\":524288,"
                        + "\"bitmapBudgetBytes\":67108864}");

        session.onClosed(1000, "normal");
        session.onClosed(1000, "duplicate");

        E22Metrics.Snapshot snapshot =
                metrics.snapshot();

        assertEquals(1, snapshot.sessionReleases());
        assertEquals(1, snapshot.sessionCleanupRuns());
    }

    private LupaSession session(
            ManualMonotonicScheduler scheduler,
            E22Metrics metrics) {
        PublishedImageStore store =
                new PublishedImageStore(
                        temp.resolve("data"));

        TileReader unused =
                (opened, z, x, y) ->
                        new TileData(
                                new byte[]{1, 2, 3, 4},
                                1,
                                1);

        return new LupaSession(
                store,
                unused,
                Runnable::run,
                Runnable::run,
                Runnable::run,
                new SessionAdmission(1),
                new TileReadAdmission(1, 1),
                new TransientBufferBudget(1024 * 1024),
                scheduler,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                metrics);
    }

    private static final class Sender
            implements WebSocketEndpoint.Sender {
        @Override
        public boolean sendText(String text) {
            return true;
        }

        @Override
        public boolean sendBinary(byte[] payload) {
            return true;
        }

        @Override
        public void close(int code, String reason) {}
    }
}
