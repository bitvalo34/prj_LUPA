package gt.lupa.concurrent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransientBufferBudgetTest {
    @Test
    void accountsExactBytesRejectsOverflowAndReleaseIsIdempotent() {
        TransientBufferBudget budget = new TransientBufferBudget(10);

        TransientBufferBudget.Lease first = budget.tryReserve(6);
        assertNotNull(first);
        assertNull(budget.tryReserve(5));
        assertEquals(6, budget.snapshot().reservedBytes());

        TransientBufferBudget.Lease second = budget.tryReserve(4);
        assertNotNull(second);
        assertEquals(10, budget.snapshot().reservedBytes());
        assertEquals(10, budget.snapshot().highWatermarkBytes());

        first.close();
        first.close();
        assertEquals(4, budget.snapshot().reservedBytes());

        second.close();
        assertEquals(0, budget.snapshot().reservedBytes());
        assertEquals(0, budget.snapshot().activeLeases());
        assertEquals(1, budget.snapshot().rejections());
    }
}
