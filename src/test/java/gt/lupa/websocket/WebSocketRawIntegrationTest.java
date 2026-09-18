package gt.lupa.websocket;

import gt.lupa.config.ServerConfig;
import gt.lupa.http.HttpRouter;
import gt.lupa.http.NioHttpServer;
import gt.lupa.storage.ClasspathCatalogSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketRawIntegrationTest {
    private NioHttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    @Test
    void handshakeAndFirstMaskedFrameCanArriveInSameTcpWrite() throws Exception {
        startServer();
        try (Socket socket = connect()) {
            byte[] handshake = handshakeRequest();
            byte[] firstFrame = maskedFrame(0x1, "hello".getBytes(StandardCharsets.UTF_8), true);
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            request.writeBytes(handshake);
            request.writeBytes(firstFrame);
            socket.getOutputStream().write(request.toByteArray());
            socket.getOutputStream().flush();

            String responseHeaders = readHeaders(socket.getInputStream());
            assertTrue(responseHeaders.startsWith("HTTP/1.1 101"));
            assertTrue(responseHeaders.contains("Sec-WebSocket-Protocol: lupa.v1"));

            ServerFrame ack = readServerFrame(socket.getInputStream());
            assertEquals(0x1, ack.opcode());
            assertEquals("ACK:hello", new String(ack.payload(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void fragmentedUtf8TextAllowsInterleavedPingAndReassemblesMessage() throws Exception {
        startServer();
        try (Socket socket = connect()) {
            socket.getOutputStream().write(handshakeRequest());
            socket.getOutputStream().flush();
            assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 101"));

            byte[] euro = "A€B".getBytes(StandardCharsets.UTF_8);
            socket.getOutputStream().write(maskedFrame(0x1, new byte[]{euro[0], euro[1]}, false));
            socket.getOutputStream().write(maskedFrame(0x9, new byte[]{4,3,2,1}, true));
            socket.getOutputStream().flush();

            ServerFrame pong = readServerFrame(socket.getInputStream());
            assertEquals(0xA, pong.opcode());
            assertArrayEquals(new byte[]{4,3,2,1}, pong.payload());

            socket.getOutputStream().write(maskedFrame(
                    0x0,
                    new byte[]{euro[2], euro[3], euro[4]},
                    true));
            socket.getOutputStream().flush();

            ServerFrame ack = readServerFrame(socket.getInputStream());
            assertEquals(0x1, ack.opcode());
            assertEquals("ACK:A€B", new String(ack.payload(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void invalidUtf8ClosesWith1007() throws Exception {
        startServer();
        try (Socket socket = connect()) {
            socket.getOutputStream().write(handshakeRequest());
            socket.getOutputStream().flush();
            assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 101"));

            socket.getOutputStream().write(maskedFrame(0x1, new byte[]{(byte)0xc3, 0x28}, true));
            socket.getOutputStream().flush();

            ServerFrame close = readServerFrame(socket.getInputStream());
            assertEquals(0x8, close.opcode());
            assertEquals(1007, closeCode(close.payload()));
        }
    }

    @Test
    void binaryClientMessageClosesWith1003() throws Exception {
        startServer();
        try (Socket socket = connect()) {
            socket.getOutputStream().write(handshakeRequest());
            socket.getOutputStream().flush();
            assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 101"));

            socket.getOutputStream().write(maskedFrame(0x2, new byte[]{1,2,3}, true));
            socket.getOutputStream().flush();

            ServerFrame close = readServerFrame(socket.getInputStream());
            assertEquals(0x8, close.opcode());
            assertEquals(1003, closeCode(close.payload()));
        }
    }

    @Test
    void rawMaskedPingGetsUnmaskedPongWithSamePayload() throws Exception {
        startServer();
        try (Socket socket = connect()) {
            socket.getOutputStream().write(handshakeRequest());
            socket.getOutputStream().flush();
            assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 101"));

            byte[] ping = new byte[]{9,8,7,6};
            socket.getOutputStream().write(maskedFrame(0x9, ping, true));
            socket.getOutputStream().flush();

            ServerFrame pong = readServerFrame(socket.getInputStream());
            assertEquals(0xA, pong.opcode());
            assertArrayEquals(ping, pong.payload());
        }
    }

    private void startServer() throws Exception {
        ServerConfig config = new ServerConfig(
                "127.0.0.1", 0, "fixture", Path.of("data/catalog.json"),
                16 * 1024, 16, 2, 16, 2, Duration.ofSeconds(2),
                1024 * 1024, 256 * 1024, true);
        server = new NioHttpServer(
                config,
                new HttpRouter(ClasspathCatalogSource.defaultFixture()),
                () -> new WebSocketEndpoint() {
                    @Override
                    public void onText(Sender sender, String message) {
                        sender.sendText("ACK:" + message);
                    }
                });
        server.start();
    }

    private Socket connect() throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 1000);
        socket.setSoTimeout(2000);
        return socket;
    }

    private static byte[] handshakeRequest() {
        return ("""
                GET /lupa HTTP/1.1\r
                Host: localhost\r
                Upgrade: websocket\r
                Connection: keep-alive, Upgrade\r
                Sec-WebSocket-Version: 13\r
                Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r
                Sec-WebSocket-Protocol: lupa.v1\r
                \r
                """).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String readHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int value = in.read();
            if (value < 0) fail("EOF before HTTP response headers completed");
            out.write(value);
            state = switch (state) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : (value == '\r' ? 1 : 0);
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : (value == '\r' ? 1 : 0);
                default -> state;
            };
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static byte[] maskedFrame(int opcode, byte[] payload, boolean fin) {
        if (payload.length > 125) throw new IllegalArgumentException("helper only needs short frames");
        byte[] mask = {1,2,3,4};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((fin ? 0x80 : 0) | opcode);
        out.write(0x80 | payload.length);
        out.writeBytes(mask);
        for (int i = 0; i < payload.length; i++) out.write(payload[i] ^ mask[i & 3]);
        return out.toByteArray();
    }

    private static int closeCode(byte[] payload) {
        assertTrue(payload.length >= 2);
        return ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
    }

    private static ServerFrame readServerFrame(InputStream in) throws Exception {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 < 0 || b1 < 0) fail("EOF before WebSocket frame header");
        assertEquals(0, b1 & 0x80, "server frames must not be masked");
        int opcode = b0 & 0x0f;
        int length = b1 & 0x7f;
        assertTrue(length <= 125, "test helper expects a short server frame");
        byte[] payload = in.readNBytes(length);
        assertEquals(length, payload.length);
        return new ServerFrame(opcode, payload);
    }

    private record ServerFrame(int opcode, byte[] payload) {}
}
