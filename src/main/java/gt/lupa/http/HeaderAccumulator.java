package gt.lupa.http;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

public final class HeaderAccumulator {
    private final int maxHeaderBytes;
    private final ByteArrayOutputStream bytes;
    private int headerEnd = -1;
    private int terminatorState;

    public HeaderAccumulator(int maxHeaderBytes) {
        this.maxHeaderBytes = maxHeaderBytes;
        this.bytes = new ByteArrayOutputStream(Math.min(maxHeaderBytes, 4096));
    }

    public AppendResult append(ByteBuffer input) {
        while (input.hasRemaining()) {
            byte b = input.get();
            bytes.write(b);
            if (headerEnd < 0) {
                advanceTerminator(b);
                if (terminatorState == 4) headerEnd = bytes.size();
                if (headerEnd < 0 && bytes.size() > maxHeaderBytes) return AppendResult.TOO_LARGE;
            }
        }
        return headerEnd >= 0 ? AppendResult.COMPLETE : AppendResult.INCOMPLETE;
    }

    private void advanceTerminator(byte b) {
        switch (terminatorState) {
            case 0 -> terminatorState = b == '\r' ? 1 : 0;
            case 1 -> terminatorState = b == '\n' ? 2 : (b == '\r' ? 1 : 0);
            case 2 -> terminatorState = b == '\r' ? 3 : 0;
            case 3 -> terminatorState = b == '\n' ? 4 : (b == '\r' ? 1 : 0);
            default -> { }
        }
    }

    public boolean complete() {
        return headerEnd >= 0;
    }

    public byte[] headerBytes() {
        if (!complete()) throw new IllegalStateException("headers are incomplete");
        return Arrays.copyOf(bytes.toByteArray(), headerEnd);
    }

    public byte[] trailingData() {
        if (!complete()) return new byte[0];
        byte[] all = bytes.toByteArray();
        return Arrays.copyOfRange(all, headerEnd, all.length);
    }

    public int trailingBytes() {
        return complete() ? bytes.size() - headerEnd : 0;
    }

    public int size() {
        return bytes.size();
    }

    public enum AppendResult { INCOMPLETE, COMPLETE, TOO_LARGE }
}
