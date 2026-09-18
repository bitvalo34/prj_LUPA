package gt.lupa.websocket;

import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;

/** Serialized bounded writer: at most one socket.write operation is pending. */
public final class WebSocketWriteQueue {
    private final WriteTarget target;
    private final int maxQueuedFrames;
    private final Consumer<Throwable> onFailure;
    private final Deque<PendingWrite> queue = new ArrayDeque<>();
    private boolean writePending;
    private boolean failed;

    public WebSocketWriteQueue(WriteTarget target, int maxQueuedFrames, Consumer<Throwable> onFailure) {
        this.target = Objects.requireNonNull(target);
        if (maxQueuedFrames < 1) throw new IllegalArgumentException("maxQueuedFrames must be positive");
        this.maxQueuedFrames = maxQueuedFrames;
        this.onFailure = Objects.requireNonNull(onFailure);
    }

    public boolean enqueue(ByteBuffer frame, Runnable onWritten) {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(onWritten);
        boolean start;
        synchronized (this) {
            if (failed || queue.size() >= maxQueuedFrames) return false;
            queue.addLast(new PendingWrite(frame.duplicate(), onWritten));
            start = !writePending;
            if (start) writePending = true;
        }
        if (start) writeCurrent();
        return true;
    }

    public synchronized int queuedFrames() {
        return queue.size();
    }

    private void writeCurrent() {
        PendingWrite current;
        synchronized (this) {
            if (failed) return;
            current = queue.peekFirst();
            if (current == null) {
                writePending = false;
                return;
            }
        }
        target.write(current.buffer(), new CompletionHandler<>() {
            @Override
            public void completed(Integer written, Void attachment) {
                if (written == null || written < 0) {
                    failed(new IllegalStateException("socket write returned " + written), attachment);
                    return;
                }
                if (current.buffer().hasRemaining()) {
                    target.write(current.buffer(), this);
                    return;
                }
                Runnable callback;
                synchronized (WebSocketWriteQueue.this) {
                    PendingWrite removed = queue.pollFirst();
                    callback = removed == null ? () -> {} : removed.onWritten();
                }
                try {
                    callback.run();
                } catch (RuntimeException e) {
                    fail(e);
                    return;
                }
                writeCurrent();
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                fail(exc);
            }
        });
    }

    private void fail(Throwable exc) {
        boolean notify;
        synchronized (this) {
            notify = !failed;
            failed = true;
            queue.clear();
            writePending = false;
        }
        if (notify) onFailure.accept(exc);
    }

    private record PendingWrite(ByteBuffer buffer, Runnable onWritten) {}

    @FunctionalInterface
    public interface WriteTarget {
        void write(ByteBuffer buffer, CompletionHandler<Integer, Void> handler);
    }
}
