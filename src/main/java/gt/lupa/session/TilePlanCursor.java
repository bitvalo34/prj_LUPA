package gt.lupa.session;

import gt.lupa.storage.ImageLevel;
import gt.lupa.storage.ImageManifest;

import java.util.NoSuchElementException;

/**
 * Lazy tile selection for one uniform VIEW.
 * The z=0 thumbnail is always emitted first. The selected region follows without materializing a tile list.
 */
final class TilePlanCursor {
    private final int selectedLevel;
    private final int xStart;
    private final int xEnd;
    private final int yStart;
    private final int yEnd;
    private boolean thumbnailPending;
    private int x;
    private int y;
    private boolean regionDone;

    TilePlanCursor(
            ImageManifest manifest,
            int selectedLevel,
            int rectX,
            int rectY,
            int rectWidth,
            int rectHeight) {
        this(manifest, selectedLevel, rectX, rectY, rectWidth, rectHeight, true);
    }

    TilePlanCursor(
            ImageManifest manifest,
            int selectedLevel,
            int rectX,
            int rectY,
            int rectWidth,
            int rectHeight,
            boolean includeOpeningThumbnail) {
        if (selectedLevel < 0 || selectedLevel >= manifest.levels().size()) {
            throw new IllegalArgumentException("selected level is outside the manifest");
        }
        this.selectedLevel = selectedLevel;
        this.thumbnailPending = includeOpeningThumbnail;

        if (selectedLevel == 0) {
            xStart = xEnd = yStart = yEnd = 0;
            x = 0;
            y = 0;
            regionDone = includeOpeningThumbnail;
            return;
        }

        ImageLevel level = manifest.levels().get(selectedLevel);
        long originalWidth = manifest.width();
        long originalHeight = manifest.height();

        long levelX0 = ((long) rectX * level.width()) / originalWidth;
        long levelY0 = ((long) rectY * level.height()) / originalHeight;
        long levelX1 = ceilDiv(((long) rectX + rectWidth) * level.width(), originalWidth);
        long levelY1 = ceilDiv(((long) rectY + rectHeight) * level.height(), originalHeight);

        levelX0 = Math.max(0, Math.min(levelX0, level.width() - 1L));
        levelY0 = Math.max(0, Math.min(levelY0, level.height() - 1L));
        levelX1 = Math.max(levelX0 + 1, Math.min(levelX1, level.width()));
        levelY1 = Math.max(levelY0 + 1, Math.min(levelY1, level.height()));

        int tileSize = manifest.tileSize();
        xStart = (int) (levelX0 / tileSize);
        xEnd = (int) ((levelX1 - 1L) / tileSize);
        yStart = (int) (levelY0 / tileSize);
        yEnd = (int) ((levelY1 - 1L) / tileSize);
        x = xStart;
        y = yStart;
    }

    boolean hasNext() {
        return thumbnailPending || !regionDone;
    }

    TileRef next() {
        if (thumbnailPending) {
            thumbnailPending = false;
            return new TileRef(0, 0, 0);
        }
        if (regionDone) throw new NoSuchElementException();

        TileRef next = new TileRef(selectedLevel, x, y);
        if (x < xEnd) {
            x++;
        } else if (y < yEnd) {
            x = xStart;
            y++;
        } else {
            regionDone = true;
        }
        return next;
    }

    static long ceilDiv(long numerator, long denominator) {
        return (numerator + denominator - 1L) / denominator;
    }

    record TileRef(int z, int x, int y) {}
}
