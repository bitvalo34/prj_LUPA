package gt.lupa.concurrent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TileReadAdmissionTest {
    @Test
    void boundsInFlightReadsQueuesFairlyAndCanCancelWaiter() {
        TileReadAdmission admission =
                new TileReadAdmission(1, 2);

        TileReadAdmission.AcquireResult first =
                admission.acquireOrQueue(
                        lease -> fail("immediate grant has no callback"));

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

        assertNotNull(
                secondLease.get(),
                "oldest live waiter must receive the permit");

        secondLease.get().close();

        assertEquals(
                1,
                admission.snapshot().inFlight(),
                "permit should transfer to the remaining live waiter");
        assertNull(
                thirdLease.get(),
                "cancelled waiter must never receive a permit");
    }

    @Test
    void namedOwnersReceiveOneTilePerTurnInFifoOrder() {
        TileReadAdmission admission =
                new TileReadAdmission(1, 4);

        List<String> grants = new ArrayList<>();
        AtomicReference<TileReadAdmission.Lease> bLease =
                new AtomicReference<>();
        AtomicReference<TileReadAdmission.Lease> aSecondLease =
                new AtomicReference<>();

        TileReadAdmission.AcquireResult aFirst =
                admission.acquireOrQueue(
                        101L,
                        lease -> fail("A first grant is immediate"));
        assertTrue(aFirst.grantedImmediately());
        grants.add("A");

        TileReadAdmission.AcquireResult duplicateA =
                admission.acquireOrQueue(
                        101L,
                        lease -> fail("duplicate A must not queue"));
        assertFalse(duplicateA.accepted());

        TileReadAdmission.AcquireResult b =
                admission.acquireOrQueue(
                        202L,
                        lease -> {
                            grants.add("B");
                            bLease.set(lease);
                        });
        assertTrue(b.queued());

        aFirst.lease().close();
        assertEquals(List.of("A", "B"), grants);

        TileReadAdmission.AcquireResult aSecond =
                admission.acquireOrQueue(
                        101L,
                        lease -> {
                            grants.add("A");
                            aSecondLease.set(lease);
                        });
        assertTrue(aSecond.queued());

        bLease.get().close();
        assertEquals(List.of("A", "B", "A"), grants);

        aSecondLease.get().close();

        TileReadAdmission.Snapshot snapshot =
                admission.snapshot();

        assertEquals(0, snapshot.inFlight());
        assertEquals(0, snapshot.waiting());
        assertEquals(0, snapshot.outstandingOwners());
        assertEquals(3, snapshot.turnsGranted());
        assertEquals(1, snapshot.duplicateOwnerRejections());
    }
}
