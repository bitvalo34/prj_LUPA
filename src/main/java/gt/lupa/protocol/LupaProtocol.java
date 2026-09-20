package gt.lupa.protocol;

public final class LupaProtocol {
    public static final int VERSION = 1;
    public static final int MIN_WINDOW_BYTES = 512 * 1024;
    public static final int MAX_WINDOW_BYTES = 1024 * 1024;
    public static final int MAX_TILE_BYTES = 262144;
    public static final int MAX_IN_FLIGHT = 16;
    public static final int MAX_CONTROL_BYTES = 16 * 1024;
    public static final int MAX_EPOCH = 2_147_483_647;
    public static final long MAX_VIEWPORT_PIXELS = 8_294_400L;

    public static final int MAX_FOCUS_RADIUS_PX = 512;
    public static final int MAX_SELECTION_DESCRIPTORS = 512;
    public static final long BITMAP_RESERVED_BYTES = 16L * 1024 * 1024;

    private LupaProtocol() {}

    public enum ErrorCode {
        VERSION_UNSUPPORTED,
        BAD_VIEW,
        IMAGE_NOT_FOUND,
        IMAGE_NOT_READY,
        LIMIT_EXCEEDED,
        INTERNAL_READ_ERROR
    }
}
