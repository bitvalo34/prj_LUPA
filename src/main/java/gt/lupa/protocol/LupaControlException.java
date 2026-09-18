package gt.lupa.protocol;

/** Invalid LUPA control syntax/shape that has no v1 ERROR code and is treated as WS policy violation. */
public final class LupaControlException extends Exception {
    public LupaControlException(String message) {
        super(message);
    }

    public LupaControlException(String message, Throwable cause) {
        super(message, cause);
    }
}
