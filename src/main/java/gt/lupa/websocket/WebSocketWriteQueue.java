package gt.lupa.websocket;

import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Serialized bounded writer: at most one socket.write operation is pending.
 *
 * E21 distinguishes frames that are still only queued from frames already committed to the
 * asynchronous channel. Only the former may be cancelled.
 */
public final class WebSocketWriteQueue {
    private final WriteTarget target;
    private final int maxQueuedFrames;
    private final Consumer<Throwable> onFailure;
    private final Deque<PendingWrite> queue = new ArrayDeque<>();
    private boolean writePending;
    private boolean failed;

    /*
     * Trampoline state. Some deterministic tests complete writes synchronously; keeping the
     * drive loop iterative avoids recursive callback growth.
     */
    private boolean driveActive;
    private boolean driveRequested;

    public WebSocketWriteQueue(WriteTarget target, int maxQueuedFrames, Consumer<Throwable> onFailure) {
        this.target = Objects.requireNonNull(target);
        if (maxQueuedFrames < 1) throw new IllegalArgumentException("maxQueuedFrames must be positive");
        this.maxQueuedFrames = maxQueuedFrames;
        this.onFailure = Objects.requireNonNull(onFailure);
    }

    public boolean enqueue(ByteBuffer frame, Runnable onWritten) {
        return enqueueTracked(frame, () -> {}, onWritten).accepted();
    }

    public WriteHandle enqueueTracked(
            ByteBuffer frame,
            Runnable onCommitted,
            Runnable onWritten) {
        Objects.requireNonNull(frame);
        Objects.requireNonNull(onCommitted);
        Objects.requireNonNull(onWritten);

        PendingWrite pending;
        boolean start;
        synchronized (this) {
            if (failed || queue.size() >= maxQueuedFrames) return WriteHandle.rejected();
            pending = new PendingWrite(frame.duplicate(), onCommitted, onWritten);
            queue.addLast(pending);
            start = !writePending;
            if (start) writePending = true;
        }
        if (start) requestDrive();
        return pending.handle();
    }

    public synchronized int queuedFrames() {
        return queue.size();
    }

    private void requestDrive() {
        synchronized (this) {
            if (failed) return;
            if (driveActive) {
                driveRequested = true;
                return;
            }
            driveActive = true;
        }

        while (true) {
            PendingWrite current;
            Runnable committedCallback = null;

            synchronized (this) {
                if (failed) {
                    driveActive = false;
                    driveRequested = false;
                    return;
                }
                current = queue.peekFirst();
                if (current == null) {
                    writePending = false;
                    driveActive = false;
                    driveRequested = false;
                    return;
                }
                if (current.state == PendingState.QUEUED) {
                    current.state = PendingState.COMMITTED;
                    committedCallback = current.onCommitted;
                }
                driveRequested = false;
            }

            if (committedCallback != null) {
                try {
                    committedCallback.run();
                } catch (RuntimeException e) {
                    fail(e);
                    return;
                }
            }

            try {
                PendingWrite committed = current;
                target.write(committed.buffer, new CompletionHandler<>() {
                    @Override
                    public void completed(Integer written, Void attachment) {
                        if (written == null || written < 0) {
                            failed(new IllegalStateException("socket write returned " + written), attachment);
                            return;
                        }
                        if (committed.buffer.hasRemaining()) {
                            requestDrive();
                            return;
                        }

                        Runnable callback;
                        synchronized (WebSocketWriteQueue.this) {
                            PendingWrite removed = queue.peekFirst();
                            if (removed != committed) {
                                fail(new IllegalStateException("write queue head changed while committed"));
                                return;
                            }
                            queue.removeFirst();
                            committed.state = PendingState.DONE;
                            callback = committed.onWritten;
                        }
                        try {
                            callback.run();
                        } catch (RuntimeException e) {
                            fail(e);
                            return;
                        }
                        requestDrive();
                    }

                    @Override
                    public void failed(Throwable exc, Void attachment) {
                        fail(exc);
                    }
                });
            } catch (RuntimeException e) {
                fail(e);
                return;
            }

            synchronized (this) {
                if (failed) {
                    driveActive = false;
                    driveRequested = false;
                    return;
                }
                if (!driveRequested) {
                    driveActive = false;
                    return;
                }
            }
        }
    }

    private void fail(Throwable exc) {
        boolean notify;
        synchronized (this) {
            notify = !failed;
            failed = true;
            for (PendingWrite pending : queue) {
                if (pending.state == PendingState.QUEUED) pending.state = PendingState.CANCELLED;
                else if (pending.state == PendingState.COMMITTED) pending.state = PendingState.FAILED;
            }
            queue.clear();
            writePending = false;
            driveRequested = false;
        }
        if (notify) onFailure.accept(exc);
    }

    private enum PendingState {
        QUEUED,
        COMMITTED,
        DONE,
        CANCELLED,
        FAILED
    }

    private final class PendingWrite {
        private final ByteBuffer buffer;
        private final Runnable onCommitted;
        private final Runnable onWritten;
        private PendingState state = PendingState.QUEUED;

        private PendingWrite(ByteBuffer buffer, Runnable onCommitted, Runnable onWritten) {
            this.buffer = buffer;
            this.onCommitted = onCommitted;
            this.onWritten = onWritten;
        }

        private WriteHandle handle() {
            return new WriteHandle() {
                @Override
                public boolean accepted() {
                    return true;
                }

                @Override
                public boolean committed() {
                    synchronized (WebSocketWriteQueue.this) {
                        return state == PendingState.COMMITTED
                                || state == PendingState.DONE
                                || state == PendingState.FAILED;
                    }
                }

                @Override
                public boolean cancelIfNotCommitted() {
                    synchronized (WebSocketWriteQueue.this) {
                        if (state != PendingState.QUEUED) return false;
                        if (!queue.remove(PendingWrite.this)) return false;
                        state = PendingState.CANCELLED;
                        return true;
                    }
                }
            };
        }
    }

    public interface WriteHandle extends WebSocketEndpoint.BinarySend {
        static WriteHandle rejected() {
            return RejectedWriteHandle.INSTANCE;
        }
    }

    private enum RejectedWriteHandle implements WriteHandle {
        INSTANCE;

        @Override
        public boolean accepted() {
            return false;
        }

        @Override
        public boolean committed() {
            return false;
        }

        @Override
        public boolean cancelIfNotCommitted() {
            return false;
        }
    }

    @FunctionalInterface
    public interface WriteTarget {
        void write(ByteBuffer buffer, CompletionHandler<Integer, Void> handler);
    }
}
