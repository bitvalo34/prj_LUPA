package gt.lupa.ingest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PyramidMath {
    public static final int TILE_SIZE = 256;

    private PyramidMath() {
    }

    public static PyramidPlan plan(int width, int height) throws IngestException {
        if (width <= 0 || height <= 0) {
            throw new IngestException(2, "width and height must be positive");
        }

        List<int[]> descending = new ArrayList<>();
        int currentWidth = width;
        int currentHeight = height;
        descending.add(new int[]{currentWidth, currentHeight});

        while (currentWidth > TILE_SIZE || currentHeight > TILE_SIZE) {
            currentWidth = ceilDivide(currentWidth, 2);
            currentHeight = ceilDivide(currentHeight, 2);
            descending.add(new int[]{currentWidth, currentHeight});
        }

        Collections.reverse(descending);

        List<PyramidLevel> levels = new ArrayList<>(descending.size());
        for (int i = 0; i < descending.size(); i++) {
            int[] dims = descending.get(i);
            int w = dims[0];
            int h = dims[1];
            int columns = ceilDivide(w, TILE_SIZE);
            int rows = ceilDivide(h, TILE_SIZE);
            levels.add(new PyramidLevel(i, w, h, columns, rows));
        }

        return new PyramidPlan(List.copyOf(levels), width, height, levels.getLast().z());
    }

    static int ceilDivide(int value, int divisor) {
        return (int) ((value + (long) divisor - 1L) / divisor);
    }
}

record PyramidPlan(
        List<PyramidLevel> levels,
        int width,
        int height,
        int maxLevel) {
}

record PyramidLevel(
        int z,
        int width,
        int height,
        int columns,
        int rows) {
}