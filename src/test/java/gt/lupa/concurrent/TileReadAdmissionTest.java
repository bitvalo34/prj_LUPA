package gt.lupa.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TileReadAdmissionTest {
    @Test
    void boundsInFlightReadsQueuesFairlyAndCanCancelWaiter() {
        TileReadAdmission admission = new TileReadAdmission(1, 2);

        TileReadAdmission.AcquireResult first =
                admission.acquireOrQueue(lease -> fail("immediate grant has no callback"));
        assertTrue(first.grantedImmediately());
        assertEquals(1, admission.snapshot().inFlight());

        AtomicReference<TileReadAdmission.Lease> secondLease =
                new AtomicReference<>();
        TileReadAdmission.AcquireResult second =
                admission.acquireOrQueue(secondLease::set);
        assertTrue(second.queued());

        AtomicReference<TileReadAdmission.Lease> thirdLease =
                new AtomicReference<>();
        TileReadAdmission.AcquireResult third =
                admission.acquireOrQueue(thirdLease::set);
        assertTrue(third.queued());
        assertTrue(third.waitHandle().cancel());

        TileReadAdmission.AcquireResult replacement =
                admission.acquireOrQueue(lease -> {});
        assertTrue(replacement.queued());

        TileReadAdmission.AcquireResult rejected =
                admission.acquireOrQueue(lease -> {});
        assertFalse(rejected.accepted());

        first.lease().close();

        assertNotNull(secondLease.get(), "oldest live waiter must receive the permit");
        assertEquals(1, admission.snapshot().inFlight());

        secondLease.get().close();
        assertEquals(
                1,
                admission.snapshot().inFlight(),
                "permit should transfer to the remaining live waiter");

        assertNull(thirdLease.get(), "cancelled waiter must never receive a permit");
    }
}
