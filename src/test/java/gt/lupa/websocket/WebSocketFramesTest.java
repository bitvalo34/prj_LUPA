package gt.lupa.websocket;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketFramesTest {
    @Test
    void parsesValidCloseCodeAndUtf8Reason() throws Exception {
        byte[] payload = WebSocketFrames.closePayload(1000, "fin ✓");
        WebSocketFrames.CloseInfo info = WebSocketFrames.parseClose(payload);
        assertEquals(1000, info.code());
        assertEquals("fin ✓", info.reason());
    }

    @Test
    void rejectsOneByteReservedCodeAndInvalidUtf8Reason() {
        WebSocketProtocolException oneByte = assertThrows(
                WebSocketProtocolException.class,
                () -> WebSocketFrames.parseClose(new byte[]{1}));
        assertEquals(1002, oneByte.closeCode());

        byte[] reserved = ByteBuffer.allocate(2).putShort((short) 1005).array();
        WebSocketProtocolException reservedCode = assertThrows(
                WebSocketProtocolException.class,
                () -> WebSocketFrames.parseClose(reserved));
        assertEquals(1002, reservedCode.closeCode());

        byte[] invalidUtf8 = ByteBuffer.allocate(4)
                .putShort((short) 1000)
                .put((byte) 0xc3)
                .put((byte) 0x28)
                .array();
        WebSocketProtocolException utf8 = assertThrows(
                WebSocketProtocolException.class,
                () -> WebSocketFrames.parseClose(invalidUtf8));
        assertEquals(1007, utf8.closeCode());
    }

    @Test
    void serverFramesAreUnmaskedAndUseCanonicalExtendedLength() {
        byte[] payload = "x".repeat(126).getBytes(StandardCharsets.UTF_8);
        ByteBuffer frame = WebSocketFrames.encode(0x2, payload, true);
        assertEquals((byte) 0x82, frame.get());
        assertEquals((byte) 126, frame.get());
        assertEquals(126, Short.toUnsignedInt(frame.getShort()));
        assertEquals(126, frame.remaining());
    }
}
