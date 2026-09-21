package gt.lupa.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Read-only access to the published catalog and manifests.
 *
 * All file names are derived from validated identifiers. Each access verifies
 * lexical containment and rejects symbolic links in the configured data-root
 * chain before opening the file.
 */
public final class FileCatalogSource implements CatalogSource {
    private final Path catalogPath;
    private final Path dataRoot;
    private final Path pyramidsRoot;
    private final int maxJsonBytes;
    private final CatalogJson json =
            new CatalogJson();

    public FileCatalogSource(Path catalogPath) {
        this(catalogPath, 256 * 1024);
    }

    public FileCatalogSource(
            Path catalogPath,
            int maxJsonBytes) {
        if (maxJsonBytes < 1) {
            throw new IllegalArgumentException(
                    "maxJsonBytes must be positive");
        }

        this.catalogPath =
                Objects.requireNonNull(
                                catalogPath,
                                "catalogPath")
                        .toAbsolutePath()
                        .normalize();

        Path parent = this.catalogPath.getParent();
        this.dataRoot =
                parent == null
                        ? Path.of(".")
                                .toAbsolutePath()
                                .normalize()
                        : parent;

        this.pyramidsRoot =
                this.dataRoot
                        .resolve("pyramids")
                        .normalize();

        this.maxJsonBytes = maxJsonBytes;
    }

    @Override
    public CatalogSnapshot readCatalog()
            throws CatalogException {
        ensureContainedFile(
                catalogPath,
                dataRoot);

        return json.parseCatalog(
                readRegularFile(catalogPath));
    }

    public ImageManifest readManifest(
            String imageId,
            String imageVersion)
            throws CatalogException {
        CatalogValidator.validateId(imageId);
        CatalogValidator.validateVersion(
                imageVersion);

        Path versionRoot =
                pyramidsRoot
                        .resolve(imageId)
                        .resolve(imageVersion)
                        .normalize();

        ensureDescendant(
                versionRoot,
                pyramidsRoot);

        Path manifest =
                versionRoot
                        .resolve("manifest.json")
                        .normalize();

        ensureDescendant(
                manifest,
                versionRoot);
        rejectSymlinkChain(
                dataRoot,
                manifest);

        ImageManifest parsed =
                json.parseManifest(
                        readRegularFile(manifest));

        if (!parsed.imageId().equals(imageId)
                || !parsed.imageVersion()
                        .equals(imageVersion)) {
            throw new CatalogException(
                    "manifest identity does not match requested version");
        }

        return parsed;
    }

    private void ensureContainedFile(
            Path path,
            Path root) throws CatalogException {
        ensureDescendant(path, root);
        rejectSymlinkChain(root, path);
    }

    private static void ensureDescendant(
            Path path,
            Path root) throws CatalogException {
        Path normalizedPath =
                path.toAbsolutePath().normalize();
        Path normalizedRoot =
                root.toAbsolutePath().normalize();

        if (!normalizedPath.startsWith(normalizedRoot)
                || normalizedPath.equals(normalizedRoot)) {
            throw new CatalogException(
                    "published JSON path escaped its storage root");
        }
    }

    private static void rejectSymlinkChain(
            Path root,
            Path path) throws CatalogException {
        Path normalizedRoot =
                root.toAbsolutePath().normalize();
        Path normalizedPath =
                path.toAbsolutePath().normalize();

        if (!normalizedPath.startsWith(normalizedRoot)) {
            throw new CatalogException(
                    "published JSON path escaped its storage root");
        }

        if (Files.isSymbolicLink(normalizedRoot)) {
            throw new CatalogException(
                    "data root must not be a symbolic link");
        }

        Path current = normalizedRoot;

        for (Path segment :
                normalizedRoot.relativize(
                        normalizedPath)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new CatalogException(
                        "published JSON path contains a symbolic link");
            }
        }
    }

    private byte[] readRegularFile(
            Path path) throws CatalogException {
        try {
            if (!Files.isRegularFile(
                            path,
                            LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(path)) {
                throw new CatalogException(
                        "JSON data file is absent, non-regular or symbolic link");
            }

            long size = Files.size(path);
            if (size < 1
                    || size > maxJsonBytes) {
                throw new CatalogException(
                        "JSON data file size is invalid");
            }

            try (InputStream in =
                    Files.newInputStream(path)) {
                byte[] bytes =
                        in.readNBytes(
                                maxJsonBytes + 1);

                if (bytes.length > maxJsonBytes) {
                    throw new CatalogException(
                            "JSON data file exceeds size limit");
                }

                return bytes;
            }
        } catch (IOException e) {
            throw new CatalogException(
                    "cannot read JSON data file",
                    e);
        }
    }
}
