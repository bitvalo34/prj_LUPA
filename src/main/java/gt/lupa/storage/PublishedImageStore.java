package gt.lupa.storage;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Read-only resolver for complete, catalog-published image versions.
 * It does not expose originals or staging and does not perform A19's exhaustive tile validation again.
 */
public final class PublishedImageStore {
    private final Path dataRoot;
    private final Path pyramidsRoot;
    private final FileCatalogSource catalogSource;

    public PublishedImageStore(Path dataRoot) {
        this(dataRoot, 256 * 1024);
    }

    public PublishedImageStore(Path dataRoot, int maxJsonBytes) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot").toAbsolutePath().normalize();
        this.pyramidsRoot = this.dataRoot.resolve("pyramids").normalize();
        this.catalogSource = new FileCatalogSource(this.dataRoot.resolve("catalog.json"), maxJsonBytes);
    }

    public CatalogSnapshot readCatalog() throws CatalogException {
        return catalogSource.readCatalog();
    }

    public OpenedImage openCurrent(String imageId) throws CatalogException {
        CatalogValidator.validateId(imageId);
        CatalogImage catalogImage = readCatalog().images().stream()
                .filter(image -> image.imageId().equals(imageId))
                .findFirst()
                .orElseThrow(() -> new CatalogException("image is not published"));

        ImageManifest manifest = catalogSource.readManifest(imageId, catalogImage.imageVersion());
        if (manifest.width() != catalogImage.width()
                || manifest.height() != catalogImage.height()
                || manifest.tileSize() != catalogImage.tileSize()
                || manifest.levels().size() - 1 != catalogImage.maxLevel()) {
            throw new CatalogException("catalog and manifest disagree for published image");
        }
        return new OpenedImage(catalogImage, manifest);
    }

    public Path resolveTile(OpenedImage opened, int z, int x, int y) throws CatalogException {
        Objects.requireNonNull(opened, "opened");
        if (z < 0 || z >= opened.manifest().levels().size()) {
            throw new CatalogException("tile level is outside the manifest");
        }
        if (x < 0 || y < 0) {
            throw new CatalogException("tile coordinates must be nonnegative");
        }

        ImageLevel level = opened.manifest().levels().get(z);
        int columns = ceilDivide(level.width(), opened.manifest().tileSize());
        int rows = ceilDivide(level.height(), opened.manifest().tileSize());
        if (x >= columns || y >= rows) {
            throw new CatalogException("tile coordinates are outside the manifest level");
        }

        Path versionRoot = pyramidsRoot
                .resolve(opened.catalogImage().imageId())
                .resolve(opened.catalogImage().imageVersion())
                .normalize();
        ensureInside(versionRoot, pyramidsRoot);
        Path tile = versionRoot
                .resolve("tiles")
                .resolve(Integer.toString(z))
                .resolve(x + "_" + y + ".jpg")
                .normalize();
        ensureInside(tile, versionRoot);
        rejectSymlinkChain(tile);

        if (!Files.isRegularFile(tile, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(tile)) {
            throw new CatalogException("published tile is absent or invalid");
        }
        return tile;
    }

    private void rejectSymlinkChain(Path path) throws CatalogException {
        Path normalized = path.toAbsolutePath().normalize();
        ensureInside(normalized, dataRoot);
        if (Files.isSymbolicLink(dataRoot)) {
            throw new CatalogException("data root must not be a symbolic link");
        }
        Path current = dataRoot;
        for (Path segment : dataRoot.relativize(normalized)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new CatalogException("published path contains a symbolic link");
            }
        }
    }

    private static void ensureInside(Path path, Path root) throws CatalogException {
        Path normalizedPath = path.toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot) || normalizedPath.equals(normalizedRoot)) {
            throw new CatalogException("published path escaped its storage root");
        }
    }

    private static int ceilDivide(int value, int divisor) {
        return (int) ((value + (long) divisor - 1L) / divisor);
    }

    public record OpenedImage(CatalogImage catalogImage, ImageManifest manifest) {
        public OpenedImage {
            Objects.requireNonNull(catalogImage, "catalogImage");
            Objects.requireNonNull(manifest, "manifest");
        }
    }
}
