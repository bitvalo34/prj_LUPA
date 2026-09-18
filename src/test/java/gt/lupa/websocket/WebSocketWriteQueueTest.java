package gt.lupa.websocket;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketWriteQueueTest {
    @Test
    void serializesFramesAndCompletesDeterministicPartialWrites() {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        AtomicInteger pending = new AtomicInteger();
        AtomicInteger maxPending = new AtomicInteger();
        AtomicInteger callbacks = new AtomicInteger();

        WebSocketWriteQueue queue = new WebSocketWriteQueue(
                (buffer, handler) -> {
                    int now = pending.incrementAndGet();
                    maxPending.accumulateAndGet(now, Math::max);
                    int count = Math.min(3, buffer.remaining());
                    byte[] part = new byte[count];
                    buffer.get(part);
                    wire.writeBytes(part);
                    pending.decrementAndGet();
                    handler.completed(count, null);
                },
                4,
                failure -> fail(failure));

        byte[] first = new byte[]{1, 2, 3, 4, 5, 6, 7};
        byte[] second = new byte[]{8, 9, 10, 11};

        assertTrue(queue.enqueue(ByteBuffer.wrap(first), callbacks::incrementAndGet));
        assertTrue(queue.enqueue(ByteBuffer.wrap(second), callbacks::incrementAndGet));

        assertArrayEquals(new byte[]{1,2,3,4,5,6,7,8,9,10,11}, wire.toByteArray());
        assertEquals(2, callbacks.get());
        assertEquals(1, maxPending.get(), "only one write may be pending at a time");
        assertEquals(0, queue.queuedFrames());
    }

    @Test
    void rejectsWhenBoundedQueueIsFull() {
        final CompletionHandler<Integer, Void>[] held = new CompletionHandler[1];
        WebSocketWriteQueue queue = new WebSocketWriteQueue(
                (buffer, handler) -> held[0] = handler,
                1,
                failure -> fail(failure));

        assertTrue(queue.enqueue(ByteBuffer.wrap(new byte[]{1}), () -> {}));
        assertFalse(queue.enqueue(ByteBuffer.wrap(new byte[]{2}), () -> {}));

        held[0].completed(1, null);
        assertEquals(0, queue.queuedFrames());
    }
}
