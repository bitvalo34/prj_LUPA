package gt.lupa.websocket;

/** Application hook above RFC 6455. E20's LUPA session will implement this interface. */
public interface WebSocketEndpoint {
    default void onOpen(Sender sender) {}

    default void onText(Sender sender, String message) {
        sender.close(1008, "LUPA application layer not attached");
    }

    default void onClosed(int code, String reason) {}

    interface Sender {
        boolean sendText(String text);
        boolean sendBinary(byte[] payload);
        void close(int code, String reason);
    }
}
