package gt.lupa.websocket;

import java.util.Arrays;

public record WebSocketFrame(boolean fin, int opcode, byte[] payload) {
    public WebSocketFrame {
        if (opcode < 0 || opcode > 0x0f) throw new IllegalArgumentException("opcode must be 0..15");
        payload = payload == null ? new byte[0] : payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    byte[] payloadUnsafe() {
        return payload;
    }

    @Override
    public String toString() {
        return "WebSocketFrame[fin=" + fin + ", opcode=" + opcode + ", payload=" + Arrays.toString(payload) + "]";
    }
}
