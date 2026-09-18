package gt.lupa.websocket;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketFrameExtendedLengthTest {
    @Test
    void parsesCanonical16BitAnd64BitLengths() throws Exception {
        byte[] p126 = new byte[126];
        byte[] p65536 = new byte[65536];

        WebSocketFrameParser parser16 = new WebSocketFrameParser(70_000);
        List<WebSocketFrame> f16 = parser16.feed(ByteBuffer.wrap(masked(0x2, p126, true)));
        assertEquals(126, f16.getFirst().payload().length);

        WebSocketFrameParser parser64 = new WebSocketFrameParser(70_000);
        List<WebSocketFrame> f64 = parser64.feed(ByteBuffer.wrap(masked(0x2, p65536, true)));
        assertEquals(65536, f64.getFirst().payload().length);
    }

    @Test
    void rejectsNonMinimalLengthsMsbAndConfiguredLimitBeforeAllocation() {
        byte[] mask = {1,2,3,4};

        byte[] nonMinimal16 = concat(
                new byte[]{(byte)0x81, (byte)0xfe, 0, 125},
                mask,
                new byte[125]);
        assertEquals(1002, assertThrows(
                WebSocketProtocolException.class,
                () -> new WebSocketFrameParser(70_000).feed(ByteBuffer.wrap(nonMinimal16))).closeCode());

        byte[] nonMinimal64 = concat(
                new byte[]{(byte)0x81, (byte)0xff, 0,0,0,0,0,0, (byte)0xff, (byte)0xff},
                mask,
                new byte[65535]);
        assertEquals(1002, assertThrows(
                WebSocketProtocolException.class,
                () -> new WebSocketFrameParser(70_000).feed(ByteBuffer.wrap(nonMinimal64))).closeCode());

        byte[] msb = concat(
                new byte[]{(byte)0x81, (byte)0xff, (byte)0x80,0,0,0,0,0,0,0},
                mask);
        assertEquals(1002, assertThrows(
                WebSocketProtocolException.class,
                () -> new WebSocketFrameParser(70_000).feed(ByteBuffer.wrap(msb))).closeCode());

        byte[] tooLarge = concat(
                new byte[]{(byte)0x81, (byte)0xfe, 0x10, 0x01},
                mask);
        assertEquals(1009, assertThrows(
                WebSocketProtocolException.class,
                () -> new WebSocketFrameParser(4096).feed(ByteBuffer.wrap(tooLarge))).closeCode());
    }

    private static byte[] masked(int opcode, byte[] payload, boolean fin) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((fin ? 0x80 : 0) | opcode);
        if (payload.length <= 125) {
            out.write(0x80 | payload.length);
        } else if (payload.length <= 0xffff) {
            out.write(0xfe);
            out.write((payload.length >>> 8) & 0xff);
            out.write(payload.length & 0xff);
        } else {
            out.write(0xff);
            long n = payload.length;
            for (int shift = 56; shift >= 0; shift -= 8) out.write((int)(n >>> shift) & 0xff);
        }
        byte[] mask = {1,2,3,4};
        out.writeBytes(mask);
        for (int i = 0; i < payload.length; i++) out.write(payload[i] ^ mask[i & 3]);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }
}
