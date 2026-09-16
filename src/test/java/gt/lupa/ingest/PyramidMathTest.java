package gt.lupa.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PyramidMathTest {
    @Test
    void plansSingleLevelWhenImageFitsOneTile() throws Exception {
        PyramidPlan plan = PyramidMath.plan(200, 100);

        assertEquals(1, plan.levels().size());
        assertEquals(0, plan.maxLevel());

        PyramidLevel level0 = plan.levels().getFirst();
        assertEquals(0, level0.z());
        assertEquals(200, level0.width());
        assertEquals(100, level0.height());
        assertEquals(1, level0.columns());
        assertEquals(1, level0.rows());
    }

    @Test
    void plansExpectedLevelsForLargeImage() throws Exception {
        PyramidPlan plan = PyramidMath.plan(4096, 3072);

        assertEquals(5, plan.levels().size());
        assertEquals(4, plan.maxLevel());

        assertEquals(256, plan.levels().get(0).width());
        assertEquals(192, plan.levels().get(0).height());

        assertEquals(4096, plan.levels().get(4).width());
        assertEquals(3072, plan.levels().get(4).height());
    }

    @Test
    void usesCeilHalvingForOddDimensions() throws Exception {
        PyramidPlan plan = PyramidMath.plan(257, 257);

        assertEquals(2, plan.levels().size());
        assertEquals(129, plan.levels().get(0).width());
        assertEquals(129, plan.levels().get(0).height());
        assertEquals(257, plan.levels().get(1).width());
        assertEquals(257, plan.levels().get(1).height());
    }
}