package gt.lupa.websocket;

public final class WebSocketProtocolException extends Exception {
    private final int closeCode;

    public WebSocketProtocolException(int closeCode, String message) {
        super(message);
        this.closeCode = closeCode;
    }

    public int closeCode() {
        return closeCode;
    }
}
