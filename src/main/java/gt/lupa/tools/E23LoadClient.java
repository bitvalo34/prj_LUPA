package gt.lupa.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * E23 real WebSocket load client.
 *
 * The client uses the Java 21 WebSocket API for valid traffic only. It validates
 * LUPA control flow and TILE envelopes, sends RELEASE(discarded), coordinates
 * multiple real sessions and writes bounded JSONL/JSON evidence.
 */
public final class E23LoadClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter RUN_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withLocale(Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    private E23LoadClient() {}

    public static void main(String[] args) throws Exception {
        Config config = Config.load(args);
        String runId = config.runId().isBlank()
                ? "e23-" + RUN_TIME.format(Instant.now())
                        + "-c" + config.clients()
                        + "-" + config.scenario()
                        + "-" + config.experimentMode()
                        + "-s" + config.seed()
                : config.runId();

        Path runDir = config.outputDir().resolve(runId).toAbsolutePath().normalize();
        if (Files.exists(runDir)) {
            try (var stream = Files.list(runDir)) {
                if (stream.findAny().isPresent()) {
                    throw new IllegalArgumentException(
                            "result directory already exists and is not empty: " + runDir);
                }
            }
        }
        Files.createDirectories(runDir);

        long processStartNano = System.nanoTime();
        long heapStart = usedHeapBytes();
        try (RunLog log = new RunLog(runDir.resolve("events.jsonl"), processStartNano)) {
            log.event(-1, "RUN_START", node -> {
                node.put("runId", runId);
                node.put("commit", System.getProperty("e23.commit", "unknown"));
                node.put("url", config.url().toString());
                node.put("subprotocol", "lupa.v1");
                node.put("clients", config.clients());
                node.put("scenario", config.scenario());
                node.put("mode", config.experimentMode());
                node.put("imageId", config.imageId());
                node.put("operations", config.operations());
                node.put("viewIntervalMs", config.viewIntervalMs());
                node.put("seed", config.seed());
                node.put("windowBytes", config.windowBytes());
                node.put("bitmapBudgetBytes", config.bitmapBudgetBytes());
                node.put("releaseDelayMs", config.releaseDelayMs());
                node.put("slowReleaseDelayMs", config.slowReleaseDelayMs());
                node.put("jpegValidation", config.jpegValidation());
                node.put("javaVersion", System.getProperty("java.version"));
                node.put("osName", System.getProperty("os.name"));
                node.put("osVersion", System.getProperty("os.version"));
                node.put("heapUsedBytes", heapStart);
            });

            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
                    .build();

            CountDownLatch ready = new CountDownLatch(config.clients());
            CountDownLatch start = new CountDownLatch(1);
            AtomicLong measurementStartNano = new AtomicLong();
            ScheduledExecutorService releases =
                    Executors.newScheduledThreadPool(Math.min(4, Math.max(1, config.clients())));
            ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();

            List<Future<ClientResult>> futures = new ArrayList<>();
            for (int i = 0; i < config.clients(); i++) {
                int clientId = i + 1;
                futures.add(clients.submit(() -> new ClientSession(
                        clientId,
                        config,
                        http,
                        log,
                        ready,
                        start,
                        measurementStartNano,
                        releases).run()));
            }

            boolean allReady = ready.await(config.timeoutMs(), TimeUnit.MILLISECONDS);
            if (!allReady) {
                log.event(-1, "READY_TIMEOUT", node ->
                        node.put("remainingClients", ready.getCount()));
            }

            measurementStartNano.set(System.nanoTime());
            log.event(-1, "MEASUREMENT_START", node ->
                    node.put("readyClients", config.clients() - ready.getCount()));
            start.countDown();

            long futureTimeoutMs = config.timeoutMs()
                    + Math.max(0L, (long) (config.operations() - 1) * config.viewIntervalMs())
                    + config.settleMs()
                    + 5_000L;

            List<ClientResult> results = new ArrayList<>();
            for (Future<ClientResult> future : futures) {
                try {
                    results.add(future.get(futureTimeoutMs, TimeUnit.MILLISECONDS));
                } catch (Exception e) {
                    results.add(ClientResult.failed("future: " + e));
                }
            }

            clients.shutdownNow();
            releases.shutdown();
            releases.awaitTermination(3, TimeUnit.SECONDS);

            long heapEnd = usedHeapBytes();
            Summary summary = Summary.from(
                    runId,
                    config,
                    results,
                    processStartNano,
                    measurementStartNano.get(),
                    heapStart,
                    heapEnd);

            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(runDir.resolve("summary.json").toFile(), summary.toJson());

            writeViewsCsv(runDir.resolve("views.csv"), results);

            log.event(-1, "RUN_END", node -> {
                node.put("successfulClients", summary.successfulClients());
                node.put("failedClients", summary.failedClients());
                node.put("viewsSent", summary.viewsSent());
                node.put("viewsCompleted", summary.viewsCompleted());
                node.put("incompleteViews", summary.incompleteViews());
                node.put("tiles", summary.tiles());
                node.put("jpegBytes", summary.jpegBytes());
                node.put("lupaBinaryBytes", summary.lupaBinaryBytes());
                node.put("obsoleteTiles", summary.obsoleteTiles());
                node.put("obsoleteJpegBytes", summary.obsoleteJpegBytes());
                node.put("releasesSent", summary.releasesSent());
                node.put("errors", summary.errors());
                node.put("heapUsedBytes", heapEnd);
            });

            System.out.printf(
                    Locale.ROOT,
                    "E23_LOAD_CLIENT run=%s clients=%d ok=%d failed=%d views=%d completed=%d "
                            + "tiles=%d jpegBytes=%d obsoleteTiles=%d errors=%d results=%s%n",
                    runId,
                    config.clients(),
                    summary.successfulClients(),
                    summary.failedClients(),
                    summary.viewsSent(),
                    summary.viewsCompleted(),
                    summary.tiles(),
                    summary.jpegBytes(),
                    summary.obsoleteTiles(),
                    summary.errors(),
                    runDir);

            if (summary.failedClients() > 0 || summary.errors() > 0) {
                System.exit(1);
            }
        }
    }

    private static final class ClientSession {
        private final int clientId;
        private final Config config;
        private final HttpClient http;
        private final RunLog log;
        private final CountDownLatch ready;
        private final CountDownLatch start;
        private final AtomicLong measurementStartNano;
        private final ScheduledExecutorService releases;
        private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
        private final Map<Integer, ViewStat> views = new LinkedHashMap<>();
        private final AtomicInteger pendingReleases = new AtomicInteger();
        private boolean readySignalled;
        private int latestEpoch = 1;
        private long controlBytesIn;
        private final AtomicLong controlBytesOut = new AtomicLong();
        private long tiles;
        private long jpegBytes;
        private long lupaBinaryBytes;
        private long obsoleteTiles;
        private long obsoleteJpegBytes;
        private final AtomicLong releasesSent = new AtomicLong();
        private final AtomicLong asyncReleaseErrors = new AtomicLong();
        // Java's WebSocket client permits only one pending send per connection.
        // Serialize every outbound text message (HELLO/OPEN/VIEW/RELEASE) per client.
        private final Object outboundSendLock = new Object();
        private long errors;

        private ClientSession(
                int clientId,
                Config config,
                HttpClient http,
                RunLog log,
                CountDownLatch ready,
                CountDownLatch start,
                AtomicLong measurementStartNano,
                ScheduledExecutorService releases) {
            this.clientId = clientId;
            this.config = config;
            this.http = http;
            this.log = log;
            this.ready = ready;
            this.start = start;
            this.measurementStartNano = measurementStartNano;
            this.releases = releases;
        }

        ClientResult run() {
            WebSocket socket = null;
            String failure = "";
            String imageVersion = "";
            try {
                Listener listener = new Listener(events);
                socket = http.newWebSocketBuilder()
                        .subprotocols("lupa.v1")
                        .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
                        .buildAsync(config.url(), listener)
                        .get(config.connectTimeoutMs(), TimeUnit.MILLISECONDS);

                if (!"lupa.v1".equals(socket.getSubprotocol())) {
                    throw new IllegalStateException(
                            "server did not select lupa.v1: " + socket.getSubprotocol());
                }
                String selectedSubprotocol = socket.getSubprotocol();
                log.event(clientId, "WS_OPEN", node ->
                        node.put("subprotocol", selectedSubprotocol));

                sendText(socket, MAPPER.createObjectNode()
                        .put("type", "HELLO")
                        .put("version", 1)
                        .put("windowBytes", config.windowBytes())
                        .put("bitmapBudgetBytes", config.bitmapBudgetBytes())
                        .toString(),
                        "HELLO", 0);

                JsonNode welcome = awaitControl("WELCOME", config.timeoutMs());
                int negotiatedWindow = requiredInt(welcome, "windowBytes");
                int maxInFlight = requiredInt(welcome, "maxInFlight");
                log.event(clientId, "WELCOME", node -> {
                    node.put("windowBytes", negotiatedWindow);
                    node.put("maxInFlight", maxInFlight);
                    node.put("maxTileBytes", requiredInt(welcome, "maxTileBytes"));
                });

                sendText(socket, MAPPER.createObjectNode()
                        .put("type", "OPEN")
                        .put("epoch", 1)
                        .put("imageId", config.imageId())
                        .toString(),
                        "OPEN", 1);

                JsonNode manifest = awaitControl("MANIFEST", config.timeoutMs());
                imageVersion = requiredText(manifest, "imageVersion");
                int width = requiredInt(manifest, "width");
                int height = requiredInt(manifest, "height");
                String finalImageVersion = imageVersion;
                log.event(clientId, "MANIFEST", node -> {
                    node.put("imageId", config.imageId());
                    node.put("imageVersion", finalImageVersion);
                    node.put("width", width);
                    node.put("height", height);
                    node.put("tileSize", requiredInt(manifest, "tileSize"));
                });

                List<ViewIntent> intents = buildIntents(
                        config,
                        clientId,
                        width,
                        height,
                        imageVersion);

                signalReady();
                start.await(config.timeoutMs(), TimeUnit.MILLISECONDS);
                long measurementStart = measurementStartNano.get();
                if (measurementStart == 0) {
                    throw new IllegalStateException("measurement start was not initialized");
                }

                int nextIntent = 0;
                boolean latestDone = false;
                long latestDoneNano = 0;
                long absoluteDeadline = measurementStart
                        + TimeUnit.MILLISECONDS.toNanos(
                                config.timeoutMs()
                                        + Math.max(0L,
                                        (long) (intents.size() - 1)
                                                * config.viewIntervalMs()));

                while (System.nanoTime() < absoluteDeadline) {
                    long now = System.nanoTime();
                    while (nextIntent < intents.size()
                            && now >= measurementStart + intents.get(nextIntent).offsetNanos()) {
                        ViewIntent intent = intents.get(nextIntent++);
                        latestEpoch = intent.epoch();
                        ViewStat stat = new ViewStat(intent.epoch(), intent.plannedOffsetNanos());
                        views.put(intent.epoch(), stat);
                        stat.viewSentNanos = System.nanoTime();
                        stat.scheduleLagNanos = Math.max(
                                0L,
                                stat.viewSentNanos
                                        - (measurementStart + intent.offsetNanos()));
                        sendText(socket, intent.json(), "VIEW", intent.epoch());
                        log.event(clientId, "VIEW_SENT", node -> {
                            node.put("epoch", intent.epoch());
                            node.put("plannedOffsetNanos", intent.plannedOffsetNanos());
                            node.put("scheduleLagNanos", stat.scheduleLagNanos);
                            node.put("mode", intent.mode());
                        });
                        now = System.nanoTime();
                    }

                    Event event = events.poll(10, TimeUnit.MILLISECONDS);
                    if (event != null) {
                        processEvent(socket, event);
                        ViewStat latest = views.get(latestEpoch);
                        if (nextIntent >= intents.size()
                                && latest != null
                                && latest.doneNanos > 0) {
                            latestDone = true;
                            if (latestDoneNano == 0) latestDoneNano = System.nanoTime();
                        }
                    }

                    if (latestDone
                            && pendingReleases.get() == 0
                            && System.nanoTime() - latestDoneNano
                                    >= TimeUnit.MILLISECONDS.toNanos(config.settleMs())) {
                        break;
                    }
                }

                if (!latestDone) {
                    errors++;
                    log.event(clientId, "CLIENT_TIMEOUT", node -> {
                        node.put("latestEpoch", latestEpoch);
                        node.put("pendingReleases", pendingReleases.get());
                    });
                }

                try {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "E23 load client complete")
                            .get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Evidence already records protocol completion; close is best-effort here.
                }
            } catch (Exception e) {
                failure = e.toString();
                errors++;
                String failureText = failure;
                log.event(clientId, "CLIENT_FAILURE", node ->
                        node.put("message", failureText));
            } finally {
                signalReady();
                if (socket != null && !socket.isOutputClosed()) {
                    try {
                        socket.abort();
                    } catch (RuntimeException ignored) {
                    }
                }
            }

            long totalErrors = errors + asyncReleaseErrors.get();
            boolean ok = failure.isBlank() && totalErrors == 0;
            return new ClientResult(
                    clientId,
                    ok,
                    failure,
                    config.imageId(),
                    imageVersion,
                    List.copyOf(views.values()),
                    controlBytesIn,
                    controlBytesOut.get(),
                    tiles,
                    jpegBytes,
                    lupaBinaryBytes,
                    obsoleteTiles,
                    obsoleteJpegBytes,
                    releasesSent.get(),
                    totalErrors);
        }

        private void signalReady() {
            if (!readySignalled) {
                readySignalled = true;
                ready.countDown();
            }
        }

        private JsonNode awaitControl(String expected, long timeoutMs) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (System.nanoTime() < deadline) {
                Event event = events.poll(50, TimeUnit.MILLISECONDS);
                if (event == null) continue;
                if (event instanceof TextEvent text) {
                    controlBytesIn += text.utf8Bytes();
                    JsonNode node = MAPPER.readTree(text.text());
                    String type = node.path("type").asText();
                    if ("ERROR".equals(type)) {
                        throw new IllegalStateException("server ERROR: " + text.text());
                    }
                    if (expected.equals(type)) return node;
                    throw new IllegalStateException(
                            "expected " + expected + " but received " + text.text());
                }
                if (event instanceof FailureEvent failure) {
                    throw new IllegalStateException("WebSocket failure", failure.error());
                }
                if (event instanceof CloseEvent close) {
                    throw new IllegalStateException(
                            "WebSocket closed code=" + close.statusCode()
                                    + " reason=" + close.reason());
                }
                throw new IllegalStateException("unexpected binary before " + expected);
            }
            throw new IllegalStateException("timeout waiting for " + expected);
        }

        private void processEvent(WebSocket socket, Event event) throws Exception {
            if (event instanceof FailureEvent failure) {
                throw new IllegalStateException("WebSocket listener failed", failure.error());
            }
            if (event instanceof CloseEvent close) {
                throw new IllegalStateException(
                        "WebSocket closed code=" + close.statusCode()
                                + " reason=" + close.reason());
            }
            if (event instanceof TextEvent text) {
                controlBytesIn += text.utf8Bytes();
                JsonNode node = MAPPER.readTree(text.text());
                String type = node.path("type").asText();
                int epoch = node.path("epoch").asInt(-1);

                if ("PLAN".equals(type)) {
                    ViewStat stat = views.get(epoch);
                    if (stat != null && stat.planNanos == 0) {
                        stat.planNanos = System.nanoTime();
                    }
                    log.event(clientId, "PLAN_RECEIVED", out -> {
                        out.put("epoch", epoch);
                        out.put("appliedLevel", node.path("appliedLevel").asInt());
                        out.put("contextLevel", node.path("contextLevel").asInt());
                    });
                    return;
                }

                if ("DONE".equals(type)) {
                    ViewStat stat = views.get(epoch);
                    if (stat != null) {
                        stat.doneNanos = System.nanoTime();
                        stat.doneSentTiles = node.path("sentTiles").asInt(-1);
                    }
                    log.event(clientId, "DONE_RECEIVED", out -> {
                        out.put("epoch", epoch);
                        out.put("sentTiles", node.path("sentTiles").asInt(-1));
                    });
                    return;
                }

                if ("ERROR".equals(type)) {
                    errors++;
                    log.event(clientId, "SERVER_ERROR", out -> {
                        out.put("epoch", epoch);
                        out.put("code", node.path("code").asText());
                        out.put("message", node.path("message").asText());
                    });
                    return;
                }

                throw new IllegalStateException("unexpected control during load: " + text.text());
            }

            BinaryEvent binary = (BinaryEvent) event;
            TileEnvelope tile = decodeTile(binary.payload(), config.jpegValidation());
            long receivedNano = System.nanoTime();
            tiles++;
            jpegBytes += tile.payloadBytes();
            lupaBinaryBytes += binary.payload().length;

            ViewStat stat = views.get(tile.epoch());
            boolean obsolete = tile.epoch() != latestEpoch;
            if (stat != null) {
                stat.tiles++;
                stat.jpegBytes += tile.payloadBytes();
                stat.lupaBinaryBytes += binary.payload().length;
                stat.lastTileNanos = receivedNano;
                if (!obsolete && stat.firstUsefulTileNanos == 0) {
                    stat.firstUsefulTileNanos = receivedNano;
                }
                if (obsolete) {
                    stat.obsoleteTiles++;
                    stat.obsoleteJpegBytes += tile.payloadBytes();
                }
            }

            if (obsolete) {
                obsoleteTiles++;
                obsoleteJpegBytes += tile.payloadBytes();
            }

            log.event(clientId, "TILE_RECEIVED", out -> {
                out.put("deliveryId", tile.deliveryId());
                out.put("epoch", tile.epoch());
                out.put("z", tile.z());
                out.put("x", tile.x());
                out.put("y", tile.y());
                out.put("payloadBytes", tile.payloadBytes());
                out.put("messageBytes", binary.payload().length);
                out.put("obsolete", obsolete);
            });

            long delayMs = effectiveReleaseDelayMs();
            pendingReleases.incrementAndGet();
            releases.schedule(
                    () -> {
                        String release = MAPPER.createObjectNode()
                                .put("type", "RELEASE")
                                .put("deliveryId", tile.deliveryId())
                                .put("status", "discarded")
                                .toString();
                        byte[] releaseBytes = release.getBytes(StandardCharsets.UTF_8);
                        try {
                            synchronized (outboundSendLock) {
                                socket.sendText(release, true)
                                        .join();
                            }
                            controlBytesOut.addAndGet(releaseBytes.length);
                            releasesSent.incrementAndGet();
                            log.event(clientId, "RELEASE_SENT", out -> {
                                out.put("deliveryId", tile.deliveryId());
                                out.put("epoch", tile.epoch());
                                out.put("status", "discarded");
                                out.put("delayMs", delayMs);
                            });
                        } catch (RuntimeException failure) {
                            asyncReleaseErrors.incrementAndGet();
                            log.event(clientId, "RELEASE_SEND_ERROR", out -> {
                                out.put("deliveryId", tile.deliveryId());
                                out.put("epoch", tile.epoch());
                                out.put("message", failure.toString());
                            });
                        } finally {
                            pendingReleases.decrementAndGet();
                        }
                    },
                    delayMs,
                    TimeUnit.MILLISECONDS);
        }

        private long effectiveReleaseDelayMs() {
            if ("slow".equals(config.scenario()) && clientId == 1) {
                return config.slowReleaseDelayMs();
            }
            return config.releaseDelayMs();
        }

        private void sendText(
                WebSocket socket,
                String text,
                String type,
                int epoch) throws Exception {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            synchronized (outboundSendLock) {
                socket.sendText(text, true)
                        .get(config.sendTimeoutMs(), TimeUnit.MILLISECONDS);
            }
            controlBytesOut.addAndGet(bytes.length);
            log.event(clientId, "CONTROL_SENT", node -> {
                node.put("type", type);
                node.put("epoch", epoch);
                node.put("bytes", bytes.length);
            });
        }
    }

    private static List<ViewIntent> buildIntents(
            Config config,
            int clientId,
            int width,
            int height,
            String version) throws Exception {
        int count = "stable".equals(config.scenario()) ? 1 : config.operations();
        Random random = new Random(config.seed() + clientId * 1_000_003L);

        int rectWidth = Math.max(1, Math.min(width, Math.max(256, width * 2 / 3)));
        int rectHeight = Math.max(1, Math.min(height, Math.max(256, height * 2 / 3)));
        int maxX = Math.max(0, width - rectWidth);
        int maxY = Math.max(0, height - rectHeight);
        int viewportWidth = Math.max(1, Math.min(800, rectWidth));
        int viewportHeight = Math.max(1, Math.min(600, rectHeight));

        List<ViewIntent> intents = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int epoch = i + 2;
            double fraction = count == 1 ? 0.5 : (double) i / (count - 1);
            int x;
            int y;

            if ("stable".equals(config.scenario())) {
                x = maxX / 2;
                y = maxY / 2;
            } else {
                x = (int) Math.round(maxX * fraction);
                int deterministicJitter = maxY == 0 ? 0 : random.nextInt(maxY + 1);
                y = maxY == 0
                        ? 0
                        : Math.min(maxY,
                                (int) Math.round(maxY * (1.0 - fraction) * 0.65)
                                        + deterministicJitter / 4);
            }

            String mode = "focus".equals(config.scenario()) ? "focus" : "uniform";
            ObjectNode view = MAPPER.createObjectNode();
            view.put("type", "VIEW");
            view.put("epoch", epoch);
            view.put("imageId", config.imageId());
            view.put("imageVersion", version);

            ObjectNode rect = view.putObject("rect");
            rect.put("x", x);
            rect.put("y", y);
            rect.put("width", rectWidth);
            rect.put("height", rectHeight);

            ObjectNode viewport = view.putObject("viewportPx");
            viewport.put("width", viewportWidth);
            viewport.put("height", viewportHeight);

            view.put("detailOffset", config.detailOffset());
            view.put("mode", mode);
            if ("focus".equals(mode)) {
                ObjectNode focus = view.putObject("focus");
                focus.put("x", x + rectWidth / 2);
                focus.put("y", y + rectHeight / 2);
                focus.put("radiusPx", Math.max(1, Math.min(viewportWidth, viewportHeight) / 5));
            } else {
                view.putNull("focus");
            }

            long offsetNanos =
                    TimeUnit.MILLISECONDS.toNanos((long) i * config.viewIntervalMs());
            intents.add(new ViewIntent(
                    epoch,
                    offsetNanos,
                    offsetNanos,
                    mode,
                    MAPPER.writeValueAsString(view)));
        }
        return List.copyOf(intents);
    }

    private static TileEnvelope decodeTile(
            byte[] envelope,
            String jpegValidation) throws Exception {
        if (envelope.length < 4) {
            throw new IllegalStateException("TILE envelope shorter than 4 bytes");
        }

        ByteBuffer buffer = ByteBuffer.wrap(envelope);
        int headerLength = buffer.getInt();
        if (headerLength < 1 || headerLength > 4096 || headerLength > buffer.remaining()) {
            throw new IllegalStateException("invalid TILE header length: " + headerLength);
        }

        byte[] headerBytes = new byte[headerLength];
        buffer.get(headerBytes);
        JsonNode header = MAPPER.readTree(headerBytes);
        if (!"TILE".equals(header.path("type").asText())) {
            throw new IllegalStateException("binary header type is not TILE");
        }

        int payloadBytes = requiredInt(header, "payloadBytes");
        if (payloadBytes < 1 || payloadBytes > 262_144 || payloadBytes != buffer.remaining()) {
            throw new IllegalStateException(
                    "TILE payload mismatch header=" + payloadBytes
                            + " actual=" + buffer.remaining());
        }
        if (!"jpeg".equals(requiredText(header, "codec"))) {
            throw new IllegalStateException("TILE codec is not jpeg");
        }

        byte[] jpeg = new byte[payloadBytes];
        buffer.get(jpeg);
        if (jpeg.length < 4
                || (jpeg[0] & 0xff) != 0xff
                || (jpeg[1] & 0xff) != 0xd8
                || (jpeg[jpeg.length - 2] & 0xff) != 0xff
                || (jpeg[jpeg.length - 1] & 0xff) != 0xd9) {
            throw new IllegalStateException("TILE payload does not have JPEG SOI/EOI markers");
        }

        int width = requiredInt(header, "w");
        int height = requiredInt(header, "h");
        if ("decode".equals(jpegValidation)) {
            var decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (decoded == null) {
                throw new IllegalStateException("TILE JPEG cannot be decoded");
            }
            if (decoded.getWidth() != width || decoded.getHeight() != height) {
                throw new IllegalStateException("TILE JPEG dimensions disagree with header");
            }
        }

        return new TileEnvelope(
                requiredInt(header, "deliveryId"),
                requiredInt(header, "epoch"),
                requiredInt(header, "z"),
                requiredInt(header, "x"),
                requiredInt(header, "y"),
                width,
                height,
                payloadBytes);
    }

    private static void writeViewsCsv(Path path, List<ClientResult> results)
            throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW)) {
            out.write("clientId,epoch,viewSentNanos,scheduleLagNanos,planNanos,firstUsefulTileNanos,lastTileNanos,doneNanos,tiles,jpegBytes,lupaBinaryBytes,obsoleteTiles,obsoleteJpegBytes,doneSentTiles\n");
            for (ClientResult result : results.stream()
                    .sorted(Comparator.comparingInt(ClientResult::clientId))
                    .toList()) {
                for (ViewStat view : result.views()) {
                    out.write(String.format(
                            Locale.ROOT,
                            "%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                            result.clientId(),
                            view.epoch,
                            view.viewSentNanos,
                            view.scheduleLagNanos,
                            view.planNanos,
                            view.firstUsefulTileNanos,
                            view.lastTileNanos,
                            view.doneNanos,
                            view.tiles,
                            view.jpegBytes,
                            view.lupaBinaryBytes,
                            view.obsoleteTiles,
                            view.obsoleteJpegBytes,
                            view.doneSentTiles));
                }
            }
        }
    }

    private static int requiredInt(JsonNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new IllegalStateException(field + " must be an integer");
        }
        return node.intValue();
    }

    private static String requiredText(JsonNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || !node.isTextual() || node.textValue().isBlank()) {
            throw new IllegalStateException(field + " must be a non-empty string");
        }
        return node.textValue();
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static final class Listener implements WebSocket.Listener {
        private final BlockingQueue<Event> events;
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();

        private Listener(BlockingQueue<Event> events) {
            this.events = events;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last) {
            text.append(data);
            if (last) {
                String complete = text.toString();
                text.setLength(0);
                events.add(new TextEvent(
                        complete,
                        complete.getBytes(StandardCharsets.UTF_8).length));
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(
                WebSocket webSocket,
                ByteBuffer data,
                boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binary.writeBytes(chunk);
            if (binary.size() > 4 + 4096 + 262_144) {
                events.add(new FailureEvent(
                        new IllegalStateException("binary message exceeds LUPA TILE limit")));
                binary.reset();
            } else if (last) {
                events.add(new BinaryEvent(binary.toByteArray()));
                binary.reset();
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(
                WebSocket webSocket,
                int statusCode,
                String reason) {
            events.add(new CloseEvent(statusCode, reason));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            events.add(new FailureEvent(error));
        }
    }

    private sealed interface Event
            permits TextEvent, BinaryEvent, CloseEvent, FailureEvent {}

    private record TextEvent(String text, int utf8Bytes) implements Event {}

    private record BinaryEvent(byte[] payload) implements Event {
        private BinaryEvent {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    private record CloseEvent(int statusCode, String reason) implements Event {}

    private record FailureEvent(Throwable error) implements Event {}

    private record TileEnvelope(
            int deliveryId,
            int epoch,
            int z,
            int x,
            int y,
            int width,
            int height,
            int payloadBytes) {}

    private record ViewIntent(
            int epoch,
            long offsetNanos,
            long plannedOffsetNanos,
            String mode,
            String json) {}

    private static final class ViewStat {
        private final int epoch;
        private final long plannedOffsetNanos;
        private long viewSentNanos;
        private long scheduleLagNanos;
        private long planNanos;
        private long firstUsefulTileNanos;
        private long lastTileNanos;
        private long doneNanos;
        private long tiles;
        private long jpegBytes;
        private long lupaBinaryBytes;
        private long obsoleteTiles;
        private long obsoleteJpegBytes;
        private int doneSentTiles = -1;

        private ViewStat(int epoch, long plannedOffsetNanos) {
            this.epoch = epoch;
            this.plannedOffsetNanos = plannedOffsetNanos;
        }

        private ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("epoch", epoch);
            node.put("plannedOffsetNanos", plannedOffsetNanos);
            node.put("viewSentNanos", viewSentNanos);
            node.put("scheduleLagNanos", scheduleLagNanos);
            node.put("planNanos", planNanos);
            node.put("firstUsefulTileNanos", firstUsefulTileNanos);
            node.put("lastTileNanos", lastTileNanos);
            node.put("doneNanos", doneNanos);
            node.put("tiles", tiles);
            node.put("jpegBytes", jpegBytes);
            node.put("lupaBinaryBytes", lupaBinaryBytes);
            node.put("obsoleteTiles", obsoleteTiles);
            node.put("obsoleteJpegBytes", obsoleteJpegBytes);
            node.put("doneSentTiles", doneSentTiles);
            node.put("completed", doneNanos > 0);
            return node;
        }
    }

    private record ClientResult(
            int clientId,
            boolean success,
            String failure,
            String imageId,
            String imageVersion,
            List<ViewStat> views,
            long controlBytesIn,
            long controlBytesOut,
            long tiles,
            long jpegBytes,
            long lupaBinaryBytes,
            long obsoleteTiles,
            long obsoleteJpegBytes,
            long releasesSent,
            long errors) {
        static ClientResult failed(String message) {
            return new ClientResult(
                    -1, false, message, "", "", List.of(),
                    0, 0, 0, 0, 0, 0, 0, 0, 1);
        }
    }

    private record Summary(
            String runId,
            int configuredClients,
            int successfulClients,
            int failedClients,
            long viewsSent,
            long viewsCompleted,
            long incompleteViews,
            long tiles,
            long jpegBytes,
            long lupaBinaryBytes,
            long controlBytesIn,
            long controlBytesOut,
            long obsoleteTiles,
            long obsoleteJpegBytes,
            long releasesSent,
            long errors,
            long processDurationNanos,
            long measurementDurationNanos,
            long heapUsedBytesStart,
            long heapUsedBytesEnd,
            Config config,
            List<ClientResult> clients) {

        static Summary from(
                String runId,
                Config config,
                List<ClientResult> results,
                long processStartNano,
                long measurementStartNano,
                long heapStart,
                long heapEnd) {
            int ok = (int) results.stream().filter(ClientResult::success).count();
            long viewsSent = results.stream().mapToLong(r -> r.views().size()).sum();
            long completed = results.stream()
                    .flatMap(r -> r.views().stream())
                    .filter(v -> v.doneNanos > 0)
                    .count();
            return new Summary(
                    runId,
                    config.clients(),
                    ok,
                    results.size() - ok,
                    viewsSent,
                    completed,
                    viewsSent - completed,
                    results.stream().mapToLong(ClientResult::tiles).sum(),
                    results.stream().mapToLong(ClientResult::jpegBytes).sum(),
                    results.stream().mapToLong(ClientResult::lupaBinaryBytes).sum(),
                    results.stream().mapToLong(ClientResult::controlBytesIn).sum(),
                    results.stream().mapToLong(ClientResult::controlBytesOut).sum(),
                    results.stream().mapToLong(ClientResult::obsoleteTiles).sum(),
                    results.stream().mapToLong(ClientResult::obsoleteJpegBytes).sum(),
                    results.stream().mapToLong(ClientResult::releasesSent).sum(),
                    results.stream().mapToLong(ClientResult::errors).sum(),
                    System.nanoTime() - processStartNano,
                    measurementStartNano == 0 ? 0 : System.nanoTime() - measurementStartNano,
                    heapStart,
                    heapEnd,
                    config,
                    List.copyOf(results));
        }

        ObjectNode toJson() {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("schemaVersion", 1);
            root.put("runId", runId);
            root.put("commit", System.getProperty("e23.commit", "unknown"));
            root.put("mode", config.experimentMode());
            root.put("configuredClients", configuredClients);
            root.put("successfulClients", successfulClients);
            root.put("failedClients", failedClients);
            root.put("viewsSent", viewsSent);
            root.put("viewsCompleted", viewsCompleted);
            root.put("incompleteViews", incompleteViews);
            root.put("tiles", tiles);
            root.put("jpegBytes", jpegBytes);
            root.put("lupaBinaryBytes", lupaBinaryBytes);
            root.put("controlBytesIn", controlBytesIn);
            root.put("controlBytesOut", controlBytesOut);
            root.put("obsoleteTiles", obsoleteTiles);
            root.put("obsoleteJpegBytes", obsoleteJpegBytes);
            root.put("releasesSent", releasesSent);
            root.put("errors", errors);
            root.put("processDurationNanos", processDurationNanos);
            root.put("measurementDurationNanos", measurementDurationNanos);
            root.put("heapUsedBytesStart", heapUsedBytesStart);
            root.put("heapUsedBytesEnd", heapUsedBytesEnd);
            root.put("resourceScope", "load-client-jvm");
            root.put("networkByteScope",
                    "application bytes only: LUPA control UTF-8 and TILE binary envelopes; not TCP/WebSocket framing");

            ObjectNode cfg = root.putObject("config");
            config.writeJson(cfg);

            var array = root.putArray("clients");
            for (ClientResult result : clients) {
                ObjectNode client = array.addObject();
                client.put("clientId", result.clientId());
                client.put("success", result.success());
                client.put("failure", result.failure());
                client.put("imageId", result.imageId());
                client.put("imageVersion", result.imageVersion());
                client.put("controlBytesIn", result.controlBytesIn());
                client.put("controlBytesOut", result.controlBytesOut());
                client.put("tiles", result.tiles());
                client.put("jpegBytes", result.jpegBytes());
                client.put("lupaBinaryBytes", result.lupaBinaryBytes());
                client.put("obsoleteTiles", result.obsoleteTiles());
                client.put("obsoleteJpegBytes", result.obsoleteJpegBytes());
                client.put("releasesSent", result.releasesSent());
                client.put("errors", result.errors());
                var views = client.putArray("views");
                for (ViewStat view : result.views()) {
                    views.add(view.toJson());
                }
            }
            return root;
        }
    }

    private static final class RunLog implements AutoCloseable {
        private final BufferedWriter out;
        private final long processStartNano;
        private int lines;

        private RunLog(Path path, long processStartNano) throws IOException {
            this.out = Files.newBufferedWriter(
                    path,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            this.processStartNano = processStartNano;
        }

        synchronized void event(
                int clientId,
                String event,
                java.util.function.Consumer<ObjectNode> details) {
            try {
                ObjectNode node = MAPPER.createObjectNode();
                node.put("wallTime", Instant.now().toString());
                node.put("processMonotonicNanos", System.nanoTime() - processStartNano);
                node.put("clientId", clientId);
                node.put("event", event);
                details.accept(node);
                out.write(MAPPER.writeValueAsString(node));
                out.newLine();
                lines++;
                if ((lines & 63) == 0) out.flush();
            } catch (IOException e) {
                throw new RuntimeException("cannot write E23 event log", e);
            }
        }

        @Override
        public synchronized void close() throws IOException {
            out.flush();
            out.close();
        }
    }

    private record Config(
            URI url,
            int clients,
            String imageId,
            String scenario,
            int operations,
            long viewIntervalMs,
            long seed,
            int windowBytes,
            long bitmapBudgetBytes,
            long releaseDelayMs,
            long slowReleaseDelayMs,
            long connectTimeoutMs,
            long sendTimeoutMs,
            long timeoutMs,
            long settleMs,
            int detailOffset,
            String jpegValidation,
            String experimentMode,
            Path outputDir,
            String runId) {

        static Config load(String[] args) throws IOException {
            Properties properties = new Properties();
            Map<String, String> overrides = new LinkedHashMap<>();

            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) {
                    throw new IllegalArgumentException(
                            "arguments must use --name=value; --config=path is supported");
                }
                int eq = arg.indexOf('=');
                overrides.put(arg.substring(2, eq), arg.substring(eq + 1));
            }

            String configPath = overrides.remove("config");
            if (configPath != null) {
                try (var in = Files.newInputStream(Path.of(configPath))) {
                    properties.load(in);
                }
            }
            overrides.forEach(properties::setProperty);

            Config config = new Config(
                    URI.create(value(properties, "url", "ws://127.0.0.1:8081/lupa")),
                    integer(properties, "clients", 1),
                    value(properties, "imageId", "demo-grande"),
                    value(properties, "scenario", "movement").toLowerCase(Locale.ROOT),
                    integer(properties, "operations", 6),
                    number(properties, "viewIntervalMs", 150),
                    number(properties, "seed", 230023L),
                    integer(properties, "windowBytes", 1_048_576),
                    number(properties, "bitmapBudgetBytes", 67_108_864L),
                    number(properties, "releaseDelayMs", 0),
                    number(properties, "slowReleaseDelayMs", 250),
                    number(properties, "connectTimeoutMs", 3000),
                    number(properties, "sendTimeoutMs", 3000),
                    number(properties, "timeoutMs", 15000),
                    number(properties, "settleMs", 200),
                    integer(properties, "detailOffset", 0),
                    value(properties, "jpegValidation", "structural").toLowerCase(Locale.ROOT),
                    value(properties, "experimentMode", "normal").toLowerCase(Locale.ROOT),
                    Path.of(value(properties, "outputDir", "results/e23")),
                    value(properties, "runId", ""));

            config.validate();
            return config;
        }

        private void validate() {
            if (!"ws".equalsIgnoreCase(url.getScheme())
                    && !"wss".equalsIgnoreCase(url.getScheme())) {
                throw new IllegalArgumentException("url must use ws or wss");
            }
            if (clients < 1 || clients > 32) {
                throw new IllegalArgumentException("clients must be 1..32");
            }
            if (imageId.isBlank()) {
                throw new IllegalArgumentException("imageId is required");
            }
            if (!List.of("stable", "movement", "focus", "slow", "aggressive").contains(scenario)) {
                throw new IllegalArgumentException(
                        "scenario must be stable, movement, focus, slow or aggressive");
            }
            if (operations < 1 || operations > 100) {
                throw new IllegalArgumentException("operations must be 1..100");
            }
            if (viewIntervalMs < 1
                    || connectTimeoutMs < 1
                    || sendTimeoutMs < 1
                    || timeoutMs < 1
                    || settleMs < 0
                    || releaseDelayMs < 0
                    || slowReleaseDelayMs < 0) {
                throw new IllegalArgumentException("timeouts/delays are outside allowed range");
            }
            if (windowBytes < 524_288 || windowBytes > 1_048_576) {
                throw new IllegalArgumentException("windowBytes must be 524288..1048576");
            }
            if (bitmapBudgetBytes < 1) {
                throw new IllegalArgumentException("bitmapBudgetBytes must be positive");
            }
            if (detailOffset < -2 || detailOffset > 0) {
                throw new IllegalArgumentException("detailOffset must be -2..0");
            }
            if (!List.of("structural", "decode").contains(jpegValidation)) {
                throw new IllegalArgumentException(
                        "jpegValidation must be structural or decode");
            }
            if (!List.of("normal", "no-cancel").contains(experimentMode)) {
                throw new IllegalArgumentException(
                        "experimentMode must be normal or no-cancel");
            }
        }

        void writeJson(ObjectNode node) {
            node.put("url", url.toString());
            node.put("clients", clients);
            node.put("imageId", imageId);
            node.put("scenario", scenario);
            node.put("operations", operations);
            node.put("viewIntervalMs", viewIntervalMs);
            node.put("seed", seed);
            node.put("windowBytes", windowBytes);
            node.put("bitmapBudgetBytes", bitmapBudgetBytes);
            node.put("releaseDelayMs", releaseDelayMs);
            node.put("slowReleaseDelayMs", slowReleaseDelayMs);
            node.put("connectTimeoutMs", connectTimeoutMs);
            node.put("sendTimeoutMs", sendTimeoutMs);
            node.put("timeoutMs", timeoutMs);
            node.put("settleMs", settleMs);
            node.put("detailOffset", detailOffset);
            node.put("jpegValidation", jpegValidation);
            node.put("experimentMode", experimentMode);
            node.put("outputDir", outputDir.toString());
            node.put("runId", runId);
            node.put("releaseStatus", "discarded");
            node.put("rendering", false);
        }

        private static String value(
                Properties properties,
                String name,
                String fallback) {
            return properties.getProperty(name, fallback).trim();
        }

        private static int integer(
                Properties properties,
                String name,
                int fallback) {
            try {
                return Integer.parseInt(value(properties, name, Integer.toString(fallback)));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be an integer", e);
            }
        }

        private static long number(
                Properties properties,
                String name,
                long fallback) {
            try {
                return Long.parseLong(value(properties, name, Long.toString(fallback)));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a long integer", e);
            }
        }
    }
}
