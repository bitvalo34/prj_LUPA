package gt.lupa.storage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Bounded reader for immutable JPEG tiles already published by A19. */
public final class PublishedTileReader implements TileReader {
    private final PublishedImageStore store;
    private final int maxTileBytes;

    public PublishedTileReader(PublishedImageStore store, int maxTileBytes) {
        this.store = Objects.requireNonNull(store);
        if (maxTileBytes < 1) throw new IllegalArgumentException("maxTileBytes must be positive");
        this.maxTileBytes = maxTileBytes;
    }

    @Override
    public TileData read(PublishedImageStore.OpenedImage opened, int z, int x, int y) throws TileReadException {
        final Path path;
        try {
            path = store.resolveTile(opened, z, x, y);
        } catch (CatalogException e) {
            throw new TileReadException("published tile is unavailable", e);
        }

        try {
            long declaredSize = Files.size(path);
            if (declaredSize < 4 || declaredSize > maxTileBytes) {
                throw new TileReadException("published tile size is outside LUPA v1 limits");
            }

            byte[] bytes;
            try (InputStream in = Files.newInputStream(path)) {
                bytes = in.readNBytes(maxTileBytes + 1);
            }
            if (bytes.length < 4 || bytes.length > maxTileBytes) {
                throw new TileReadException("published tile size changed or exceeds the configured limit");
            }
            if ((bytes[0] & 0xff) != 0xff || (bytes[1] & 0xff) != 0xd8) {
                throw new TileReadException("published tile is not a JPEG");
            }

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) throw new TileReadException("published tile JPEG cannot be decoded");

            ImageLevel level = opened.manifest().levels().get(z);
            int tileSize = opened.manifest().tileSize();
            int expectedWidth = Math.min(tileSize, level.width() - x * tileSize);
            int expectedHeight = Math.min(tileSize, level.height() - y * tileSize);
            if (image.getWidth() != expectedWidth || image.getHeight() != expectedHeight) {
                throw new TileReadException("published tile dimensions disagree with the manifest");
            }

            return new TileData(bytes, expectedWidth, expectedHeight);
        } catch (IOException e) {
            throw new TileReadException("published tile could not be read", e);
        }
    }
}
