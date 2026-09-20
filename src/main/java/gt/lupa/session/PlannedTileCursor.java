package gt.lupa.session;

import java.util.List;
import java.util.NoSuchElementException;

final class PlannedTileCursor {
    private final List<ViewPlanner.TileDescriptor> tiles;
    private int index;

    PlannedTileCursor(ViewPlanner.Plan plan) {
        this.tiles = plan.tiles();
    }

    boolean hasNext() {
        return index < tiles.size();
    }

    ViewPlanner.TileDescriptor next() {
        if (!hasNext()) throw new NoSuchElementException();
        return tiles.get(index++);
    }
}
