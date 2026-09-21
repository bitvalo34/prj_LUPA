package gt.lupa.websocket;

import gt.lupa.test.ManualMonotonicScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketHeartbeatTest {
    @Test
    void pongDeadlineStartsOnlyAfterPingWasActuallyWritten() {
        ManualMonotonicScheduler scheduler =
                new ManualMonotonicScheduler();
        AtomicReference<byte[]> payload =
                new AtomicReference<>();
        AtomicReference<Runnable> written =
                new AtomicReference<>();
        AtomicInteger timeouts =
                new AtomicInteger();

        WebSocketHeartbeat heartbeat =
                new WebSocketHeartbeat(
                        scheduler,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(3),
                        (ping, onWritten) -> {
                            payload.set(ping.clone());
                            written.set(onWritten);
                            return true;
                        },
                        timeouts::incrementAndGet);

        heartbeat.start();
        scheduler.advance(Duration.ofSeconds(5));

        assertNotNull(payload.get());
        assertNotNull(written.get());
        assertEquals(0, timeouts.get());

        scheduler.advance(Duration.ofSeconds(30));
        assertEquals(
                0,
                timeouts.get(),
                "queued ping must not start pong timeout before write completion");

        written.get().run();
        scheduler.advance(Duration.ofSeconds(2));
        assertEquals(0, timeouts.get());

        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(1, timeouts.get());
    }

    @Test
    void onlyMatchingPongSatisfiesOutstandingHeartbeat() {
        ManualMonotonicScheduler scheduler =
                new ManualMonotonicScheduler();
        AtomicReference<byte[]> payload =
                new AtomicReference<>();
        AtomicInteger timeouts =
                new AtomicInteger();

        WebSocketHeartbeat heartbeat =
                new WebSocketHeartbeat(
                        scheduler,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(2),
                        (ping, onWritten) -> {
                            payload.set(ping.clone());
                            onWritten.run();
                            return true;
                        },
                        timeouts::incrementAndGet);

        heartbeat.start();
        scheduler.advance(Duration.ofSeconds(2));

        byte[] unrelated = payload.get().clone();
        unrelated[0] ^= 0x01;
        heartbeat.onPong(unrelated);

        assertTrue(heartbeat.snapshot().pingOutstanding());
        assertEquals(1, heartbeat.snapshot().ignoredPongs());

        heartbeat.onPong(payload.get());

        assertFalse(heartbeat.snapshot().pingOutstanding());
        assertEquals(1, heartbeat.snapshot().validPongs());

        scheduler.advance(Duration.ofSeconds(2));
        assertEquals(0, timeouts.get());
        assertEquals(2, heartbeat.snapshot().sentPings());
    }
}
