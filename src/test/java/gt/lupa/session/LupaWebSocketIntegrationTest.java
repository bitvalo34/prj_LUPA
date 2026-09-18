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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
    void standardJavaClientCompletesHelloOpenAndViewOverRealSocket() throws Exception {
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

        LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        client = HttpClient.newHttpClient();
        WebSocket socket = client.newWebSocketBuilder()
                .subprotocols("lupa.v1")
                .connectTimeout(Duration.ofSeconds(2))
                .buildAsync(
                        URI.create("ws://127.0.0.1:" + server.port() + "/lupa"),
                        new WebSocket.Listener() {
                            private final StringBuilder current = new StringBuilder();

                            @Override
                            public void onOpen(WebSocket webSocket) {
                                assertEquals("lupa.v1", webSocket.getSubprotocol());
                                webSocket.request(1);
                            }

                            @Override
                            public java.util.concurrent.CompletionStage<?> onText(
                                    WebSocket webSocket, CharSequence data, boolean last) {
                                current.append(data);
                                if (last) {
                                    messages.add(current.toString());
                                    current.setLength(0);
                                }
                                webSocket.request(1);
                                return null;
                            }
                        }).get(3, TimeUnit.SECONDS);

        socket.sendText(
                "{\"type\":\"HELLO\",\"version\":1,\"windowBytes\":524288,\"bitmapBudgetBytes\":67108864}",
                true).get(2, TimeUnit.SECONDS);
        assertEquals("WELCOME", next(messages).get("type").asText());

        socket.sendText(
                "{\"type\":\"OPEN\",\"epoch\":1,\"imageId\":\"photo\"}",
                true).get(2, TimeUnit.SECONDS);
        JsonNode manifest = next(messages);
        assertEquals("MANIFEST", manifest.get("type").asText());
        assertEquals("v1", manifest.get("imageVersion").asText());

        socket.sendText(
                "{\"type\":\"VIEW\",\"epoch\":2,\"imageId\":\"photo\",\"imageVersion\":\"v1\"," +
                "\"rect\":{\"x\":0,\"y\":0,\"width\":1024,\"height\":768}," +
                "\"viewportPx\":{\"width\":512,\"height\":384}," +
                "\"detailOffset\":0,\"mode\":\"uniform\",\"focus\":null}",
                true).get(2, TimeUnit.SECONDS);
        JsonNode plan = next(messages);
        assertEquals("PLAN", plan.get("type").asText());
        assertEquals(1, plan.get("appliedLevel").asInt());

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
    }

    private JsonNode next(LinkedBlockingQueue<String> messages) throws Exception {
        String text = messages.poll(3, TimeUnit.SECONDS);
        assertNotNull(text, "expected LUPA control was not received");
        return mapper.readTree(text);
    }

    private Path publish() throws Exception {
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
}
