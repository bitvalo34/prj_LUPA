package gt.lupa.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class HttpParser {
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_\\x60|~0-9A-Za-z-]+");

    public HttpRequest parse(byte[] headerBytes) throws HttpParseException {
        String text = new String(headerBytes, StandardCharsets.ISO_8859_1);
        if (!text.endsWith("\r\n\r\n")) throw new HttpParseException(400, "incomplete HTTP headers");
        String[] lines = text.substring(0, text.length() - 4).split("\r\n", -1);
        if (lines.length == 0 || lines[0].isBlank()) throw new HttpParseException(400, "missing request line");
        String[] requestLine = lines[0].split(" ", -1);
        if (requestLine.length != 3 || requestLine[0].isEmpty() || requestLine[1].isEmpty() || requestLine[2].isEmpty()) {
            throw new HttpParseException(400, "malformed request line");
        }
        String method = requestLine[0];
        if (!TOKEN.matcher(method).matches()) throw new HttpParseException(400, "invalid method token");
        if (!"HTTP/1.1".equals(requestLine[2])) throw new HttpParseException(505, "only HTTP/1.1 is supported");

        String rawTarget = requestLine[1];
        if (rawTarget.indexOf('#') >= 0) throw new HttpParseException(400, "fragments are not valid in request targets");
        int q = rawTarget.indexOf('?');
        String rawPath = q >= 0 ? rawTarget.substring(0, q) : rawTarget;
        String query = q >= 0 ? rawTarget.substring(q + 1) : "";
        String path = PathPolicy.decodeAndValidate(rawPath);

        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) throw new HttpParseException(400, "unexpected empty header line");
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw new HttpParseException(400, "obsolete folded headers are not supported");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) throw new HttpParseException(400, "malformed header");
            String name = line.substring(0, colon);
            if (!TOKEN.matcher(name).matches()) throw new HttpParseException(400, "invalid header name");
            String value = line.substring(colon + 1).trim();
            for (int c = 0; c < value.length(); c++) {
                char ch = value.charAt(c);
                if ((ch < 0x20 && ch != '\t') || ch == 0x7f) {
                    throw new HttpParseException(400, "invalid control character in header");
                }
            }
            headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>()).add(value);
        }
        validateHost(headers);
        validateFraming(headers);
        return new HttpRequest(method, rawTarget, path, query, requestLine[2], copyImmutable(headers));
    }

    public HttpRequest parse(byte[] headerBytes, int trailingBytes) throws HttpParseException {
        if (trailingBytes > 0) throw new HttpParseException(400, "unexpected bytes after HTTP headers");
        return parse(headerBytes);
    }

    private static void validateHost(Map<String, List<String>> headers) throws HttpParseException {
        List<String> hosts = headers.get("host");
        if (hosts == null || hosts.size() != 1) throw new HttpParseException(400, "exactly one Host header is required");
        String host = hosts.getFirst();
        if (host.isBlank() || host.length() > 255 || host.contains(",") || host.contains("/") || host.contains("\\")
                || host.chars().anyMatch(Character::isWhitespace)) {
            throw new HttpParseException(400, "invalid Host header");
        }
    }

    private static void validateFraming(Map<String, List<String>> headers) throws HttpParseException {
        List<String> te = headers.get("transfer-encoding");
        List<String> cl = headers.get("content-length");
        if (te != null) {
            if (cl != null) throw new HttpParseException(400, "ambiguous message framing");
            throw new HttpParseException(400, "Transfer-Encoding is not supported");
        }
        if (cl != null) {
            if (cl.size() != 1 || cl.getFirst().contains(",")) {
                throw new HttpParseException(400, "duplicate Content-Length is not accepted");
            }
            long length;
            try {
                String raw = cl.getFirst();
                if (raw.isEmpty() || raw.startsWith("+") || raw.startsWith("-")
                        || !raw.chars().allMatch(Character::isDigit)) throw new NumberFormatException();
                length = Long.parseLong(raw);
            } catch (NumberFormatException e) {
                throw new HttpParseException(400, "invalid Content-Length");
            }
            if (length != 0) throw new HttpParseException(400, "request bodies are not accepted for this HTTP subset");
        }
    }

    private static Map<String, List<String>> copyImmutable(Map<String, List<String>> headers) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return Map.copyOf(copy);
    }
}
