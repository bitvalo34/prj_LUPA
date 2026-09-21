package gt.lupa.http;

import gt.lupa.config.ServerConfig;
import gt.lupa.storage.ClasspathCatalogSource;
import gt.lupa.websocket.WebSocketEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class NioHttpServerAdmissionTest {
    private NioHttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    @Test
    void webSocketBudgetIsIndependentAndReusableAfterClose() throws Exception {
        ServerConfig config = ServerConfig.fromArgs(new String[]{
                "--host=127.0.0.1",
                "--port=0",
                "--catalog=fixture",
                "--max-connections=4",
                "--max-ws-connections=1",
                "--max-sessions=1",
                "--header-timeout-ms=1000",
                "--ws-close-timeout-ms=200"
        });

        server = new NioHttpServer(
                config,
                new HttpRouter(ClasspathCatalogSource.defaultFixture()),
                () -> new WebSocketEndpoint() {});
        server.start();

        Socket first = connect();
        writeUpgrade(first);
        assertTrue(
                readHeaders(first).startsWith("HTTP/1.1 101"),
                "first WebSocket should be admitted");
        assertEquals(1, server.snapshotForTest().activeWebSockets());

        try (Socket second = connect()) {
            writeUpgrade(second);
            assertTrue(
                    readHeaders(second).startsWith("HTTP/1.1 503"),
                    "second WebSocket must be rejected before upgrade");
        }

        first.close();

        waitUntil(
                () -> server.snapshotForTest().activeWebSockets() == 0,
                Duration.ofSeconds(2));

        try (Socket third = connect()) {
            writeUpgrade(third);
            assertTrue(
                    readHeaders(third).startsWith("HTTP/1.1 101"),
                    "capacity must be reusable after close");
        }
    }

    private Socket connect() throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 1000);
        socket.setSoTimeout(2000);
        return socket;
    }

    private static void writeUpgrade(Socket socket) throws Exception {
        String request =
                "GET /lupa HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Version: 13\r\n"
                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                        + "Sec-WebSocket-Protocol: lupa.v1\r\n"
                        + "\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
    }

    private static String readHeaders(Socket socket) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int state = 0;
        while (true) {
            int value = socket.getInputStream().read();
            if (value < 0) break;
            out.write(value);
            state = switch (state) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : 0;
                default -> 4;
            };
            if (state == 4) break;
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static void waitUntil(CheckedBoolean condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.get()) return;
            Thread.sleep(10);
        }
        fail("condition was not satisfied before timeout");
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean get() throws Exception;
    }
}
