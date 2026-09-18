package gt.lupa.websocket;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class WebSocketFrames {
    private WebSocketFrames() {}

    public static ByteBuffer text(String text) {
        return encode(0x1, text.getBytes(StandardCharsets.UTF_8), true);
    }

    public static ByteBuffer binary(byte[] payload) {
        return encode(0x2, payload, true);
    }

    public static ByteBuffer pong(byte[] payload) {
        if (payload.length > 125) throw new IllegalArgumentException("pong payload must be <= 125 bytes");
        return encode(0xA, payload, true);
    }

    public static ByteBuffer close(int code, String reason) {
        return encode(0x8, closePayload(code, reason), true);
    }

    public static ByteBuffer close(byte[] payload) {
        if (payload.length > 125) throw new IllegalArgumentException("close payload must be <= 125 bytes");
        return encode(0x8, payload, true);
    }

    public static ByteBuffer encode(int opcode, byte[] payload, boolean fin) {
        if (payload == null) payload = new byte[0];
        int extra = payload.length <= 125 ? 0 : payload.length <= 0xffff ? 2 : 8;
        ByteBuffer out = ByteBuffer.allocate(2 + extra + payload.length);
        out.put((byte) ((fin ? 0x80 : 0) | (opcode & 0x0f)));
        if (payload.length <= 125) {
            out.put((byte) payload.length);
        } else if (payload.length <= 0xffff) {
            out.put((byte) 126).putShort((short) payload.length);
        } else {
            out.put((byte) 127).putLong(payload.length);
        }
        out.put(payload).flip();
        return out;
    }

    public static byte[] closePayload(int code, String reason) {
        if (!isValidCloseCode(code)) throw new IllegalArgumentException("invalid WebSocket close code: " + code);
        byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        if (reasonBytes.length > 123) throw new IllegalArgumentException("close reason exceeds 123 UTF-8 bytes");
        ByteBuffer payload = ByteBuffer.allocate(2 + reasonBytes.length);
        payload.putShort((short) code).put(reasonBytes);
        return payload.array();
    }

    public static CloseInfo parseClose(byte[] payload) throws WebSocketProtocolException {
        if (payload.length == 0) return new CloseInfo(1005, "");
        if (payload.length == 1) throw new WebSocketProtocolException(1002, "close payload cannot contain exactly one byte");
        int code = ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
        if (!isValidCloseCode(code)) throw new WebSocketProtocolException(1002, "invalid close code: " + code);
        String reason;
        try {
            reason = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload, 2, payload.length - 2))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new WebSocketProtocolException(1007, "close reason is not valid UTF-8");
        }
        return new CloseInfo(code, reason);
    }

    public static boolean isValidCloseCode(int code) {
        if (code >= 3000 && code <= 4999) return true;
        if (code < 1000 || code > 1014) return false;
        return code != 1004 && code != 1005 && code != 1006;
    }

    public record CloseInfo(int code, String reason) {}
}
