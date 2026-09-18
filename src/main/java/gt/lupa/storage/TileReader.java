package gt.lupa.storage;

@FunctionalInterface
public interface TileReader {
    TileData read(PublishedImageStore.OpenedImage opened, int z, int x, int y) throws TileReadException;
}
