package gt.lupa.websocket;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Reassembles fragmented text messages while allowing control frames to be handled separately. */
public final class WebSocketTextAssembler {
    private final int maxMessageBytes;
    private ByteArrayOutputStream fragmented;

    public WebSocketTextAssembler(int maxMessageBytes) {
        if (maxMessageBytes < 1) throw new IllegalArgumentException("maxMessageBytes must be positive");
        this.maxMessageBytes = maxMessageBytes;
    }

    public Optional<String> accept(WebSocketFrame frame) throws WebSocketProtocolException {
        int opcode = frame.opcode();
        if (opcode == 0x2) throw new WebSocketProtocolException(1003, "binary application messages are not accepted from the client");
        if (opcode != 0x0 && opcode != 0x1) throw new IllegalArgumentException("assembler accepts only data frames");

        byte[] payload = frame.payloadUnsafe();
        if (opcode == 0x1) {
            if (fragmented != null) throw new WebSocketProtocolException(1002, "new data message started before fragmented message completed");
            ensureLimit(payload.length);
            if (frame.fin()) return Optional.of(decodeUtf8(payload));
            fragmented = new ByteArrayOutputStream(Math.min(maxMessageBytes, Math.max(32, payload.length)));
            fragmented.writeBytes(payload);
            return Optional.empty();
        }

        if (fragmented == null) throw new WebSocketProtocolException(1002, "continuation frame without an open fragmented message");
        ensureLimit(fragmented.size() + payload.length);
        fragmented.writeBytes(payload);
        if (!frame.fin()) return Optional.empty();
        byte[] complete = fragmented.toByteArray();
        fragmented = null;
        return Optional.of(decodeUtf8(complete));
    }

    public boolean hasOpenMessage() {
        return fragmented != null;
    }

    private void ensureLimit(int size) throws WebSocketProtocolException {
        if (size > maxMessageBytes) throw new WebSocketProtocolException(1009, "WebSocket message exceeds configured limit");
    }

    private static String decodeUtf8(byte[] bytes) throws WebSocketProtocolException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new WebSocketProtocolException(1007, "text message is not valid UTF-8");
        }
    }
}
