package gt.lupa.storage;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Immutable compressed JPEG payload handle.
 *
 * Public construction defensively copies caller data. Package-local storage/cache
 * code may adopt already-owned immutable bytes. Every consumer has an independent
 * handle lifecycle and no mutable ByteBuffer position is shared.
 */
public final class TileData implements AutoCloseable {
    private final byte[] jpeg;
    private final int width;
    private final int height;
    private final Runnable onClose;
    private final AtomicBoolean closed = new AtomicBoolean();

    public TileData(byte[] jpeg, int width, int height) {
        this(validateAndClone(jpeg), width, height, () -> {});
    }

    private TileData(byte[] immutableJpeg, int width, int height, Runnable onClose) {
        if (immutableJpeg == null) throw new IllegalArgumentException("jpeg is required");
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("tile dimensions must be positive");
        }
        this.jpeg = immutableJpeg;
        this.width = width;
        this.height = height;
        this.onClose = Objects.requireNonNull(onClose);
    }

    static TileData owned(byte[] immutableJpeg, int width, int height) {
        return new TileData(
                Objects.requireNonNull(immutableJpeg),
                width,
                height,
                () -> {});
    }

    static TileData shared(
            byte[] immutableJpeg,
            int width,
            int height,
            Runnable onClose) {
        return new TileData(
                Objects.requireNonNull(immutableJpeg),
                width,
                height,
                onClose);
    }

    public byte[] jpeg() {
        ensureOpen();
        return jpeg.clone();
    }

    public int jpegLength() {
        ensureOpen();
        return jpeg.length;
    }

    public void copyJpegTo(ByteBuffer target) {
        ensureOpen();
        Objects.requireNonNull(target);
        if (target.remaining() < jpeg.length) {
            throw new IllegalArgumentException("target does not have enough remaining space");
        }
        target.put(jpeg);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    byte[] jpegUnsafe() {
        ensureOpen();
        return jpeg;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            onClose.run();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("tile payload handle is closed");
        }
    }

    private static byte[] validateAndClone(byte[] jpeg) {
        if (jpeg == null) throw new IllegalArgumentException("jpeg is required");
        return jpeg.clone();
    }
}
