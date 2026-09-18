package gt.lupa.session;

import gt.lupa.storage.ImageLevel;
import gt.lupa.storage.ImageManifest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TilePlanCursorTest {
    private final ImageManifest manifest = new ImageManifest(
            1, "photo", "v1", 1024, 768, 256, 0, "onetile",
            List.of(
                    new ImageLevel(0, 256, 192),
                    new ImageLevel(1, 512, 384),
                    new ImageLevel(2, 1024, 768)));

    @Test
    void thumbnailComesFirstAndAlignedHalfOpenBorderDoesNotAddExtraTile() {
        TilePlanCursor cursor = new TilePlanCursor(manifest, 2, 0, 0, 256, 256);
        List<TilePlanCursor.TileRef> refs = drain(cursor);

        assertEquals(new TilePlanCursor.TileRef(0, 0, 0), refs.getFirst());
        assertEquals(List.of(
                new TilePlanCursor.TileRef(0, 0, 0),
                new TilePlanCursor.TileRef(2, 0, 0)), refs);
    }

    @Test
    void regionCrossingOnePixelPastBoundaryIncludesAdjacentTile() {
        TilePlanCursor cursor = new TilePlanCursor(manifest, 2, 0, 0, 257, 256);
        assertEquals(List.of(
                new TilePlanCursor.TileRef(0, 0, 0),
                new TilePlanCursor.TileRef(2, 0, 0),
                new TilePlanCursor.TileRef(2, 1, 0)), drain(cursor));
    }

    @Test
    void selectedLevelZeroIsNotDuplicated() {
        TilePlanCursor cursor = new TilePlanCursor(manifest, 0, 0, 0, 1024, 768);
        assertEquals(List.of(new TilePlanCursor.TileRef(0, 0, 0)), drain(cursor));
    }

    private static List<TilePlanCursor.TileRef> drain(TilePlanCursor cursor) {
        List<TilePlanCursor.TileRef> refs = new ArrayList<>();
        while (cursor.hasNext()) refs.add(cursor.next());
        return refs;
    }
}
