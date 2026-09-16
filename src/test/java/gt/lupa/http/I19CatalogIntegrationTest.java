package gt.lupa.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gt.lupa.config.ServerConfig;
import gt.lupa.storage.CatalogImage;
import gt.lupa.storage.CatalogJson;
import gt.lupa.storage.CatalogPublisher;
import gt.lupa.storage.CatalogSnapshot;
import gt.lupa.storage.FileCatalogSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I19CatalogIntegrationTest {
    @TempDir Path temp;
    private NioHttpServer server;
    private int port;

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    @Test
    void realFileCatalogSupportsGetHeadAndSeesAtomicPublicationWithoutRestart() throws Exception {
        Path dataRoot = temp.resolve("data");
        Path catalogPath = dataRoot.resolve("catalog.json");
        CatalogPublisher publisher = new CatalogPublisher();
        publisher.publish(catalogPath, new CatalogImage("photo", "v1", 300, 257, 256, 1));
        start(dataRoot);

        RawResponse first = request("GET /api/catalog HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertEquals(200, first.status);
        JsonNode firstJson = new ObjectMapper().readTree(first.body);
        assertEquals("v1", firstJson.get("images").get(0).get("imageVersion").asText());
        assertEquals(first.body.length, first.contentLength());
        assertEquals("no-store", first.header("cache-control"));

        RawResponse head = request("HEAD /api/catalog HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertEquals(200, head.status);
        assertEquals(first.header("content-type"), head.header("content-type"));
        assertEquals(first.header("content-length"), head.header("content-length"));
        assertEquals(0, head.body.length);

        publisher.publish(catalogPath, new CatalogImage("photo", "v2", 300, 257, 256, 1));
        RawResponse refreshed = request("GET /api/catalog HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertEquals(200, refreshed.status);
        JsonNode refreshedJson = new ObjectMapper().readTree(refreshed.body);
        assertEquals("v2", refreshedJson.get("images").get(0).get("imageVersion").asText());
    }

    @Test
    void emptyCatalogIsValidButMissingOrCorruptCatalogReturns503WithoutLeakingPaths() throws Exception {
        Path dataRoot = temp.resolve("data");
        Files.createDirectories(dataRoot);
        Path catalogPath = dataRoot.resolve("catalog.json");
        Files.write(catalogPath, new CatalogJson().writeCatalog(new CatalogSnapshot(1, List.of())));
        start(dataRoot);

        RawResponse empty = request("GET /api/catalog HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertEquals(200, empty.status);
        assertEquals(0, new ObjectMapper().readTree(empty.body).get("images").size());

        Files.delete(catalogPath);
        RawResponse missing = request("GET /api/catalog HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertEquals(503, missing.status);
        assertFalse(missing.text().contains(temp.toString()));

        Files.writeString(catalogPath, "{broken", StandardCharsets.UTF_8);
        RawResponse corrupt = request("GET /api/catalog HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertEquals(503, corrupt.status);
        assertFalse(corrupt.text().contains(temp.toString()));
    }

    @Test
    void privateStoragePathsRemainOutsideThePublicRouter() throws Exception {
        Path dataRoot = temp.resolve("data");
        Files.createDirectories(dataRoot);
        Files.write(
                dataRoot.resolve("catalog.json"),
                new CatalogJson().writeCatalog(new CatalogSnapshot(1, List.of()))
        );
        start(dataRoot);

        assertEquals(404, request("GET /data/originals/photo/v1/source.jpg HTTP/1.1\r\nHost: localhost\r\n\r\n").status);
        assertEquals(404, request("GET /data/staging/job-x HTTP/1.1\r\nHost: localhost\r\n\r\n").status);
        assertEquals(404, request("GET /data/pyramids/photo/v1/manifest.json HTTP/1.1\r\nHost: localhost\r\n\r\n").status);
    }

    private void start(Path dataRoot) throws Exception {
        ServerConfig config = new ServerConfig(
                "127.0.0.1",
                0,
                "file",
                dataRoot.resolve("catalog.json"),
                16 * 1024,
                32,
                4,
                32,
                2,
                Duration.ofSeconds(2),
                1024 * 1024,
                256 * 1024
        );
        server = new NioHttpServer(
                config,
                new HttpRouter(new FileCatalogSource(config.catalogPath(), config.maxCatalogBytes()))
        );
        server.start();
        port = server.port();
    }

    private RawResponse request(String request) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            socket.setSoTimeout(2000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = socket.getInputStream().read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return RawResponse.parse(out.toByteArray());
        }
    }

    private static final class RawResponse {
        private final int status;
        private final Map<String, String> headers;
        private final byte[] body;

        private RawResponse(int status, Map<String, String> headers, byte[] body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        private static RawResponse parse(byte[] bytes) {
            String wire = new String(bytes, StandardCharsets.ISO_8859_1);
            int split = wire.indexOf("\r\n\r\n");
            if (split < 0) throw new IllegalArgumentException("incomplete HTTP response");
            String[] lines = wire.substring(0, split).split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                headers.put(
                        lines[i].substring(0, colon).toLowerCase(),
                        lines[i].substring(colon + 1).trim()
                );
            }
            return new RawResponse(
                    status,
                    headers,
                    Arrays.copyOfRange(bytes, split + 4, bytes.length)
            );
        }

        private String header(String name) {
            return headers.get(name.toLowerCase());
        }

        private int contentLength() {
            return Integer.parseInt(header("content-length"));
        }

        private String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
