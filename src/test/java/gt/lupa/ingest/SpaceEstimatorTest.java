package gt.lupa.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpaceEstimatorTest {
    @Test
    void referencePolicyDoesNotReserveAnotherFullOriginalCopy() throws Exception {
        long originalBytes = 100L * 1024L * 1024L * 1024L;
        SpaceEstimate copy = SpaceEstimator.estimate(originalBytes, 40_000, 30_131, OriginalPolicy.COPY);
        SpaceEstimate reference = SpaceEstimator.estimate(originalBytes, 40_000, 30_131, OriginalPolicy.REFERENCE);

        assertTrue(copy.requiredBytes() > reference.requiredBytes());
        assertTrue(copy.baseBytes() - reference.baseBytes() == originalBytes);
    }

    @Test
    void smallImageStillReceivesSafetyMargin() throws Exception {
        SpaceEstimate estimate = SpaceEstimator.estimate(1024L, 100, 100, OriginalPolicy.REFERENCE);
        assertTrue(estimate.safetyBytes() >= 256L * 1024L * 1024L);
        assertEquals(estimate.baseBytes() + estimate.safetyBytes(), estimate.requiredBytes());
    }
}
