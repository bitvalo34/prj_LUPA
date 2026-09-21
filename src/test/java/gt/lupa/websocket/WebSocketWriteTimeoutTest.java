package gt.lupa.websocket;

import gt.lupa.test.ManualMonotonicScheduler;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketWriteTimeoutTest {
    @Test
    void committedWriteFailsAfterNoProgressDeadline() {
        ManualMonotonicScheduler scheduler =
                new ManualMonotonicScheduler();
        AtomicReference<CompletionHandler<Integer, Void>> held =
                new AtomicReference<>();
        AtomicInteger failures =
                new AtomicInteger();

        WebSocketWriteQueue queue =
                new WebSocketWriteQueue(
                        (buffer, handler) -> held.set(handler),
                        4,
                        scheduler,
                        Duration.ofSeconds(3),
                        ignored -> failures.incrementAndGet());

        WebSocketWriteQueue.WriteHandle handle =
                queue.enqueueTracked(
                        ByteBuffer.wrap(new byte[16]),
                        () -> {},
                        () -> fail("write should not complete"));

        assertTrue(handle.accepted());
        assertTrue(handle.committed());
        assertNotNull(held.get());

        scheduler.advance(Duration.ofSeconds(2));
        assertEquals(0, failures.get());

        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(1, failures.get());
        assertEquals(0, queue.queuedFrames());
    }

    @Test
    void positivePartialProgressRenewsDeadlineButZeroDoesNot() {
        ManualMonotonicScheduler scheduler =
                new ManualMonotonicScheduler();
        AtomicReference<CompletionHandler<Integer, Void>> held =
                new AtomicReference<>();
        AtomicInteger failures =
                new AtomicInteger();

        WebSocketWriteQueue queue =
                new WebSocketWriteQueue(
                        (buffer, handler) -> held.set(handler),
                        4,
                        scheduler,
                        Duration.ofSeconds(3),
                        ignored -> failures.incrementAndGet());

        queue.enqueue(
                ByteBuffer.wrap(new byte[8]),
                () -> fail("should not finish in this test"));

        scheduler.advance(Duration.ofSeconds(2));
        held.get().completed(2, null);

        scheduler.advance(Duration.ofSeconds(2));
        assertEquals(
                0,
                failures.get(),
                "positive progress must renew the deadline");

        held.get().completed(0, null);

        scheduler.advance(Duration.ofSeconds(1));
        assertEquals(
                1,
                failures.get(),
                "zero-byte completions must not renew progress deadline");
    }
}
