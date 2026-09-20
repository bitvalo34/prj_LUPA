package gt.lupa.session;

import gt.lupa.protocol.LupaProtocol;

/**
 * Monotonic, non-reusable deliveryId allocator for one LUPA connection.
 * The sequence is intentionally not reset by OPEN or VIEW.
 */
final class DeliveryIdSequence {
    private long next;

    DeliveryIdSequence() {
        this(1);
    }

    DeliveryIdSequence(long first) {
        if (first < 1 || first > (long) LupaProtocol.MAX_EPOCH + 1L) {
            throw new IllegalArgumentException("invalid first deliveryId");
        }
        this.next = first;
    }

    boolean exhausted() {
        return next > LupaProtocol.MAX_EPOCH;
    }

    int take() {
        if (exhausted()) {
            throw new IllegalStateException("deliveryId space is exhausted");
        }
        return (int) next++;
    }

    long nextValue() {
        return next;
    }
}
