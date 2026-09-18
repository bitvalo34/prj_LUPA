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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LupaWebSocketIntegrationTest {
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
    void standardJavaClientCompletesHelloOpenViewTilesReleaseAndDone() throws Exception {
        Path dataRoot = publish();
        ServerConfig config = new ServerConfig(
                "127.0.0.1", 0, "file", dataRoot.resolve("catalog.json"),
                16 * 1024, 32, 4, 32, 2, Duration.ofSeconds(2),
                1024 * 1024, 256 * 1024, true);

        FileCatalogSource catalog = new FileCatalogSource(config.catalogPath(), config.maxCatalogBytes());
        PublishedImageStore store = new PublishedImageStore(dataRoot, config.maxCatalogBytes());
        server = new NioHttpServer(
                config,
                new HttpRouter(catalog),
                workers -> new LupaSession(store, workers));
        server.start();

        LinkedBlockingQueue<String> controls = new LinkedBlockingQueue<>();
        AtomicInteger binaries = new AtomicInteger();
        client = HttpClient.newHttpClient();
        WebSocket socket = client.newWebSocketBuilder()
                .subprotocols("lupa.v1")
                .connectTimeout(Duration.ofSeconds(2))
                .buildAsync(
                        URI.create("ws://127.0.0.1:" + server.port() + "/lupa"),
                        new WebSocket.Listener() {
                            private final StringBuilder text = new StringBuilder();
                            private final ByteArrayOutputStream binary = new ByteArrayOutputStream();

                            @Override
                            public void onOpen(WebSocket webSocket) {
                                assertEquals("lupa.v1", webSocket.getSubprotocol());
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
                                byte[] chunk = new byte[data.remaining()];
                                data.get(chunk);
                                binary.writeBytes(chunk);
                                if (last) {
                                    byte[] envelope = binary.toByteArray();
                                    binary.reset();
                                    int deliveryId = validateTileEnvelope(envelope);
                                    binaries.incrementAndGet();
                                    webSocket.sendText(
                                            "{\"type\":\"RELEASE\",\"deliveryId\":" + deliveryId
                                                    + ",\"status\":\"discarded\"}",
                                            true);
                                }
                                webSocket.request(1);
                                return null;
                            }

                            private int validateTileEnvelope(byte[] envelope) {
                                try {
                                    ByteBuffer buffer = ByteBuffer.wrap(envelope);
                                    int h = buffer.getInt();
                                    assertTrue(h > 0 && h <= 4096);
                                    byte[] headerBytes = new byte[h];
                                    buffer.get(headerBytes);
                                    JsonNode header = mapper.readTree(headerBytes);
                                    assertEquals("TILE", header.get("type").asText());
                                    assertEquals("jpeg", header.get("codec").asText());
                                    assertEquals(buffer.remaining(), header.get("payloadBytes").asInt());
                                    assertTrue(header.get("w").asInt() > 0 && header.get("w").asInt() <= 256);
                                    assertTrue(header.get("h").asInt() > 0 && header.get("h").asInt() <= 256);
                                    byte[] jpeg = new byte[buffer.remaining()];
                                    buffer.get(jpeg);
                                    assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(jpeg)));
                                    return header.get("deliveryId").asInt();
                                } catch (Exception e) {
                                    throw new AssertionError(e);
                                }
                            }
                        }).get(3, TimeUnit.SECONDS);

        socket.sendText(
                "{\"type\":\"HELLO\",\"version\":1,\"windowBytes\":524288,\"bitmapBudgetBytes\":67108864}",
                true).get(2, TimeUnit.SECONDS);
        assertEquals("WELCOME", next(controls).get("type").asText());

        socket.sendText(
                "{\"type\":\"OPEN\",\"epoch\":1,\"imageId\":\"photo\"}",
                true).get(2, TimeUnit.SECONDS);
        JsonNode manifest = next(controls);
        assertEquals("MANIFEST", manifest.get("type").asText());
        assertEquals("v1", manifest.get("imageVersion").asText());

        socket.sendText(
                "{\"type\":\"VIEW\",\"epoch\":2,\"imageId\":\"photo\",\"imageVersion\":\"v1\"," +
                "\"rect\":{\"x\":0,\"y\":0,\"width\":1024,\"height\":768}," +
                "\"viewportPx\":{\"width\":512,\"height\":384}," +
                "\"detailOffset\":0,\"mode\":\"uniform\",\"focus\":null}",
                true).get(2, TimeUnit.SECONDS);

        JsonNode plan = next(controls);
        assertEquals("PLAN", plan.get("type").asText());
        assertEquals(1, plan.get("appliedLevel").asInt());

        JsonNode done = next(controls);
        assertEquals("DONE", done.get("type").asText());
        assertEquals(5, done.get("sentTiles").asInt());
        assertEquals(5, binaries.get());

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
    }

    private JsonNode next(LinkedBlockingQueue<String> controls) throws Exception {
        String text = controls.poll(5, TimeUnit.SECONDS);
        assertNotNull(text, "expected LUPA control was not received");
        JsonNode message = mapper.readTree(text);
        assertNotEquals("ERROR", message.path("type").asText(), text);
        return message;
    }

    private Path publish() throws Exception {
        Path dataRoot = temp.resolve("data");
        Path version = dataRoot.resolve("pyramids/photo/v1");
        Files.createDirectories(version.resolve("tiles/0"));
        Files.createDirectories(version.resolve("tiles/1"));

        ImageManifest manifest = new ImageManifest(
                1, "photo", "v1", 1024, 768, 256, 0, "onetile",
                List.of(
                        new ImageLevel(0, 256, 192),
                        new ImageLevel(1, 512, 384),
                        new ImageLevel(2, 1024, 768)));
        Files.write(version.resolve("manifest.json"), new CatalogJson().writeManifest(manifest));

        writeJpeg(version.resolve("tiles/0/0_0.jpg"), 256, 192);
        writeJpeg(version.resolve("tiles/1/0_0.jpg"), 256, 256);
        writeJpeg(version.resolve("tiles/1/1_0.jpg"), 256, 256);
        writeJpeg(version.resolve("tiles/1/0_1.jpg"), 256, 128);
        writeJpeg(version.resolve("tiles/1/1_1.jpg"), 256, 128);

        new CatalogPublisher().publish(
                dataRoot.resolve("catalog.json"),
                new CatalogImage("photo", "v1", 1024, 768, 256, 2));
        return dataRoot;
    }

    private static void writeJpeg(Path path, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y += 16) {
            for (int x = 0; x < width; x += 16) {
                int value = ((x * 31 + y * 17) & 0xff);
                int rgb = (value << 16) | ((255 - value) << 8) | (value / 2);
                for (int yy = y; yy < Math.min(y + 16, height); yy++) {
                    for (int xx = x; xx < Math.min(x + 16, width); xx++) image.setRGB(xx, yy, rgb);
                }
            }
        }
        assertTrue(ImageIO.write(image, "jpeg", path.toFile()));
    }
}
