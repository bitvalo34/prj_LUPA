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
                (buffer, handler) -> {
                    if (buffer.hasRemaining()) buffer.get();
                    held[0] = handler;
                },
                1,
                failure -> fail(failure));

        assertTrue(queue.enqueue(ByteBuffer.wrap(new byte[]{1}), () -> {}));
        assertFalse(queue.enqueue(ByteBuffer.wrap(new byte[]{2}), () -> {}));

        held[0].completed(1, null);
        assertEquals(0, queue.queuedFrames());
    }

    @Test
    void queuedFrameCanBeCancelledBeforeChannelCommit() {
        final CompletionHandler<Integer, Void>[] held = new CompletionHandler[1];
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger written = new AtomicInteger();

        WebSocketWriteQueue queue = new WebSocketWriteQueue(
                (buffer, handler) -> {
                    if (held[0] == null) {
                        if (buffer.hasRemaining()) buffer.get();
                        held[0] = handler;
                    } else {
                        fail("second frame must remain queued until first completes");
                    }
                },
                4,
                failure -> fail(failure));

        WebSocketWriteQueue.WriteHandle first = queue.enqueueTracked(
                ByteBuffer.wrap(new byte[]{1}),
                committed::incrementAndGet,
                written::incrementAndGet);
        WebSocketWriteQueue.WriteHandle second = queue.enqueueTracked(
                ByteBuffer.wrap(new byte[]{2}),
                committed::incrementAndGet,
                written::incrementAndGet);

        assertTrue(first.committed());
        assertFalse(second.committed());
        assertTrue(second.cancelIfNotCommitted());
        assertEquals(1, queue.queuedFrames());

        held[0].completed(1, null);
        assertEquals(1, committed.get());
        assertEquals(1, written.get());
        assertEquals(0, queue.queuedFrames());
    }

    @Test
    void partiallyWrittenFrameIsCommittedAndCannotBeCancelled() {
        final CompletionHandler<Integer, Void>[] held = new CompletionHandler[1];
        final ByteBuffer[] current = new ByteBuffer[1];

        WebSocketWriteQueue queue = new WebSocketWriteQueue(
                (buffer, handler) -> {
                    current[0] = buffer;
                    held[0] = handler;
                },
                4,
                failure -> fail(failure));

        AtomicInteger commits = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        WebSocketWriteQueue.WriteHandle handle = queue.enqueueTracked(
                ByteBuffer.wrap(new byte[]{1,2,3}),
                commits::incrementAndGet,
                writes::incrementAndGet);

        assertTrue(handle.committed());
        assertFalse(handle.cancelIfNotCommitted());
        assertEquals(1, commits.get());

        current[0].get();
        held[0].completed(1, null);
        assertTrue(handle.committed());
        assertFalse(handle.cancelIfNotCommitted());

        current[0].get();
        current[0].get();
        held[0].completed(2, null);

        assertEquals(1, writes.get());
        assertEquals(0, queue.queuedFrames());
    }
}
