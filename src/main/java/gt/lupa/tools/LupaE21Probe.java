package gt.lupa.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Independent Java 21 probe for E21 state, planning, focus and credit behavior.
 *
 * Usage:
 * java -cp target/lupa.jar gt.lupa.tools.LupaE21Probe \
 *   ws://127.0.0.1:8081/lupa demo-auxiliar /home/erwin/PRJIMA/data
 */
public final class LupaE21Probe {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int WINDOW_BYTES = 1_048_576;
    private static final long BITMAP_BUDGET_BYTES = 67_108_864L;

    private LupaE21Probe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: LupaE21Probe <ws-url> <imageId> [data-root]");
            System.exit(2);
        }

        URI uri = URI.create(args[0]);
        String imageId = args[1];
        Path dataRoot = args.length == 3 ? Path.of(args[2]).toAbsolutePath().normalize() : null;
        LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<>();

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build()) {
            WebSocket socket = client.newWebSocketBuilder()
                    .subprotocols("lupa.v1")
                    .connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(uri, new Listener(events))
                    .get(5, TimeUnit.SECONDS);

            System.out.println("E21_PROBE ws=open subprotocol=" + socket.getSubprotocol());

            send(socket, MAPPER.createObjectNode()
                    .put("type", "HELLO")
                    .put("version", 1)
                    .put("windowBytes", WINDOW_BYTES)
                    .put("bitmapBudgetBytes", BITMAP_BUDGET_BYTES)
                    .toString());
            JsonNode welcome = expectControl(events, "WELCOME");
            int negotiatedWindow = requiredInt(welcome, "windowBytes");
            int maxInFlight = requiredInt(welcome, "maxInFlight");
            System.out.printf(
                    "WELCOME window=%d maxTile=%d maxInFlight=%d%n",
                    negotiatedWindow,
                    requiredInt(welcome, "maxTileBytes"),
                    maxInFlight);

            send(socket, MAPPER.createObjectNode()
                    .put("type", "OPEN")
                    .put("epoch", 1)
                    .put("imageId", imageId)
                    .toString());
            JsonNode manifest = expectControl(events, "MANIFEST");
            String version = requiredText(manifest, "imageVersion");
            int width = requiredInt(manifest, "width");
            int height = requiredInt(manifest, "height");
            int maxLevel = manifest.path("levels").size() - 1;
            System.out.printf(
                    "MANIFEST epoch=1 image=%s/%s size=%dx%d maxLevel=%d tileSize=%d%n",
                    imageId,
                    version,
                    width,
                    height,
                    maxLevel,
                    requiredInt(manifest, "tileSize"));

            int viewportWidth = Math.min(width, 800);
            int viewportHeight = Math.max(
                    1,
                    Math.min(height, (int) Math.round((double) viewportWidth * height / width)));
            CreditLedger credits = new CreditLedger(negotiatedWindow, maxInFlight);

            ViewResult uniform = runView(
                    socket,
                    events,
                    dataRoot,
                    credits,
                    manifest,
                    2,
                    0,
                    "uniform",
                    null,
                    viewportWidth,
                    viewportHeight);
            if (uniform.plan().path("appliedLevel").asInt()
                    != uniform.plan().path("contextLevel").asInt()) {
                throw new IllegalStateException("uniform PLAN must have appliedLevel == contextLevel");
            }

            ViewResult reduced = runView(
                    socket,
                    events,
                    dataRoot,
                    credits,
                    manifest,
                    3,
                    -1,
                    "uniform",
                    null,
                    viewportWidth,
                    viewportHeight);
            if (reduced.plan().path("appliedLevel").asInt()
                    > uniform.plan().path("appliedLevel").asInt()) {
                throw new IllegalStateException("detailOffset=-1 increased the effective level");
            }

            // A high invalid epoch must not be consumed.
            ObjectNode invalid = baseView(
                    manifest, 100, 0, "uniform", null, viewportWidth, viewportHeight);
            invalid.with("rect").put("width", width + 1);
            send(socket, invalid.toString());
            JsonNode badView = expectControl(events, "ERROR");
            if (!"BAD_VIEW".equals(requiredText(badView, "code")) || badView.path("epoch").asInt() != 100) {
                throw new IllegalStateException("expected BAD_VIEW for invalid epoch 100: " + badView);
            }
            System.out.println("BAD_VIEW epoch=100 preserved session; next accepted epoch will be 4");

            Focus focus = new Focus(width / 2, height / 2, Math.min(160, Math.min(viewportWidth, viewportHeight) / 3));
            ViewResult focused = runView(
                    socket,
                    events,
                    dataRoot,
                    credits,
                    manifest,
                    4,
                    0,
                    "focus",
                    focus,
                    viewportWidth,
                    viewportHeight);
            int applied = focused.plan().path("appliedLevel").asInt();
            int context = focused.plan().path("contextLevel").asInt();
            if (context > applied || context < Math.max(0, applied - 1)) {
                throw new IllegalStateException("focus PLAN has inconsistent contextLevel");
            }
            for (int z : focused.levelCounts().keySet()) {
                if (z != applied && z != context) {
                    throw new IllegalStateException("focus plan emitted unexpected level z=" + z);
                }
            }

            int duplicate = uniform.firstDeliveryId();
            if (duplicate > 0) {
                sendRelease(socket, duplicate, "discarded");
                sendRelease(socket, 2_000_000_000, "discarded");
                System.out.printf(
                        "RELEASE duplicate=%d and unknown=2000000000 sent; no client credit was added%n",
                        duplicate);
            }

            ViewResult coarse = runView(
                    socket,
                    events,
                    dataRoot,
                    credits,
                    manifest,
                    5,
                    -2,
                    "uniform",
                    null,
                    viewportWidth,
                    viewportHeight);
            if (coarse.plan().path("appliedLevel").asInt()
                    > reduced.plan().path("appliedLevel").asInt()) {
                throw new IllegalStateException("detailOffset=-2 did not remain coarser than -1");
            }

            if (!(uniform.lastDeliveryId() < reduced.firstDeliveryId()
                    && reduced.lastDeliveryId() < focused.firstDeliveryId()
                    && focused.lastDeliveryId() < coarse.firstDeliveryId())) {
                throw new IllegalStateException("deliveryId sequence was reused or moved backwards");
            }

            credits.assertEmpty();
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "E21 probe complete")
                    .get(3, TimeUnit.SECONDS);

            System.out.printf(
                    "E21_PROBE SUCCESS views=4 maxReserved=%d maxPending=%d creditInvariantChecks=%d%n",
                    credits.maxReservedBytes,
                    credits.maxPending,
                    credits.invariantChecks);
        }
    }

    private static ViewResult runView(
            WebSocket socket,
            LinkedBlockingQueue<Event> events,
            Path dataRoot,
            CreditLedger credits,
            JsonNode manifest,
            int epoch,
            int detailOffset,
            String mode,
            Focus focus,
            int viewportWidth,
            int viewportHeight) throws Exception {
        ObjectNode view = baseView(
                manifest, epoch, detailOffset, mode, focus, viewportWidth, viewportHeight);
        send(socket, view.toString());

        JsonNode plan = expectControl(events, "PLAN");
        if (plan.path("epoch").asInt() != epoch) {
            throw new IllegalStateException("PLAN belongs to unexpected epoch: " + plan);
        }
        int applied = requiredInt(plan, "appliedLevel");
        int context = requiredInt(plan, "contextLevel");
        System.out.printf(
                "PLAN epoch=%d mode=%s offset=%d applied=%d context=%d%n",
                epoch, mode, detailOffset, applied, context);

        int received = 0;
        int firstDelivery = -1;
        int lastDelivery = -1;
        Map<Integer, Integer> levelCounts = new LinkedHashMap<>();

        while (true) {
            Event event = events.poll(10, TimeUnit.SECONDS);
            if (event == null) throw new IllegalStateException("timeout waiting for TILE/DONE epoch=" + epoch);

            if (event instanceof TextEvent text) {
                JsonNode control = MAPPER.readTree(text.text());
                String type = control.path("type").asText();
                if ("ERROR".equals(type)) {
                    throw new IllegalStateException("server ERROR during epoch " + epoch + ": " + text.text());
                }
                if ("DONE".equals(type)) {
                    if (control.path("epoch").asInt() != epoch) {
                        throw new IllegalStateException("stale/future DONE during epoch " + epoch + ": " + control);
                    }
                    int sentTiles = requiredInt(control, "sentTiles");
                    if (sentTiles != received) {
                        throw new IllegalStateException(
                                "DONE sentTiles=" + sentTiles + " client received=" + received);
                    }
                    credits.assertEmpty();
                    System.out.printf(
                            "DONE epoch=%d sentTiles=%d levels=%s credit=OK%n",
                            epoch, sentTiles, levelCounts);
                    return new ViewResult(
                            plan,
                            received,
                            firstDelivery,
                            lastDelivery,
                            Map.copyOf(levelCounts));
                }
                throw new IllegalStateException("unexpected control during epoch " + epoch + ": " + text.text());
            }

            BinaryEvent binary = (BinaryEvent) event;
            Tile tile = decodeTile(binary.payload());
            if (tile.epoch() != epoch) {
                throw new IllegalStateException(
                        "unexpected TILE epoch=" + tile.epoch() + " while waiting for epoch=" + epoch);
            }
            verifyPublishedHashIfRequested(dataRoot, tile);
            credits.reserve(tile.deliveryId(), binary.payload().length);
            if (firstDelivery < 0) firstDelivery = tile.deliveryId();
            if (lastDelivery >= 0 && tile.deliveryId() <= lastDelivery) {
                throw new IllegalStateException("deliveryId is not strictly increasing");
            }
            lastDelivery = tile.deliveryId();
            levelCounts.merge(tile.z(), 1, Integer::sum);
            received++;

            sendRelease(socket, tile.deliveryId(), "discarded");
            credits.release(tile.deliveryId());
        }
    }

    private static ObjectNode baseView(
            JsonNode manifest,
            int epoch,
            int detailOffset,
            String mode,
            Focus focus,
            int viewportWidth,
            int viewportHeight) {
        ObjectNode view = MAPPER.createObjectNode();
        view.put("type", "VIEW");
        view.put("epoch", epoch);
        view.put("imageId", manifest.path("imageId").asText());
        view.put("imageVersion", manifest.path("imageVersion").asText());

        ObjectNode rect = view.putObject("rect");
        rect.put("x", 0);
        rect.put("y", 0);
        rect.put("width", manifest.path("width").asInt());
        rect.put("height", manifest.path("height").asInt());

        ObjectNode viewport = view.putObject("viewportPx");
        viewport.put("width", viewportWidth);
        viewport.put("height", viewportHeight);

        view.put("detailOffset", detailOffset);
        view.put("mode", mode);
        if (focus == null) {
            view.putNull("focus");
        } else {
            ObjectNode node = view.putObject("focus");
            node.put("x", focus.x());
            node.put("y", focus.y());
            node.put("radiusPx", focus.radiusPx());
        }
        return view;
    }

    private static Tile decodeTile(byte[] envelope) throws Exception {
        if (envelope.length < 4) throw new IllegalStateException("TILE envelope shorter than 4 bytes");
        ByteBuffer buffer = ByteBuffer.wrap(envelope);
        int headerLength = buffer.getInt();
        if (headerLength < 1 || headerLength > 4096 || headerLength > buffer.remaining()) {
            throw new IllegalStateException("invalid TILE header length: " + headerLength);
        }

        byte[] headerBytes = new byte[headerLength];
        buffer.get(headerBytes);
        JsonNode header = MAPPER.readTree(headerBytes);
        if (!"TILE".equals(header.path("type").asText())) {
            throw new IllegalStateException("binary header type must be TILE");
        }

        int payloadBytes = requiredInt(header, "payloadBytes");
        if (payloadBytes < 1 || payloadBytes > 262_144 || payloadBytes != buffer.remaining()) {
            throw new IllegalStateException("TILE payload length mismatch");
        }
        if (!"jpeg".equals(requiredText(header, "codec"))) {
            throw new IllegalStateException("TILE codec must be jpeg");
        }

        byte[] jpeg = new byte[payloadBytes];
        buffer.get(jpeg);
        var decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (decoded == null) throw new IllegalStateException("received TILE JPEG cannot be decoded");

        int w = requiredInt(header, "w");
        int h = requiredInt(header, "h");
        if (decoded.getWidth() != w || decoded.getHeight() != h) {
            throw new IllegalStateException("JPEG dimensions disagree with TILE header");
        }

        String sha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(jpeg));
        return new Tile(
                requiredInt(header, "deliveryId"),
                requiredInt(header, "epoch"),
                requiredText(header, "imageId"),
                requiredText(header, "imageVersion"),
                requiredInt(header, "z"),
                requiredInt(header, "x"),
                requiredInt(header, "y"),
                w,
                h,
                jpeg,
                sha);
    }

    private static void verifyPublishedHashIfRequested(Path dataRoot, Tile tile) throws Exception {
        if (dataRoot == null) return;
        Path published = dataRoot.resolve("pyramids")
                .resolve(tile.imageId())
                .resolve(tile.imageVersion())
                .resolve("tiles")
                .resolve(Integer.toString(tile.z()))
                .resolve(tile.x() + "_" + tile.y() + ".jpg")
                .normalize();
        if (!published.startsWith(dataRoot)) {
            throw new IllegalStateException("published path escaped data root");
        }

        byte[] expected = Files.readAllBytes(published);
        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(expected));
        if (expected.length != tile.jpeg().length || !expectedHash.equals(tile.sha256())) {
            throw new IllegalStateException("TILE differs from published JPEG: " + published);
        }
    }

    private static void sendRelease(WebSocket socket, int deliveryId, String status) throws Exception {
        send(socket, MAPPER.createObjectNode()
                .put("type", "RELEASE")
                .put("deliveryId", deliveryId)
                .put("status", status)
                .toString());
    }

    private static void send(WebSocket socket, String json) throws Exception {
        socket.sendText(json.strip(), true).get(3, TimeUnit.SECONDS);
    }

    private static JsonNode expectControl(
            LinkedBlockingQueue<Event> events,
            String expectedType) throws Exception {
        Event event = events.poll(5, TimeUnit.SECONDS);
        if (!(event instanceof TextEvent text)) {
            throw new IllegalStateException("expected " + expectedType + " text control");
        }
        JsonNode message = MAPPER.readTree(text.text());
        if (!expectedType.equals(message.path("type").asText())) {
            throw new IllegalStateException(
                    "expected " + expectedType + " but received " + text.text());
        }
        return message;
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
        if (node == null || !node.isTextual() || node.textValue().isEmpty()) {
            throw new IllegalStateException(field + " must be a string");
        }
        return node.textValue();
    }

    private sealed interface Event permits TextEvent, BinaryEvent {}
    private record TextEvent(String text) implements Event {}
    private record BinaryEvent(byte[] payload) implements Event {}
    private record Focus(int x, int y, int radiusPx) {}

    private record ViewResult(
            JsonNode plan,
            int sentTiles,
            int firstDeliveryId,
            int lastDeliveryId,
            Map<Integer, Integer> levelCounts) {}

    private record Tile(
            int deliveryId,
            int epoch,
            String imageId,
            String imageVersion,
            int z,
            int x,
            int y,
            int width,
            int height,
            byte[] jpeg,
            String sha256) {
        private Tile {
            jpeg = jpeg.clone();
        }

        @Override
        public byte[] jpeg() {
            return jpeg.clone();
        }
    }

    private static final class CreditLedger {
        private final long window;
        private final int maxInFlight;
        private final Map<Integer, Integer> pending = new LinkedHashMap<>();
        private long reserved;
        private long maxReservedBytes;
        private int maxPending;
        private long invariantChecks;

        private CreditLedger(long window, int maxInFlight) {
            this.window = window;
            this.maxInFlight = maxInFlight;
        }

        private void reserve(int deliveryId, int bytes) {
            if (pending.putIfAbsent(deliveryId, bytes) != null) {
                throw new IllegalStateException("duplicate deliveryId=" + deliveryId);
            }
            reserved += bytes;
            maxReservedBytes = Math.max(maxReservedBytes, reserved);
            maxPending = Math.max(maxPending, pending.size());
            check();
        }

        private void release(int deliveryId) {
            Integer bytes = pending.remove(deliveryId);
            if (bytes == null) throw new IllegalStateException("release of unknown delivery=" + deliveryId);
            reserved -= bytes;
            check();
        }

        private void assertEmpty() {
            check();
            if (!pending.isEmpty() || reserved != 0) {
                throw new IllegalStateException("client ledger still has pending deliveries: " + pending.keySet());
            }
        }

        private void check() {
            invariantChecks++;
            if (reserved < 0 || reserved > window) {
                throw new IllegalStateException(
                        "credit invariant failed: reserved=" + reserved + " window=" + window);
            }
            if (pending.size() > maxInFlight) {
                throw new IllegalStateException(
                        "pending delivery count exceeds maxInFlight: " + pending.size());
            }
            long free = window - reserved;
            if (free + reserved != window) {
                throw new IllegalStateException("credit invariant arithmetic failed");
            }
        }
    }

    private static final class Listener implements WebSocket.Listener {
        private final LinkedBlockingQueue<Event> events;
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();

        private Listener(LinkedBlockingQueue<Event> events) {
            this.events = events;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last) {
            text.append(data);
            if (last) {
                events.add(new TextEvent(text.toString()));
                text.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onBinary(
                WebSocket webSocket,
                ByteBuffer data,
                boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binary.writeBytes(chunk);
            if (last) {
                events.add(new BinaryEvent(binary.toByteArray()));
                binary.reset();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            events.add(new TextEvent("{\"type\":\"CLIENT_ERROR\",\"message\":"
                    + quote(error.toString()) + "}"));
        }

        private static String quote(String value) {
            try {
                return MAPPER.writeValueAsString(value);
            } catch (Exception e) {
                return "\"client error\"";
            }
        }
    }
}
