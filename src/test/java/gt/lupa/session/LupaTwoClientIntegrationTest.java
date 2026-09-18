package gt.lupa.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gt.lupa.config.ServerConfig;
import gt.lupa.http.HttpRouter;
import gt.lupa.http.NioHttpServer;
import gt.lupa.storage.CatalogImage;
import gt.lupa.storage.CatalogJson;
import gt.lupa.storage.CatalogPublisher;
import gt.lupa.storage.FileCatalogSource;
import gt.lupa.storage.ImageLevel;
import gt.lupa.storage.ImageManifest;
import gt.lupa.storage.PublishedImageStore;
import gt.lupa.storage.TileData;
import gt.lupa.storage.TileReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LupaTwoClientIntegrationTest {
    @TempDir Path temp;
    private NioHttpServer server;
    private HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void stop() {
        if (server != null) server.close();
        if (client != null) client.close();
    }

    @Test
    void twoRealWebSocketConnectionsKeepCreditsAndDeliveryIdsIndependent() throws Exception {
        Path dataRoot = publishManifest();
        ServerConfig config = new ServerConfig(
                "127.0.0.1", 0, "file", dataRoot.resolve("catalog.json"),
                16 * 1024, 32, 4, 64, 2, Duration.ofSeconds(2),
                1024 * 1024, 256 * 1024, true);

        PublishedImageStore store = new PublishedImageStore(dataRoot, config.maxCatalogBytes());
        TileReader reader = (opened, z, x, y) -> {
            ImageLevel level = opened.manifest().levels().get(z);
            int w = Math.min(256, level.width() - x * 256);
            int h = Math.min(256, level.height() - y * 256);
            byte[] bytes = new byte[140_000];
            bytes[0] = (byte) 0xff;
            bytes[1] = (byte) 0xd8;
            bytes[bytes.length - 2] = (byte) 0xff;
            bytes[bytes.length - 1] = (byte) 0xd9;
            return new TileData(bytes, w, h);
        };

        server = new NioHttpServer(
                config,
                new HttpRouter(new FileCatalogSource(config.catalogPath(), config.maxCatalogBytes())),
                workers -> new LupaSession(store, reader, workers));
        server.start();

        client = HttpClient.newHttpClient();
        ClientListener a = new ClientListener();
        ClientListener b = new ClientListener();
        WebSocket wsA = connect(a);
        WebSocket wsB = connect(b);

        initialize(wsA, a);
        initialize(wsB, b);

        sendView(wsA, 2);
        sendView(wsB, 2);
        assertEquals("PLAN", a.nextControl().path("type").asText());
        assertEquals("PLAN", b.nextControl().path("type").asText());

        for (int i = 0; i < 3; i++) {
            assertNotNull(a.nextBinary());
            assertNotNull(b.nextBinary());
        }
        assertEquals(3, a.binaryCount());
        assertEquals(3, b.binaryCount());

        int aFirst = deliveryId(a.binaryAt(0));
        int bFirst = deliveryId(b.binaryAt(0));
        assertEquals(1, aFirst);
        assertEquals(1, bFirst);

        wsA.sendText(
                "{\"type\":\"RELEASE\",\"deliveryId\":" + aFirst + ",\"status\":\"discarded\"}",
                true).get(2, TimeUnit.SECONDS);

        assertNotNull(a.nextBinary(), "connection A should resume after its RELEASE");
        assertEquals(4, a.binaryCount());
        assertEquals(3, b.binaryCount(), "connection B must not inherit connection A credit");

        wsA.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
        wsB.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
    }

    private WebSocket connect(ClientListener listener) throws Exception {
        return client.newWebSocketBuilder()
                .subprotocols("lupa.v1")
                .connectTimeout(Duration.ofSeconds(2))
                .buildAsync(
                        URI.create("ws://127.0.0.1:" + server.port() + "/lupa"),
                        listener)
                .get(3, TimeUnit.SECONDS);
    }

    private void initialize(WebSocket socket, ClientListener listener) throws Exception {
        socket.sendText(
                "{\"type\":\"HELLO\",\"version\":1,\"windowBytes\":524288,\"bitmapBudgetBytes\":67108864}",
                true).get(2, TimeUnit.SECONDS);
        assertEquals("WELCOME", listener.nextControl().path("type").asText());

        socket.sendText(
                "{\"type\":\"OPEN\",\"epoch\":1,\"imageId\":\"photo\"}",
                true).get(2, TimeUnit.SECONDS);
        assertEquals("MANIFEST", listener.nextControl().path("type").asText());
    }

    private static void sendView(WebSocket socket, int epoch) throws Exception {
        socket.sendText(
                "{\"type\":\"VIEW\",\"epoch\":" + epoch
                        + ",\"imageId\":\"photo\",\"imageVersion\":\"v1\","
                        + "\"rect\":{\"x\":0,\"y\":0,\"width\":1024,\"height\":768},"
                        + "\"viewportPx\":{\"width\":512,\"height\":384},"
                        + "\"detailOffset\":0,\"mode\":\"uniform\",\"focus\":null}",
                true).get(2, TimeUnit.SECONDS);
    }

    private int deliveryId(byte[] envelope) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(envelope);
        int h = buffer.getInt();
        byte[] header = new byte[h];
        buffer.get(header);
        JsonNode json = mapper.readTree(header);
        assertEquals("TILE", json.path("type").asText());
        return json.path("deliveryId").asInt();
    }

    private Path publishManifest() throws Exception {
        Path dataRoot = temp.resolve("data");
        Path version = dataRoot.resolve("pyramids/photo/v1");
        Files.createDirectories(version);
        ImageManifest manifest = new ImageManifest(
                1, "photo", "v1", 1024, 768, 256, 0, "onetile",
                List.of(
                        new ImageLevel(0, 256, 192),
                        new ImageLevel(1, 512, 384),
                        new ImageLevel(2, 1024, 768)));
        Files.write(version.resolve("manifest.json"), new CatalogJson().writeManifest(manifest));
        new CatalogPublisher().publish(
                dataRoot.resolve("catalog.json"),
                new CatalogImage("photo", "v1", 1024, 768, 256, 2));
        return dataRoot;
    }

    private final class ClientListener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> controls = new LinkedBlockingQueue<>();
        private final LinkedBlockingQueue<byte[]> binaries = new LinkedBlockingQueue<>();
        private final java.util.List<byte[]> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(
                WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                controls.add(text.toString());
                text.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onBinary(
                WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] part = new byte[data.remaining()];
            data.get(part);
            binary.writeBytes(part);
            if (last) {
                byte[] complete = binary.toByteArray();
                binary.reset();
                seen.add(complete);
                binaries.add(complete);
            }
            webSocket.request(1);
            return null;
        }

        JsonNode nextControl() throws Exception {
            String raw = controls.poll(4, TimeUnit.SECONDS);
            assertNotNull(raw, "expected text control");
            JsonNode node = mapper.readTree(raw);
            assertNotEquals("ERROR", node.path("type").asText(), raw);
            return node;
        }

        byte[] nextBinary() throws Exception {
            return binaries.poll(4, TimeUnit.SECONDS);
        }

        int binaryCount() {
            return seen.size();
        }

        byte[] binaryAt(int index) {
            return seen.get(index);
        }
    }
}
