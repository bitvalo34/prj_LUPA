package gt.lupa.http;

import gt.lupa.concurrent.ShutdownSupport;
import gt.lupa.config.ServerConfig;
import gt.lupa.diagnostics.E22Metrics;
import gt.lupa.websocket.WebSocketConnection;
import gt.lupa.websocket.WebSocketEndpoint;
import gt.lupa.websocket.WebSocketHandshake;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

public final class NioHttpServer implements AutoCloseable {
    private final ServerConfig config;
    private final HttpRouter router;
    private final HttpParser parser = new HttpParser();
    private final Semaphore connectionSlots;
    private final Semaphore webSocketSlots;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService timers;
    private final Function<Executor, WebSocketEndpoint> webSocketEndpointFactory;
    private final E22Metrics metrics;
    private final AtomicBoolean running = new AtomicBoolean();
    private AsynchronousChannelGroup ioGroup;
    private AsynchronousServerSocketChannel server;

    public NioHttpServer(ServerConfig config, HttpRouter router) {
        this(
                config,
                router,
                ignored -> new WebSocketEndpoint() {},
                new E22Metrics());
    }

    public NioHttpServer(
            ServerConfig config,
            HttpRouter router,
            Supplier<WebSocketEndpoint> webSocketEndpoints) {
        this(
                config,
                router,
                ignored -> webSocketEndpoints.get(),
                new E22Metrics());
    }

    public NioHttpServer(
            ServerConfig config,
            HttpRouter router,
            Function<Executor, WebSocketEndpoint> webSocketEndpointFactory) {
        this(
                config,
                router,
                webSocketEndpointFactory,
                new E22Metrics());
    }

    public NioHttpServer(
            ServerConfig config,
            HttpRouter router,
            Function<Executor, WebSocketEndpoint> webSocketEndpointFactory,
            E22Metrics metrics) {
        this.config = config;
        this.router = router;
        this.webSocketEndpointFactory = webSocketEndpointFactory;
        this.metrics = java.util.Objects.requireNonNull(metrics);
        this.connectionSlots = new Semaphore(config.maxConnections(), true);
        this.webSocketSlots = new Semaphore(config.maxWebSocketConnections(), true);
        this.workers = new ThreadPoolExecutor(
                config.workerThreads(), config.workerThreads(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.workerQueueCapacity()),
                namedFactory("lupa-worker-"),
                new ThreadPoolExecutor.AbortPolicy());
        this.timers = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(namedFactory("lupa-timer-"));
    }

    public synchronized void start() throws IOException {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("server already started");
        try {
            ioGroup = AsynchronousChannelGroup.withFixedThreadPool(config.ioThreads(), namedFactory("lupa-io-"));
            server = AsynchronousServerSocketChannel.open(ioGroup);
            server.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            server.bind(new InetSocketAddress(config.host(), config.port()), config.maxConnections());
            acceptNext();
        } catch (IOException | RuntimeException e) {
            running.set(false);
            close();
            throw e;
        }
    }

    public int port() {
        try {
            if (server == null) throw new IllegalStateException("server not started");
            return ((InetSocketAddress) server.getLocalAddress()).getPort();
        } catch (IOException e) {
            throw new IllegalStateException("cannot obtain local address", e);
        }
    }

    ServerSnapshot snapshotForTest() {
        return new ServerSnapshot(
                connections.size(),
                config.maxWebSocketConnections() - webSocketSlots.availablePermits(),
                workers.getActiveCount(),
                workers.getQueue().size());
    }

    record ServerSnapshot(
            int activeConnections,
            int activeWebSockets,
            int activeWorkers,
            int queuedWorkerTasks) {}

    E22Metrics.Snapshot metricsSnapshotForTest() {
        return metrics.snapshot();
    }

    ShutdownSnapshot shutdownSnapshotForTest() {
        return new ShutdownSnapshot(
                workers.isTerminated(),
                timers.isTerminated(),
                ioGroup == null || ioGroup.isTerminated());
    }

    record ShutdownSnapshot(
            boolean workersTerminated,
            boolean timersTerminated,
            boolean ioGroupTerminated) {}

    private void acceptNext() {
        if (!running.get()) return;
        server.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
            @Override
            public void completed(AsynchronousSocketChannel socket, Void attachment) {
                if (running.get()) acceptNext();
                if (!running.get()) {
                    closeQuietly(socket);
                    return;
                }
                if (!connectionSlots.tryAcquire()) {
                    closeQuietly(socket);
                    return;
                }
                try {
                    socket.setOption(StandardSocketOptions.TCP_NODELAY, true);
                    Connection connection = new Connection(socket);
                    connections.add(connection);
                    connection.start();
                } catch (IOException | RuntimeException e) {
                    connectionSlots.release();
            metrics.recordConnectionRelease();
                    closeQuietly(socket);
                }
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                if (running.get()) acceptNext();
            }
        });
    }

    @Override
    public synchronized void close() {
        running.set(false);
        if (server != null) closeQuietly(server);
        for (Connection connection : connections.toArray(Connection[]::new)) connection.finish();
        // Drain I/O callbacks before their worker and timer dependencies are stopped.
        if (ioGroup != null) {
            try {
                ioGroup.shutdownNow();
                ioGroup.awaitTermination(
                        2,
                        TimeUnit.SECONDS);
            } catch (IOException ignored) {
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        ShutdownSupport.shutdownNowAndAwait(
                workers,
                Duration.ofSeconds(2));
        ShutdownSupport.shutdownNowAndAwait(
                timers,
                Duration.ofSeconds(2));
    }

    private final class Connection {
        private final AsynchronousSocketChannel socket;
        private final HeaderAccumulator headers = new HeaderAccumulator(config.maxHeaderBytes());
        private final ByteBuffer readBuffer = ByteBuffer.allocate(4096);
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean responseStarted = new AtomicBoolean();
        private final AtomicBoolean webSocketSlotHeld = new AtomicBoolean();
        private ScheduledFuture<?> timeout;

        private Connection(AsynchronousSocketChannel socket) {
            this.socket = socket;
        }

        private void start() {
            Duration duration = config.headerTimeout();
            timeout = timers.schedule(
                    () -> {
                        metrics.recordHeaderTimeout();
                        respondOnce(
                                ResponseFactory.error(
                                        408,
                                        "request headers were not completed in time"),
                                false);
                    },
                    duration.toMillis(),
                    TimeUnit.MILLISECONDS);
            readNext();
        }

        private void readNext() {
            if (finished.get() || responseStarted.get()) return;
            socket.read(readBuffer, null, new CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer read, Void attachment) {
                    if (finished.get() || responseStarted.get()) return;
                    if (read == null || read < 0) {
                        if (headers.size() == 0) finish();
                        else respondOnce(ResponseFactory.error(400, "connection closed before headers completed"), false);
                        return;
                    }
                    if (read == 0) {
                        readNext();
                        return;
                    }
                    readBuffer.flip();
                    HeaderAccumulator.AppendResult result = headers.append(readBuffer);
                    readBuffer.clear();
                    if (result == HeaderAccumulator.AppendResult.TOO_LARGE) {
                        respondOnce(ResponseFactory.error(431, "request headers exceed configured limit"), false);
                    } else if (result == HeaderAccumulator.AppendResult.COMPLETE) {
                        processHeaders();
                    } else {
                        readNext();
                    }
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    finish();
                }
            });
        }

        private void processHeaders() {
            cancelTimeout();
            final HttpRequest request;
            try {
                request = parser.parse(headers.headerBytes());
            } catch (HttpParseException e) {
                respondOnce(ResponseFactory.error(e.statusCode(), e.getMessage()), false);
                return;
            }

            byte[] trailing = headers.trailingData();
            if ("/lupa".equals(request.path())) {
                upgradeToWebSocket(request, trailing);
                return;
            }
            if (trailing.length > 0) {
                respondOnce(ResponseFactory.error(400, "unexpected bytes after HTTP headers"), false);
                return;
            }

            try {
                workers.execute(() -> {
                    if (finished.get()) return;
                    HttpResponse response;
                    try {
                        response = router.route(request);
                    } catch (RuntimeException e) {
                        response = ResponseFactory.error(500, "internal server error");
                    }
                    respondOnce(response, request.method().equals("HEAD"));
                });
            } catch (RejectedExecutionException e) {
                respondOnce(ResponseFactory.error(503, "server work queue is full"), request.method().equals("HEAD"));
            }
        }

        private void upgradeToWebSocket(HttpRequest request, byte[] trailing) {
            WebSocketHandshake.Result result = WebSocketHandshake.evaluate(request, config.webSocketAllowNoOrigin());
            if (result instanceof WebSocketHandshake.Rejected rejected) {
                respondOnce(rejected.response(), request.method().equals("HEAD"));
                return;
            }
            WebSocketHandshake.Accepted accepted = (WebSocketHandshake.Accepted) result;
            if (!webSocketSlots.tryAcquire()) {
                respondOnce(
                        ResponseFactory.error(503, "server WebSocket capacity is full"),
                        request.method().equals("HEAD"));
                return;
            }
            webSocketSlotHeld.set(true);

            if (finished.get() || !responseStarted.compareAndSet(false, true)) {
                releaseWebSocketSlot();
                return;
            }
            cancelTimeout();
            new AsyncWritePump(
                    (buffer, handler) -> socket.write(buffer, null, handler),
                    accepted.encode(),
                    () -> startWebSocket(trailing),
                    ignored -> finish()).start();
        }

        private void startWebSocket(byte[] trailing) {
            if (finished.get()) return;
            final WebSocketEndpoint endpoint;
            try {
                endpoint = webSocketEndpointFactory.apply(workers);
                if (endpoint == null) throw new IllegalStateException("WebSocket endpoint factory returned null");
            } catch (RuntimeException e) {
                finish();
                return;
            }
            new WebSocketConnection(
                    socket,
                    endpoint,
                    timers,
                    config.webSocketCloseTimeout(),
                    config.pingInterval(),
                    config.pongTimeout(),
                    config.writeProgressTimeout(),
                    metrics,
                    this::finish).start(trailing);
        }

        private void respondOnce(HttpResponse response, boolean headOnly) {
            if (finished.get() || !responseStarted.compareAndSet(false, true)) return;
            cancelTimeout();
            new AsyncWritePump(
                    (buffer, handler) -> socket.write(buffer, null, handler),
                    response.encode(headOnly),
                    this::finish,
                    ignored -> finish()).start();
        }

        private void cancelTimeout() {
            ScheduledFuture<?> current = timeout;
            if (current != null) current.cancel(false);
        }

        private void releaseWebSocketSlot() {
            if (webSocketSlotHeld.compareAndSet(true, false)) {
                webSocketSlots.release();
                metrics.recordWebSocketRelease();
            }
        }

        private void finish() {
            if (!finished.compareAndSet(false, true)) return;
            cancelTimeout();
            closeQuietly(socket);
            connections.remove(this);
            releaseWebSocketSlot();
            connectionSlots.release();
        }
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
