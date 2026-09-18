package gt.lupa.websocket;

import gt.lupa.http.HttpRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketHandshakeTest {
    @Test
    void computesKnownRfcAcceptAndSelectsLupaProtocol() {
        HttpRequest request = request(Map.of(
                "host", List.of("localhost:8080"),
                "upgrade", List.of("websocket"),
                "connection", List.of("keep-alive, Upgrade"),
                "sec-websocket-version", List.of("13"),
                "sec-websocket-key", List.of("dGhlIHNhbXBsZSBub25jZQ=="),
                "sec-websocket-protocol", List.of("chat, lupa.v1"),
                "origin", List.of("http://localhost")
        ));
        WebSocketHandshake.Result result = WebSocketHandshake.evaluate(request, true);
        assertInstanceOf(WebSocketHandshake.Accepted.class, result);
        String response = new String(((WebSocketHandshake.Accepted) result).responseBytes(), StandardCharsets.ISO_8859_1);
        assertTrue(response.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));
        assertTrue(response.contains("Sec-WebSocket-Protocol: lupa.v1"));
    }

    @Test
    void rejectsBadVersionBadKeyAndMissingSubprotocol() {
        assertInstanceOf(WebSocketHandshake.Rejected.class, WebSocketHandshake.evaluate(
                request(Map.of(
                        "host", List.of("localhost"),
                        "upgrade", List.of("websocket"),
                        "connection", List.of("Upgrade"),
                        "sec-websocket-version", List.of("12"),
                        "sec-websocket-key", List.of("dGhlIHNhbXBsZSBub25jZQ=="),
                        "sec-websocket-protocol", List.of("lupa.v1")
                )), true));

        assertInstanceOf(WebSocketHandshake.Rejected.class, WebSocketHandshake.evaluate(
                request(Map.of(
                        "host", List.of("localhost"),
                        "upgrade", List.of("websocket"),
                        "connection", List.of("Upgrade"),
                        "sec-websocket-version", List.of("13"),
                        "sec-websocket-key", List.of("bad"),
                        "sec-websocket-protocol", List.of("lupa.v1")
                )), true));

        assertInstanceOf(WebSocketHandshake.Rejected.class, WebSocketHandshake.evaluate(
                request(Map.of(
                        "host", List.of("localhost"),
                        "upgrade", List.of("websocket"),
                        "connection", List.of("Upgrade"),
                        "sec-websocket-version", List.of("13"),
                        "sec-websocket-key", List.of("dGhlIHNhbXBsZSBub25jZQ==")
                )), true));
    }

    @Test
    void rejectsWrongMethodRejectedOriginAndMissingOriginWhenPolicyRequiresIt() {
        Map<String, List<String>> headers = Map.of(
                "host", List.of("localhost:8080"),
                "upgrade", List.of("websocket"),
                "connection", List.of("Upgrade"),
                "sec-websocket-version", List.of("13"),
                "sec-websocket-key", List.of("dGhlIHNhbXBsZSBub25jZQ=="),
                "sec-websocket-protocol", List.of("lupa.v1"),
                "origin", List.of("https://evil.example")
        );

        HttpRequest post = new HttpRequest("POST", "/lupa", "/lupa", "", "HTTP/1.1", headers);
        WebSocketHandshake.Rejected method = assertInstanceOf(
                WebSocketHandshake.Rejected.class,
                WebSocketHandshake.evaluate(post, true));
        assertEquals(405, method.response().status());

        WebSocketHandshake.Rejected origin = assertInstanceOf(
                WebSocketHandshake.Rejected.class,
                WebSocketHandshake.evaluate(request(headers), true));
        assertEquals(403, origin.response().status());

        Map<String, List<String>> noOrigin = new java.util.HashMap<>(headers);
        noOrigin.remove("origin");
        WebSocketHandshake.Rejected required = assertInstanceOf(
                WebSocketHandshake.Rejected.class,
                WebSocketHandshake.evaluate(request(Map.copyOf(noOrigin)), false));
        assertEquals(403, required.response().status());
    }

    private static HttpRequest request(Map<String, List<String>> headers) {
        return new HttpRequest("GET", "/lupa", "/lupa", "", "HTTP/1.1", headers);
    }
}
