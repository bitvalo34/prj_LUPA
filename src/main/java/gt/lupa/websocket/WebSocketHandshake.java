package gt.lupa.websocket;

import gt.lupa.http.HttpRequest;
import gt.lupa.http.HttpResponse;
import gt.lupa.http.ResponseFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Validation and response generation for the RFC 6455 opening handshake on /lupa. */
public final class WebSocketHandshake {
    public static final String SUBPROTOCOL = "lupa.v1";
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_\\x60|~0-9A-Za-z-]+");

    private WebSocketHandshake() {}

    public static Result evaluate(HttpRequest request, boolean allowNoOrigin) {
        if (!"/lupa".equals(request.path()) || !request.query().isEmpty()) {
            return rejected(ResponseFactory.error(404, "resource not found"));
        }
        if (!"GET".equals(request.method())) {
            return rejected(new HttpResponse(
                    405,
                    "Method Not Allowed",
                    "WebSocket opening handshake requires GET\n".getBytes(StandardCharsets.UTF_8),
                    Map.of("Content-Type", "text/plain; charset=utf-8", "Allow", "GET")));
        }
        if (!containsToken(request.headerValues("upgrade"), "websocket")
                || !containsToken(request.headerValues("connection"), "upgrade")) {
            return rejected(upgradeRequired("valid WebSocket Upgrade and Connection headers are required", false));
        }

        List<String> versions = request.headerValues("sec-websocket-version");
        if (versions.size() != 1 || !"13".equals(versions.getFirst().trim())) {
            return rejected(upgradeRequired("Sec-WebSocket-Version 13 is required", true));
        }

        List<String> keys = request.headerValues("sec-websocket-key");
        if (keys.size() != 1 || !validKey(keys.getFirst())) {
            return rejected(ResponseFactory.error(400, "Sec-WebSocket-Key must be one Base64 value representing 16 bytes"));
        }

        if (!offersSubprotocol(request.headerValues("sec-websocket-protocol"), SUBPROTOCOL)) {
            return rejected(ResponseFactory.error(400, "Sec-WebSocket-Protocol must offer lupa.v1"));
        }

        OriginDecision origin = validateOrigin(request, allowNoOrigin);
        if (!origin.accepted()) {
            return rejected(ResponseFactory.error(origin.status(), origin.message()));
        }

        String accept = computeAccept(keys.getFirst().trim());
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + "Sec-WebSocket-Protocol: " + SUBPROTOCOL + "\r\n"
                + "\r\n";
        return new Accepted(response.getBytes(StandardCharsets.ISO_8859_1));
    }

    public static String computeAccept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by the Java platform", e);
        }
    }

    private static boolean validKey(String value) {
        if (value == null || !value.equals(value.trim())) return false;
        try {
            return Base64.getDecoder().decode(value).length == 16;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean containsToken(List<String> values, String expected) {
        for (String value : values) {
            for (String part : value.split(",", -1)) {
                if (part.trim().equalsIgnoreCase(expected)) return true;
            }
        }
        return false;
    }

    private static boolean offersSubprotocol(List<String> values, String expected) {
        for (String value : values) {
            for (String part : value.split(",", -1)) {
                String protocol = part.trim();
                if (protocol.isEmpty() || !TOKEN.matcher(protocol).matches()) return false;
                if (protocol.equals(expected)) return true;
            }
        }
        return false;
    }

    private static OriginDecision validateOrigin(HttpRequest request, boolean allowNoOrigin) {
        List<String> origins = request.headerValues("origin");
        if (origins.isEmpty()) {
            return allowNoOrigin
                    ? OriginDecision.allow()
                    : OriginDecision.reject(403, "Origin is required by server policy");
        }
        if (origins.size() != 1) return OriginDecision.reject(400, "Origin must not appear more than once");

        URI origin;
        try {
            origin = new URI(origins.getFirst());
        } catch (URISyntaxException e) {
            return OriginDecision.reject(403, "Origin is not allowed");
        }
        String scheme = origin.getScheme();
        String host = origin.getHost();
        if (scheme == null || host == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || origin.getUserInfo() != null
                || origin.getRawQuery() != null
                || origin.getRawFragment() != null
                || (origin.getRawPath() != null && !origin.getRawPath().isEmpty())) {
            return OriginDecision.reject(403, "Origin is not allowed");
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.equals("localhost") || normalizedHost.equals("127.0.0.1") || normalizedHost.equals("::1")) {
            return OriginDecision.allow();
        }

        String requestHost = request.firstHeader("host");
        try {
            URI authority = new URI("http://" + requestHost);
            String requestHostname = authority.getHost();
            if (requestHostname == null || !normalizedHost.equals(requestHostname.toLowerCase(Locale.ROOT))) {
                return OriginDecision.reject(403, "Origin host does not match the requested server");
            }
            int requestPort = authority.getPort();
            int originPort = origin.getPort();
            if (requestPort != originPort) {
                return OriginDecision.reject(403, "Origin port does not match the requested server");
            }
            return OriginDecision.allow();
        } catch (URISyntaxException e) {
            return OriginDecision.reject(403, "Origin is not allowed");
        }
    }

    private static HttpResponse upgradeRequired(String message, boolean includeVersion) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/plain; charset=utf-8");
        headers.put("Upgrade", "websocket");
        if (includeVersion) headers.put("Sec-WebSocket-Version", "13");
        return new HttpResponse(426, "Upgrade Required", (message + "\n").getBytes(StandardCharsets.UTF_8), headers);
    }

    private static Rejected rejected(HttpResponse response) {
        return new Rejected(response);
    }

    public sealed interface Result permits Accepted, Rejected {}

    public record Accepted(byte[] responseBytes) implements Result {
        public Accepted {
            responseBytes = responseBytes.clone();
        }

        @Override
        public byte[] responseBytes() {
            return responseBytes.clone();
        }

        public List<ByteBuffer> encode() {
            return List.of(ByteBuffer.wrap(responseBytes()));
        }
    }

    public record Rejected(HttpResponse response) implements Result {}

    private record OriginDecision(boolean accepted, int status, String message) {
        static OriginDecision allow() {
            return new OriginDecision(true, 0, "");
        }

        static OriginDecision reject(int status, String message) {
            return new OriginDecision(false, status, message);
        }
    }
}
