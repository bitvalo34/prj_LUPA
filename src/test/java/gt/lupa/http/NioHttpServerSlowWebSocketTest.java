package gt.lupa.http;

import gt.lupa.config.ServerConfig;
import gt.lupa.storage.ClasspathCatalogSource;
import gt.lupa.websocket.WebSocketEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NioHttpServerSlowWebSocketTest {
    private NioHttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void nonReadingWebSocketCannotBlockAnotherRealClient()
            throws Exception {
        CountDownLatch saturated =
                new CountDownLatch(1);
        CountDownLatch slowClosed =
                new CountDownLatch(1);
        AtomicInteger endpointIds =
                new AtomicInteger();

        ServerConfig config =
                new ServerConfig(
                        "127.0.0.1",
                        0,
                        "fixture",
                        Path.of("data/catalog.json"),
                        16 * 1024,
                        16,
                        2,
                        16,
                        2,
                        Duration.ofSeconds(2),
                        1024 * 1024,
                        256 * 1024,
                        true);

        server =
                new NioHttpServer(
                        config,
                        new HttpRouter(
                                ClasspathCatalogSource
                                        .defaultFixture()),
                        () -> {
                            int id =
                                    endpointIds.incrementAndGet();

                            return new WebSocketEndpoint() {
                                @Override
                                public void onText(
                                        Sender sender,
                                        String message) {
                                    if ("echo".equals(message)) {
                                        sender.sendText("OK");
                                        return;
                                    }

                                    if (!"flood".equals(message)) {
                                        sender.close(
                                                1008,
                                                "unexpected test command");
                                        return;
                                    }

                                    byte[] payload =
                                            new byte[250_000];

                                    boolean rejected = false;
                                    for (int i = 0; i < 512; i++) {
                                        if (!sender.sendBinary(payload)) {
                                            rejected = true;
                                            break;
                                        }
                                    }

                                    if (rejected) {
                                        saturated.countDown();
                                        sender.close(
                                                1011,
                                                "bounded write queue saturated");
                                    }
                                }

                                @Override
                                public void onClosed(
                                        int code,
                                        String reason) {
                                    if (id == 1) {
                                        slowClosed.countDown();
                                    }
                                }
                            };
                        });

        server.start();

        Socket slow = connect();
        slow.setReceiveBufferSize(1024);
        slow.getOutputStream()
                .write(handshakeRequest());
        slow.getOutputStream().flush();

        assertTrue(
                readHeaders(
                                slow.getInputStream())
                        .startsWith("HTTP/1.1 101"));

        slow.getOutputStream()
                .write(
                        maskedText("flood"));
        slow.getOutputStream().flush();

        assertTrue(
                saturated.await(
                        3,
                        TimeUnit.SECONDS),
                "non-reading client did not reach bounded write saturation");

        try (Socket healthy = connect()) {
            healthy.getOutputStream()
                    .write(handshakeRequest());
            healthy.getOutputStream().flush();

            assertTrue(
                    readHeaders(
                                    healthy.getInputStream())
                            .startsWith("HTTP/1.1 101"));

            healthy.getOutputStream()
                    .write(
                            maskedText("echo"));
            healthy.getOutputStream().flush();

            ServerFrame reply =
                    readServerFrame(
                            healthy.getInputStream());

            assertEquals(0x1, reply.opcode());
            assertEquals(
                    "OK",
                    new String(
                            reply.payload(),
                            StandardCharsets.UTF_8));
        }

        assertTrue(
                slowClosed.await(
                        3,
                        TimeUnit.SECONDS),
                "slow WebSocket was not cleaned after bounded saturation");

        slow.close();

        try (Socket http = connect()) {
            http.getOutputStream()
                    .write(
                            "GET / HTTP/1.1\r\n"
                                    .concat("Host: localhost\r\n\r\n")
                                    .getBytes(
                                            StandardCharsets.ISO_8859_1));
            http.getOutputStream().flush();

            assertTrue(
                    readHeaders(
                                    http.getInputStream())
                            .startsWith("HTTP/1.1 200"));
        }
    }

    private Socket connect() throws Exception {
        InetSocketAddress address =
                new InetSocketAddress(
                        "127.0.0.1",
                        server.port());

        long deadline =
                System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(3);

        IOException last = null;

        while (System.nanoTime() < deadline) {
            Socket socket = new Socket();
            try {
                socket.connect(
                        address,
                        500);
                socket.setSoTimeout(2000);
                return socket;
            } catch (IOException e) {
                last = e;
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                Thread.sleep(25);
            }
        }

        throw new IOException(
                "could not connect to test server",
                last);
    }

    private static byte[] handshakeRequest() {
        String request =
                "GET /lupa HTTP/1.1\\r\\n"
                        + "Host: localhost\\r\\n"
                        + "Upgrade: websocket\\r\\n"
                        + "Connection: keep-alive, Upgrade\\r\\n"
                        + "Sec-WebSocket-Version: 13\\r\\n"
                        + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\\r\\n"
                        + "Sec-WebSocket-Protocol: lupa.v1\\r\\n"
                        + "\\r\\n";

        return request.getBytes(
                StandardCharsets.ISO_8859_1);
    }

    private static byte[] maskedText(
            String value) {
        byte[] payload =
                value.getBytes(
                        StandardCharsets.UTF_8);

        byte[] mask =
                new byte[]{1, 2, 3, 4};

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        out.write(0x81);
        out.write(0x80 | payload.length);
        out.writeBytes(mask);

        for (int i = 0; i < payload.length; i++) {
            out.write(
                    payload[i]
                            ^ mask[i & 3]);
        }

        return out.toByteArray();
    }

    private static String readHeaders(
            InputStream in) throws Exception {
        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        int state = 0;
        while (state < 4) {
            int value = in.read();

            if (value < 0) {
                fail(
                        "EOF before HTTP response headers completed");
            }

            out.write(value);

            state =
                    switch (state) {
                        case 0 ->
                                value == '\r'
                                        ? 1
                                        : 0;
                        case 1 ->
                                value == '\n'
                                        ? 2
                                        : value == '\r'
                                                ? 1
                                                : 0;
                        case 2 ->
                                value == '\r'
                                        ? 3
                                        : 0;
                        case 3 ->
                                value == '\n'
                                        ? 4
                                        : value == '\r'
                                                ? 1
                                                : 0;
                        default -> state;
                    };
        }

        return out.toString(
                StandardCharsets.ISO_8859_1);
    }

    private static ServerFrame readServerFrame(
            InputStream in) throws Exception {
        int b0 = in.read();
        int b1 = in.read();

        if (b0 < 0 || b1 < 0) {
            fail(
                    "EOF before WebSocket frame header");
        }

        assertEquals(
                0,
                b1 & 0x80,
                "server frames must not be masked");

        int length = b1 & 0x7f;
        assertTrue(
                length <= 125,
                "helper expects short text frame");

        byte[] payload =
                in.readNBytes(length);

        assertEquals(
                length,
                payload.length);

        return new ServerFrame(
                b0 & 0x0f,
                payload);
    }

    private record ServerFrame(
            int opcode,
            byte[] payload) {}
}
