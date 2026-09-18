package gt.lupa.storage;

public record TileData(byte[] jpeg, int width, int height) {
    public TileData {
        if (jpeg == null) throw new IllegalArgumentException("jpeg is required");
        if (width < 1 || height < 1) throw new IllegalArgumentException("tile dimensions must be positive");
        jpeg = jpeg.clone();
    }

    @Override
    public byte[] jpeg() {
        return jpeg.clone();
    }

    byte[] jpegUnsafe() {
        return jpeg;
    }
}
