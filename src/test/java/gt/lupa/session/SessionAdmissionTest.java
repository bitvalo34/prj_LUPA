package gt.lupa.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionAdmissionTest {
    @Test
    void admissionIsBoundedAndLeaseReleaseIsIdempotent() {
        SessionAdmission admission = new SessionAdmission(1);

        SessionAdmission.Lease first = admission.tryAcquire();
        assertNotNull(first);
        assertNull(admission.tryAcquire());
        assertEquals(1, admission.snapshot().active());
        assertEquals(0, admission.snapshot().available());

        first.close();
        first.close();

        assertEquals(0, admission.snapshot().active());
        assertEquals(1, admission.snapshot().available());

        SessionAdmission.Lease second = admission.tryAcquire();
        assertNotNull(second);
        assertEquals(1, admission.snapshot().active());

        second.close();
        assertEquals(0, admission.snapshot().active());
    }
}
