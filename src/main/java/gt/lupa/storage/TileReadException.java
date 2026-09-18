package gt.lupa.storage;

public final class TileReadException extends Exception {
    public TileReadException(String message) {
        super(message);
    }

    public TileReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
