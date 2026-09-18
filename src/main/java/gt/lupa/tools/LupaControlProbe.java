package gt.lupa.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Independent Java 21 E20 probe.
 *
 * Usage:
 * java -cp target/lupa.jar gt.lupa.tools.LupaControlProbe \
 *   ws://127.0.0.1:8081/lupa demo-auxiliar /home/erwin/PRJIMA/data
 */
public final class LupaControlProbe {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LupaControlProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: LupaControlProbe <ws-url> <imageId> [data-root]");
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

            System.out.println("WebSocket OPEN; subprotocol=" + socket.getSubprotocol());

            send(socket, """
                    {"type":"HELLO","version":1,"windowBytes":1048576,"bitmapBudgetBytes":67108864}
                    """);
            JsonNode welcome = expectControl(events, "WELCOME");
            System.out.printf(
                    "WELCOME version=%d windowBytes=%d maxTileBytes=%d maxInFlight=%d%n",
                    welcome.path("version").asInt(),
                    welcome.path("windowBytes").asInt(),
                    welcome.path("maxTileBytes").asInt(),
                    welcome.path("maxInFlight").asInt());

            send(socket, MAPPER.createObjectNode()
                    .put("type", "OPEN")
                    .put("epoch", 1)
                    .put("imageId", imageId)
                    .toString());
            JsonNode manifest = expectControl(events, "MANIFEST");
            String version = manifest.path("imageVersion").asText();
            int width = manifest.path("width").asInt();
            int height = manifest.path("height").asInt();
            System.out.printf(
                    "MANIFEST %s/%s %dx%d levels=%d tileSize=%d%n",
                    imageId,
                    version,
                    width,
                    height,
                    manifest.path("levels").size(),
                    manifest.path("tileSize").asInt());

            int viewportWidth = Math.min(width, 800);
            int viewportHeight = Math.min(height, 600);
            var view = MAPPER.createObjectNode();
            view.put("type", "VIEW");
            view.put("epoch", 2);
            view.put("imageId", imageId);
            view.put("imageVersion", version);
            var rect = view.putObject("rect");
            rect.put("x", 0);
            rect.put("y", 0);
            rect.put("width", width);
            rect.put("height", height);
            var viewport = view.putObject("viewportPx");
            viewport.put("width", viewportWidth);
            viewport.put("height", viewportHeight);
            view.put("detailOffset", 0);
            view.put("mode", "uniform");
            view.putNull("focus");

            send(socket, view.toString());
            JsonNode plan = expectControl(events, "PLAN");
            System.out.printf(
                    "PLAN epoch=%d appliedLevel=%d contextLevel=%d%n",
                    plan.path("epoch").asInt(),
                    plan.path("appliedLevel").asInt(),
                    plan.path("contextLevel").asInt());

            int receivedTiles = 0;
            while (true) {
                Event event = events.poll(10, TimeUnit.SECONDS);
                if (event == null) throw new IllegalStateException("timeout waiting for TILE or DONE");

                if (event instanceof TextEvent text) {
                    JsonNode control = MAPPER.readTree(text.text());
                    String type = control.path("type").asText();
                    if ("ERROR".equals(type)) throw new IllegalStateException("server ERROR: " + text.text());
                    if ("DONE".equals(type)) {
                        int sentTiles = control.path("sentTiles").asInt();
                        if (sentTiles != receivedTiles) {
                            throw new IllegalStateException(
                                    "DONE sentTiles=" + sentTiles + " but client received=" + receivedTiles);
                        }
                        System.out.printf("DONE epoch=%d sentTiles=%d%n",
                                control.path("epoch").asInt(), sentTiles);
                        break;
                    }
                    throw new IllegalStateException("unexpected control after PLAN: " + text.text());
                }

                BinaryEvent binary = (BinaryEvent) event;
                Tile tile = decodeTile(binary.payload());
                verifyPublishedHashIfRequested(dataRoot, tile);
                receivedTiles++;
                System.out.printf(
                        "TILE delivery=%d epoch=%d z=%d x=%d y=%d %dx%d bytes=%d sha256=%s%n",
                        tile.deliveryId(), tile.epoch(), tile.z(), tile.x(), tile.y(),
                        tile.width(), tile.height(), tile.jpeg().length, tile.sha256());

                send(socket, MAPPER.createObjectNode()
                        .put("type", "RELEASE")
                        .put("deliveryId", tile.deliveryId())
                        .put("status", "discarded")
                        .toString());
            }

            socket.sendClose(WebSocket.NORMAL_CLOSURE, "probe complete").get(3, TimeUnit.SECONDS);
            System.out.println("E20 TILE/RELEASE probe completed successfully.");
        }
    }

    private static Tile decodeTile(byte[] envelope) throws Exception {
        if (envelope.length < 4) throw new IllegalStateException("TILE envelope is shorter than 4 bytes");
        ByteBuffer buffer = ByteBuffer.wrap(envelope);
        int headerLength = buffer.getInt();
        if (headerLength < 1 || headerLength > 4096 || headerLength > buffer.remaining()) {
            throw new IllegalStateException("invalid TILE header length: " + headerLength);
        }

        byte[] headerBytes = new byte[headerLength];
        buffer.get(headerBytes);
        JsonNode header = MAPPER.readTree(headerBytes);
        int payloadBytes = requiredInt(header, "payloadBytes");
        if (payloadBytes < 1 || payloadBytes > 262144 || payloadBytes != buffer.remaining()) {
            throw new IllegalStateException("TILE payload length mismatch");
        }
        if (!"jpeg".equals(header.path("codec").asText())) {
            throw new IllegalStateException("unsupported TILE codec");
        }

        byte[] jpeg = new byte[payloadBytes];
        buffer.get(jpeg);
        var decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (decoded == null) throw new IllegalStateException("received TILE JPEG cannot be decoded");

        int w = requiredInt(header, "w");
        int h = requiredInt(header, "h");
        if (decoded.getWidth() != w || decoded.getHeight() != h) {
            throw new IllegalStateException("received JPEG dimensions disagree with TILE header");
        }

        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jpeg));
        return new Tile(
                requiredInt(header, "deliveryId"),
                requiredInt(header, "epoch"),
                header.path("imageId").asText(),
                header.path("imageVersion").asText(),
                requiredInt(header, "z"),
                requiredInt(header, "x"),
                requiredInt(header, "y"),
                w,
                h,
                jpeg,
                sha256);
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

        if (!published.startsWith(dataRoot)) throw new IllegalStateException("published path escaped data root");
        byte[] expected = Files.readAllBytes(published);
        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(expected));
        if (!expectedHash.equals(tile.sha256()) || expected.length != tile.jpeg().length) {
            throw new IllegalStateException("received TILE differs from published JPEG: " + published);
        }
    }

    private static int requiredInt(JsonNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new IllegalStateException("TILE header field " + field + " must be an integer");
        }
        return node.intValue();
    }

    private static void send(WebSocket socket, String json) throws Exception {
        socket.sendText(json.strip(), true).get(3, TimeUnit.SECONDS);
    }

    private static JsonNode expectControl(LinkedBlockingQueue<Event> events, String expectedType) throws Exception {
        Event event = events.poll(5, TimeUnit.SECONDS);
        if (!(event instanceof TextEvent text)) {
            throw new IllegalStateException("expected " + expectedType + " text control");
        }
        JsonNode message = MAPPER.readTree(text.text());
        if ("ERROR".equals(message.path("type").asText())) {
            throw new IllegalStateException("server ERROR: " + text.text());
        }
        if (!expectedType.equals(message.path("type").asText())) {
            throw new IllegalStateException("expected " + expectedType + " but received " + text.text());
        }
        return message;
    }

    private sealed interface Event permits TextEvent, BinaryEvent {}
    private record TextEvent(String text) implements Event {}
    private record BinaryEvent(byte[] payload) implements Event {}

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
                WebSocket webSocket, CharSequence data, boolean last) {
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
                WebSocket webSocket, ByteBuffer data, boolean last) {
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
