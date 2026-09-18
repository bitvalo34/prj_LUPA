package gt.lupa.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** RFC 6455 connection lifecycle after the HTTP 101 response has been fully written. */
public final class WebSocketConnection {
    public static final int MAX_CLIENT_MESSAGE_BYTES = 16 * 1024;
    public static final int MAX_SERVER_BINARY_BYTES = 4 + 4096 + 262144;
    private static final int READ_BUFFER_BYTES = 8192;
    private static final int MAX_QUEUED_FRAMES = 64;

    private final AsynchronousSocketChannel socket;
    private final WebSocketEndpoint endpoint;
    private final WebSocketFrameParser parser;
    private final WebSocketTextAssembler textAssembler;
    private final ScheduledExecutorService timers;
    private final Duration closeTimeout;
    private final Runnable onClosed;
    private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
    private final WebSocketWriteQueue writes;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Object stateLock = new Object();
    private boolean closeSent;
    private boolean closeReceived;
    private int closeCode = 1006;
    private String closeReason = "abnormal closure";
    private ScheduledFuture<?> closeTimer;

    public WebSocketConnection(
            AsynchronousSocketChannel socket,
            WebSocketEndpoint endpoint,
            ScheduledExecutorService timers,
            Duration closeTimeout,
            Runnable onClosed) {
        this.socket = Objects.requireNonNull(socket);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.timers = Objects.requireNonNull(timers);
        this.closeTimeout = Objects.requireNonNull(closeTimeout);
        this.onClosed = Objects.requireNonNull(onClosed);
        this.parser = new WebSocketFrameParser(MAX_CLIENT_MESSAGE_BYTES);
        this.textAssembler = new WebSocketTextAssembler(MAX_CLIENT_MESSAGE_BYTES);
        this.writes = new WebSocketWriteQueue(
                (buffer, handler) -> socket.write(buffer, null, handler),
                MAX_QUEUED_FRAMES,
                ignored -> finish());
    }

    public void start(byte[] initialBytes) {
        if (finished.get()) return;
        try {
            endpoint.onOpen(new SenderImpl());
            if (initialBytes != null && initialBytes.length > 0) {
                process(ByteBuffer.wrap(initialBytes));
            }
            if (!finished.get()) readNext();
        } catch (RuntimeException e) {
            fail(1011, "endpoint failed during open");
        }
    }

    public void abort() {
        finish();
    }

    private void readNext() {
        if (finished.get()) return;
        socket.read(readBuffer, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer read, Void attachment) {
                if (finished.get()) return;
                if (read == null || read < 0) {
                    finish();
                    return;
                }
                if (read == 0) {
                    readNext();
                    return;
                }
                readBuffer.flip();
                try {
                    process(readBuffer);
                } finally {
                    readBuffer.clear();
                }
                if (!finished.get()) readNext();
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                finish();
            }
        });
    }

    private void process(ByteBuffer bytes) {
        try {
            List<WebSocketFrame> frames = parser.feed(bytes);
            for (WebSocketFrame frame : frames) {
                if (finished.get()) return;
                handle(frame);
            }
        } catch (WebSocketProtocolException e) {
            fail(e.closeCode(), e.getMessage());
        }
    }

    private void handle(WebSocketFrame frame) throws WebSocketProtocolException {
        switch (frame.opcode()) {
            case 0x8 -> receiveClose(frame.payloadUnsafe());
            case 0x9 -> sendControl(WebSocketFrames.pong(frame.payloadUnsafe()));
            case 0xA -> { /* pong accepted; heartbeat policy belongs to E22 */ }
            case 0x0, 0x1, 0x2 -> receiveData(frame);
            default -> throw new WebSocketProtocolException(1002, "unsupported opcode");
        }
    }

    private void receiveData(WebSocketFrame frame) throws WebSocketProtocolException {
        synchronized (stateLock) {
            if (closeSent || closeReceived) return;
        }
        textAssembler.accept(frame).ifPresent(message -> {
            try {
                endpoint.onText(new SenderImpl(), message);
            } catch (RuntimeException e) {
                fail(1011, "endpoint failed while processing text");
            }
        });
    }

    private void receiveClose(byte[] payload) throws WebSocketProtocolException {
        WebSocketFrames.CloseInfo info = WebSocketFrames.parseClose(payload);
        boolean alreadySent;
        synchronized (stateLock) {
            closeReceived = true;
            closeCode = info.code();
            closeReason = info.reason();
            alreadySent = closeSent;
        }
        if (alreadySent) {
            finish();
            return;
        }
        synchronized (stateLock) {
            closeSent = true;
        }
        ByteBuffer response = payload.length == 0
                ? WebSocketFrames.encode(0x8, new byte[0], true)
                : WebSocketFrames.close(payload);
        if (!writes.enqueue(response, this::finish)) finish();
    }

    private void sendControl(ByteBuffer frame) {
        if (finished.get()) return;
        if (!writes.enqueue(frame, () -> {})) fail(1011, "WebSocket write queue is full");
    }

    private void initiateClose(int code, String reason) {
        synchronized (stateLock) {
            if (finished.get() || closeSent) return;
            closeSent = true;
            closeCode = code;
            closeReason = reason == null ? "" : reason;
        }
        ByteBuffer frame;
        try {
            frame = WebSocketFrames.close(code, closeReason);
        } catch (IllegalArgumentException e) {
            frame = WebSocketFrames.close(1002, "protocol error");
        }
        if (!writes.enqueue(frame, () -> {
            synchronized (stateLock) {
                if (closeReceived) finish();
            }
        })) {
            finish();
            return;
        }
        closeTimer = timers.schedule(this::finish, closeTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void fail(int code, String reason) {
        synchronized (stateLock) {
            if (finished.get()) return;
            if (closeSent) {
                finish();
                return;
            }
            closeSent = true;
            closeCode = code;
            closeReason = reason == null ? "" : reason;
        }
        String boundedReason = closeReason;
        while (boundedReason.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 123 && !boundedReason.isEmpty()) {
            boundedReason = boundedReason.substring(0, boundedReason.length() - 1);
        }
        ByteBuffer close = WebSocketFrames.close(code, boundedReason);
        if (!writes.enqueue(close, this::finish)) finish();
    }

    private void finish() {
        if (!finished.compareAndSet(false, true)) return;
        ScheduledFuture<?> timer = closeTimer;
        if (timer != null) timer.cancel(false);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        try {
            endpoint.onClosed(closeCode, closeReason);
        } catch (RuntimeException ignored) {
        }
        onClosed.run();
    }

    private final class SenderImpl implements WebSocketEndpoint.Sender {
        @Override
        public boolean sendText(String text) {
            Objects.requireNonNull(text);
            synchronized (stateLock) {
                if (finished.get() || closeSent) return false;
            }
            return writes.enqueue(WebSocketFrames.text(text), () -> {});
        }

        @Override
        public boolean sendBinary(byte[] payload) {
            Objects.requireNonNull(payload);
            if (payload.length > MAX_SERVER_BINARY_BYTES) {
                throw new IllegalArgumentException("server binary message exceeds LUPA v1 limit");
            }
            synchronized (stateLock) {
                if (finished.get() || closeSent) return false;
            }
            return writes.enqueue(WebSocketFrames.binary(payload), () -> {});
        }

        @Override
        public void close(int code, String reason) {
            initiateClose(code, reason);
        }
    }
}
