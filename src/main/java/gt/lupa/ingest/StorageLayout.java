package gt.lupa.ingest;

import gt.lupa.storage.CatalogException;
import gt.lupa.storage.CatalogValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public final class StorageLayout {
    private final Path dataRoot;

    public StorageLayout(Path dataRoot) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot").toAbsolutePath().normalize();
    }

    public Path dataRoot() { return dataRoot; }
    public Path originals() { return dataRoot.resolve("originals"); }
    public Path staging() { return dataRoot.resolve("staging"); }
    public Path pyramids() { return dataRoot.resolve("pyramids"); }
    public Path catalog() { return dataRoot.resolve("catalog.json"); }
    public Path lockFile() { return staging().resolve(".ingest.lock"); }

    public void initialize() throws IngestException {
        try {
            Files.createDirectories(originals());
            Files.createDirectories(staging());
            Files.createDirectories(pyramids());
        } catch (IOException e) {
            throw new IngestException(4, "cannot initialize data directories under " + dataRoot, e);
        }
    }

    public Path createJobDirectory() throws IngestException {
        initialize();
        Path candidate = staging().resolve("job-" + UUID.randomUUID()).normalize();
        ensureInside(candidate, staging(), "staging job");
        try {
            return Files.createDirectory(candidate);
        } catch (IOException e) {
            throw new IngestException(4, "cannot create staging job directory", e);
        }
    }

    public Path originalVersionDirectory(String imageId, String imageVersion) throws IngestException {
        validateIdentity(imageId, imageVersion);
        Path path = originals().resolve(imageId).resolve(imageVersion).normalize();
        ensureInside(path, originals(), "original version");
        return path;
    }

    public Path publishedVersionDirectory(String imageId, String imageVersion) throws IngestException {
        validateIdentity(imageId, imageVersion);
        Path path = pyramids().resolve(imageId).resolve(imageVersion).normalize();
        ensureInside(path, pyramids(), "published version");
        return path;
    }

    private static void validateIdentity(String imageId, String imageVersion) throws IngestException {
        try {
            CatalogValidator.validateId(imageId);
            CatalogValidator.validateVersion(imageVersion);
        } catch (CatalogException e) {
            throw new IngestException(2, e.getMessage(), e);
        }
    }

    private static void ensureInside(Path path, Path root, String label) throws IngestException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot) || normalizedPath.equals(normalizedRoot)) {
            throw new IngestException(2, label + " path escaped its root");
        }
    }
}
