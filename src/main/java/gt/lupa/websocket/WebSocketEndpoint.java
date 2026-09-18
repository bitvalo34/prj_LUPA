package gt.lupa.websocket;

/** Application hook above RFC 6455. E20's LUPA session implements this interface. */
public interface WebSocketEndpoint {
    default void onOpen(Sender sender) {}

    default void onText(Sender sender, String message) {
        sender.close(1008, "LUPA application layer not attached");
    }

    default void onClosed(int code, String reason) {}

    interface Sender {
        boolean sendText(String text);

        boolean sendBinary(byte[] payload);

        default boolean sendBinary(byte[] payload, Runnable onWritten) {
            boolean accepted = sendBinary(payload);
            if (accepted) onWritten.run();
            return accepted;
        }

        void close(int code, String reason);
    }
}
