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

    @Test
    void drrChargesActualCompressedBytesAndLetsSmallerFlowAdvanceEarlier() {
        TileReadAdmission admission =
                new TileReadAdmission(
                        1,
                        8,
                        128 * 1024);

        List<String> grants =
                new ArrayList<>();

        AtomicReference<TileReadAdmission.Lease> bFirst =
                new AtomicReference<>();
        AtomicReference<TileReadAdmission.Lease> cFirst =
                new AtomicReference<>();
        AtomicReference<TileReadAdmission.Lease> bSecond =
                new AtomicReference<>();
        AtomicReference<TileReadAdmission.Lease> aSecond =
                new AtomicReference<>();

        TileReadAdmission.AcquireResult aFirst =
                admission.acquireOrQueue(
                        1L,
                        lease -> fail("A first turn is immediate"));
        grants.add("A");

        assertTrue(
                admission.acquireOrQueue(
                                2L,
                                lease -> {
                                    grants.add("B");
                                    bFirst.set(lease);
                                })
                        .queued());

        assertTrue(
                admission.acquireOrQueue(
                                3L,
                                lease -> {
                                    grants.add("C");
                                    cFirst.set(lease);
                                })
                        .queued());

        aFirst.lease().complete(220 * 1024);
        assertEquals(List.of("A", "B"), grants);

        assertTrue(
                admission.acquireOrQueue(
                                1L,
                                lease -> {
                                    grants.add("A");
                                    aSecond.set(lease);
                                })
                        .queued());

        bFirst.get().complete(32 * 1024);
        assertEquals(List.of("A", "B", "C"), grants);

        assertTrue(
                admission.acquireOrQueue(
                                2L,
                                lease -> {
                                    grants.add("B");
                                    bSecond.set(lease);
                                })
                        .queued());

        cFirst.get().complete(128 * 1024);

        /*
         * A consumed a much larger previous tile. Its deficit is not enough on
         * the first visit, so B's small flow receives the next byte-fair turn.
         */
        assertEquals(
                List.of("A", "B", "C", "B"),
                grants);

        bSecond.get().complete(32 * 1024);
        assertEquals(
                List.of("A", "B", "C", "B", "A"),
                grants);

        aSecond.get().complete(220 * 1024);

        TileReadAdmission.Snapshot snapshot =
                admission.snapshot();

        assertEquals(0, snapshot.inFlight());
        assertEquals(0, snapshot.waiting());
        assertEquals(0, snapshot.outstandingOwners());
        assertEquals(
                (220L + 32L + 128L + 32L + 220L) * 1024L,
                snapshot.chargedBytes());
        assertTrue(
                snapshot.drrVisits() >= 5,
                "DRR must rotate flows while accumulating byte deficit");
    }
}
