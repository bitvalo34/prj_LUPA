package gt.lupa.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Independent Java 21 control-plane probe for E20.
 * Usage:
 * java -cp target/lupa.jar gt.lupa.tools.LupaControlProbe ws://127.0.0.1:8081/lupa demo-auxiliar
 */
public final class LupaControlProbe {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LupaControlProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: LupaControlProbe <ws-url> <imageId>");
            System.exit(2);
        }

        URI uri = URI.create(args[0]);
        String imageId = args[1];
        LinkedBlockingQueue<String> controls = new LinkedBlockingQueue<>();

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build()) {

            WebSocket socket = client.newWebSocketBuilder()
                    .subprotocols("lupa.v1")
                    .connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(uri, new Listener(controls))
                    .get(5, TimeUnit.SECONDS);

            System.out.println("WebSocket OPEN; subprotocol=" + socket.getSubprotocol());

            send(socket, """
                    {"type":"HELLO","version":1,"windowBytes":1048576,"bitmapBudgetBytes":67108864}
                    """);
            JsonNode welcome = next(controls, "WELCOME");
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
            JsonNode manifest = next(controls, "MANIFEST");
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
            JsonNode plan = next(controls, "PLAN");
            System.out.printf(
                    "PLAN epoch=%d appliedLevel=%d contextLevel=%d%n",
                    plan.path("epoch").asInt(),
                    plan.path("appliedLevel").asInt(),
                    plan.path("contextLevel").asInt());

            socket.sendClose(WebSocket.NORMAL_CLOSURE, "probe complete").get(3, TimeUnit.SECONDS);
            System.out.println("Control-plane probe completed successfully.");
        }
    }

    private static void send(WebSocket socket, String json) throws Exception {
        socket.sendText(json.strip(), true).get(3, TimeUnit.SECONDS);
    }

    private static JsonNode next(LinkedBlockingQueue<String> controls, String expectedType) throws Exception {
        String raw = controls.poll(5, TimeUnit.SECONDS);
        if (raw == null) throw new IllegalStateException("timeout waiting for " + expectedType);
        JsonNode message = MAPPER.readTree(raw);
        if ("ERROR".equals(message.path("type").asText())) {
            throw new IllegalStateException("server ERROR: " + raw);
        }
        if (!expectedType.equals(message.path("type").asText())) {
            throw new IllegalStateException("expected " + expectedType + " but received " + raw);
        }
        return message;
    }

    private static final class Listener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> controls;
        private final StringBuilder current = new StringBuilder();

        private Listener(LinkedBlockingQueue<String> controls) {
            this.controls = controls;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(
                WebSocket webSocket, CharSequence data, boolean last) {
            current.append(data);
            if (last) {
                controls.add(current.toString());
                current.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            controls.add("{\"type\":\"CLIENT_ERROR\",\"message\":"
                    + quote(error.toString()) + "}");
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
