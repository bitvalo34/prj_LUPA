package gt.lupa.http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Strict origin-form path decoding for the localhost HTTP surface.
 *
 * The path is decoded exactly once. Encoded separators are rejected before
 * decoding, and a decoded percent sign is rejected so a downstream component
 * cannot accidentally perform a second decoding pass.
 */
public final class PathPolicy {
    private PathPolicy() {}

    public static String decodeAndValidate(
            String rawPath) throws HttpParseException {
        if (rawPath == null
                || rawPath.isEmpty()
                || rawPath.charAt(0) != '/') {
            throw new HttpParseException(
                    400,
                    "only origin-form targets are supported");
        }

        if (rawPath.startsWith("//")) {
            throw new HttpParseException(
                    400,
                    "network-path targets are not supported");
        }

        String decoded = strictPercentDecode(rawPath);

        if (decoded.indexOf('\\') >= 0
                || decoded.indexOf('\0') >= 0) {
            throw new HttpParseException(
                    400,
                    "invalid path separator");
        }

        if (decoded.indexOf('%') >= 0) {
            throw new HttpParseException(
                    400,
                    "double-encoded paths are not accepted");
        }

        if (!decoded.startsWith("/")
                || decoded.startsWith("//")) {
            throw new HttpParseException(
                    400,
                    "invalid path");
        }

        for (String segment : decoded.split("/", -1)) {
            if (segment.equals(".")
                    || segment.equals("..")) {
                throw new HttpParseException(
                        400,
                        "path traversal is not allowed");
            }
        }

        return decoded;
    }

    private static String strictPercentDecode(
            String raw) throws HttpParseException {
        ByteArrayOutputStream out =
                new ByteArrayOutputStream(raw.length());

        for (int i = 0; i < raw.length();) {
            char ch = raw.charAt(i);

            if (ch == '%') {
                if (i + 2 >= raw.length()) {
                    throw new HttpParseException(
                            400,
                            "invalid percent encoding");
                }

                int hi = hex(raw.charAt(i + 1));
                int lo = hex(raw.charAt(i + 2));

                if (hi < 0 || lo < 0) {
                    throw new HttpParseException(
                            400,
                            "invalid percent encoding");
                }

                int value = (hi << 4) | lo;

                if (value == '/'
                        || value == '\\') {
                    throw new HttpParseException(
                            400,
                            "encoded path separators are not allowed");
                }

                out.write(value);
                i += 3;
                continue;
            }

            if (ch > 0x7f
                    || Character.isISOControl(ch)) {
                throw new HttpParseException(
                        400,
                        "raw non-ASCII/control characters are not allowed in path");
            }

            out.write((byte) ch);
            i++;
        }

        try {
            var decoder =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(
                                    CodingErrorAction.REPORT)
                            .onUnmappableCharacter(
                                    CodingErrorAction.REPORT);

            CharBuffer chars =
                    decoder.decode(
                            ByteBuffer.wrap(
                                    out.toByteArray()));

            return chars.toString();
        } catch (CharacterCodingException e) {
            throw new HttpParseException(
                    400,
                    "path is not valid UTF-8");
        }
    }

    private static int hex(char value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        return -1;
    }
}
