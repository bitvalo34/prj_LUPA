package gt.lupa.websocket;

/** Application hook above RFC 6455. E20/E21's LUPA session implements this interface. */
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

        /**
         * E21 tracked binary write. A send becomes committed when its frame is handed to the
         * underlying asynchronous channel for the first write. Before that point a queued frame
         * may be cancelled safely without emitting bytes.
         *
         * Legacy/test senders that do not override this method are treated as committing
         * synchronously.
         */
        default BinarySend sendBinaryTracked(
                byte[] payload,
                Runnable onCommitted,
                Runnable onWritten) {
            boolean accepted = sendBinary(payload);
            if (!accepted) return BinarySend.rejected();
            onCommitted.run();
            onWritten.run();
            return BinarySend.alreadyCommitted();
        }

        void close(int code, String reason);
    }

    interface BinarySend {
        boolean accepted();

        boolean committed();

        boolean cancelIfNotCommitted();

        static BinarySend rejected() {
            return FixedBinarySend.REJECTED;
        }

        static BinarySend alreadyCommitted() {
            return FixedBinarySend.COMMITTED;
        }
    }

    enum FixedBinarySend implements BinarySend {
        REJECTED(false, false),
        COMMITTED(true, true);

        private final boolean accepted;
        private final boolean committed;

        FixedBinarySend(boolean accepted, boolean committed) {
            this.accepted = accepted;
            this.committed = committed;
        }

        @Override
        public boolean accepted() {
            return accepted;
        }

        @Override
        public boolean committed() {
            return committed;
        }

        @Override
        public boolean cancelIfNotCommitted() {
            return false;
        }
    }
}
