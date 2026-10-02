package gt.lupa.websocket;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketFrameParserTest {
    @Test
    void parsesMaskedFrameAcrossSingleByteReads() throws Exception {
        byte[] wire = masked(true, 0x1, "hello".getBytes(StandardCharsets.UTF_8), new byte[]{1,2,3,4});
        WebSocketFrameParser parser = new WebSocketFrameParser(16 * 1024);
        List<WebSocketFrame> frames = new ArrayList<>();
        for (byte b : wire) frames.addAll(parser.feed(ByteBuffer.wrap(new byte[]{b})));
        assertEquals(1, frames.size());
        assertEquals("hello", new String(frames.getFirst().payload(), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsUnmaskedAndReservedOpcode() {
        WebSocketProtocolException unmasked = assertThrows(
                WebSocketProtocolException.class,
                () -> new WebSocketFrameParser(16 * 1024).feed(ByteBuffer.wrap(new byte[]{(byte)0x81, 0x00})));
        assertEquals(1002, unmasked.closeCode());

        byte[] reserved = masked(true, 0x3, new byte[0], new byte[]{1,2,3,4});
        WebSocketProtocolException opcode = assertThrows(
                WebSocketProtocolException.class,
                () -> new WebSocketFrameParser(16 * 1024).feed(ByteBuffer.wrap(reserved)));
        assertEquals(1002, opcode.closeCode());
    }

    @Test
    void reassemblesFragmentedUtf8AcrossFrameBoundary() throws Exception {
        byte[] utf8 = "A€B".getBytes(StandardCharsets.UTF_8);
        WebSocketTextAssembler assembler = new WebSocketTextAssembler(1024);
        assertTrue(assembler.accept(new WebSocketFrame(false, 0x1, new byte[]{utf8[0], utf8[1]})).isEmpty());
        String text = assembler.accept(new WebSocketFrame(true, 0x0, new byte[]{utf8[2], utf8[3], utf8[4]})).orElseThrow();
        assertEquals("A€B", text);
    }

    @Test
    void parsesMultipleFramesFromOneRead() throws Exception {
        byte[] first = masked(true, 0x1, "a".getBytes(StandardCharsets.UTF_8), new byte[]{1,2,3,4});
        byte[] second = masked(true, 0x1, "b".getBytes(StandardCharsets.UTF_8), new byte[]{5,6,7,8});
        ByteBuffer combined = ByteBuffer.allocate(first.length + second.length);
        combined.put(first).put(second).flip();

        List<WebSocketFrame> frames = new WebSocketFrameParser(1024).feed(combined);
        assertEquals(2, frames.size());
        assertEquals("a", new String(frames.get(0).payload(), StandardCharsets.UTF_8));
        assertEquals("b", new String(frames.get(1).payload(), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsInvalidContinuationAndFragmentedControlFrame() throws Exception {
        WebSocketTextAssembler assembler = new WebSocketTextAssembler(1024);
        WebSocketProtocolException continuation = assertThrows(
                WebSocketProtocolException.class,
                () -> assembler.accept(new WebSocketFrame(true, 0x0, new byte[]{1})));
        assertEquals(1002, continuation.closeCode());

        byte[] fragmentedPing = masked(false, 0x9, new byte[]{1}, new byte[]{1,2,3,4});
        WebSocketProtocolException control = assertThrows(
                WebSocketProtocolException.class,
                () -> new WebSocketFrameParser(1024).feed(ByteBuffer.wrap(fragmentedPing)));
        assertEquals(1002, control.closeCode());
    }

    @Test
    void rejectsRsvSecondDataMessageAndAccumulatedFragmentOverflow() throws Exception {
        byte[] rsv = masked(
                true,
                0x1,
                "x".getBytes(StandardCharsets.UTF_8),
                new byte[]{1,2,3,4});
        rsv[0] |= 0x40;

        WebSocketProtocolException rsvFailure = assertThrows(
                WebSocketProtocolException.class,
                () -> new WebSocketFrameParser(1024)
                        .feed(ByteBuffer.wrap(rsv)));
        assertEquals(1002, rsvFailure.closeCode());

        WebSocketTextAssembler fragmented =
                new WebSocketTextAssembler(16);
        assertTrue(fragmented.accept(
                new WebSocketFrame(false, 0x1, new byte[]{'a'}))
                .isEmpty());

        WebSocketProtocolException secondMessage = assertThrows(
                WebSocketProtocolException.class,
                () -> fragmented.accept(
                        new WebSocketFrame(
                                true,
                                0x1,
                                new byte[]{'b'})));
        assertEquals(1002, secondMessage.closeCode());

        WebSocketTextAssembler bounded =
                new WebSocketTextAssembler(3);
        assertTrue(bounded.accept(
                new WebSocketFrame(
                        false,
                        0x1,
                        new byte[]{'a','b'}))
                .isEmpty());

        WebSocketProtocolException overflow = assertThrows(
                WebSocketProtocolException.class,
                () -> bounded.accept(
                        new WebSocketFrame(
                                true,
                                0x0,
                                new byte[]{'c','d'})));
        assertEquals(1009, overflow.closeCode());
    }

    private static byte[] masked(boolean fin, int opcode, byte[] payload, byte[] mask) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((fin ? 0x80 : 0) | opcode);
        out.write(0x80 | payload.length);
        out.writeBytes(mask);
        for (int i = 0; i < payload.length; i++) out.write(payload[i] ^ mask[i & 3]);
        return out.toByteArray();
    }
}
