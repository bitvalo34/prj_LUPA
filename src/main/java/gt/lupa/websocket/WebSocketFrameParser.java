package gt.lupa.websocket;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Incremental RFC 6455 parser for client-to-server frames. */
public final class WebSocketFrameParser {
    private enum State { HEADER1, HEADER2, EXTENDED_LENGTH, MASK, PAYLOAD }

    private final int maxFramePayloadBytes;
    private State state = State.HEADER1;
    private boolean fin;
    private int opcode;
    private boolean control;
    private int extendedLengthBytes;
    private int extendedLengthRead;
    private long payloadLength;
    private final byte[] mask = new byte[4];
    private int maskRead;
    private byte[] payload;
    private int payloadRead;

    public WebSocketFrameParser(int maxFramePayloadBytes) {
        if (maxFramePayloadBytes < 125) throw new IllegalArgumentException("maxFramePayloadBytes must be >= 125");
        this.maxFramePayloadBytes = maxFramePayloadBytes;
    }

    public List<WebSocketFrame> feed(ByteBuffer input) throws WebSocketProtocolException {
        List<WebSocketFrame> frames = new ArrayList<>();
        while (input.hasRemaining()) {
            switch (state) {
                case HEADER1 -> readHeader1(input.get());
                case HEADER2 -> readHeader2(input.get());
                case EXTENDED_LENGTH -> readExtendedLength(input.get());
                case MASK -> readMask(input.get(), frames);
                case PAYLOAD -> readPayload(input, frames);
            }
        }
        return frames;
    }

    private void readHeader1(byte raw) throws WebSocketProtocolException {
        int b = raw & 0xff;
        fin = (b & 0x80) != 0;
        int rsv = b & 0x70;
        opcode = b & 0x0f;
        if (rsv != 0) throw protocol("RSV bits require a negotiated extension");
        if (!isKnownOpcode(opcode)) throw protocol("reserved or unknown opcode: " + opcode);
        control = (opcode & 0x08) != 0;
        if (control && !fin) throw protocol("control frames must not be fragmented");
        state = State.HEADER2;
    }

    private void readHeader2(byte raw) throws WebSocketProtocolException {
        int b = raw & 0xff;
        boolean masked = (b & 0x80) != 0;
        if (!masked) throw protocol("client frames must be masked");
        int length7 = b & 0x7f;
        if (control && length7 > 125) throw protocol("control frame payload must be <= 125 bytes");
        if (length7 <= 125) {
            payloadLength = length7;
            validateLength();
            beginMask();
        } else {
            extendedLengthBytes = length7 == 126 ? 2 : 8;
            extendedLengthRead = 0;
            payloadLength = 0;
            state = State.EXTENDED_LENGTH;
        }
    }

    private void readExtendedLength(byte raw) throws WebSocketProtocolException {
        int b = raw & 0xff;
        if (extendedLengthBytes == 8 && extendedLengthRead == 0 && (b & 0x80) != 0) {
            throw protocol("64-bit payload length must have its most significant bit clear");
        }
        payloadLength = (payloadLength << 8) | b;
        extendedLengthRead++;
        if (extendedLengthRead == extendedLengthBytes) {
            if (extendedLengthBytes == 2 && payloadLength < 126) {
                throw protocol("non-minimal 16-bit payload length");
            }
            if (extendedLengthBytes == 8 && payloadLength < 65536) {
                throw protocol("non-minimal 64-bit payload length");
            }
            validateLength();
            beginMask();
        }
    }

    private void validateLength() throws WebSocketProtocolException {
        if (control && payloadLength > 125) throw protocol("control frame payload must be <= 125 bytes");
        if (payloadLength > maxFramePayloadBytes) {
            throw new WebSocketProtocolException(1009, "frame payload exceeds configured limit");
        }
        if (payloadLength > Integer.MAX_VALUE) {
            throw new WebSocketProtocolException(1009, "frame payload is too large");
        }
    }

    private void beginMask() {
        maskRead = 0;
        state = State.MASK;
    }

    private void readMask(byte raw, List<WebSocketFrame> frames) {
        mask[maskRead++] = raw;
        if (maskRead == 4) {
            payloadRead = 0;
            payload = new byte[(int) payloadLength];
            if (payload.length == 0) emit(frames);
            else state = State.PAYLOAD;
        }
    }

    private void readPayload(ByteBuffer input, List<WebSocketFrame> frames) {
        while (input.hasRemaining() && payloadRead < payload.length) {
            byte encoded = input.get();
            payload[payloadRead] = (byte) (encoded ^ mask[payloadRead & 3]);
            payloadRead++;
        }
        if (payloadRead == payload.length) emit(frames);
    }

    private void emit(List<WebSocketFrame> frames) {
        frames.add(new WebSocketFrame(fin, opcode, payload));
        reset();
    }

    private void reset() {
        state = State.HEADER1;
        fin = false;
        opcode = 0;
        control = false;
        extendedLengthBytes = 0;
        extendedLengthRead = 0;
        payloadLength = 0;
        maskRead = 0;
        payload = null;
        payloadRead = 0;
    }

    private static boolean isKnownOpcode(int opcode) {
        return opcode == 0x0 || opcode == 0x1 || opcode == 0x2
                || opcode == 0x8 || opcode == 0x9 || opcode == 0xA;
    }

    private static WebSocketProtocolException protocol(String message) {
        return new WebSocketProtocolException(1002, message);
    }
}
