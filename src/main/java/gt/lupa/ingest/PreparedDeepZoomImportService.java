package gt.lupa.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gt.lupa.storage.CatalogException;
import gt.lupa.storage.CatalogImage;
import gt.lupa.storage.CatalogJson;
import gt.lupa.storage.CatalogPublisher;
import gt.lupa.storage.ImageLevel;
import gt.lupa.storage.ImageManifest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Imports an already generated DeepZoom filesystem pyramid into the immutable
 * LUPA layout without duplicating the huge source PNG.
 *
 * <p>The prepared source is treated as untrusted: only the exact tiles expected
 * from PyramidMath are linked/copied into staging, a fresh LUPA manifest is
 * written, and TilePyramidValidator fully decodes every resulting JPEG before
 * atomic publication.</p>
 */
public final class PreparedDeepZoomImportService {
    private final CatalogJson catalogJson = new CatalogJson();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TilePyramidValidator validator;
    private final CatalogPublisher catalogPublisher;

    public PreparedDeepZoomImportService() {
        this(new TilePyramidValidator(), new CatalogPublisher());
    }

    PreparedDeepZoomImportService(TilePyramidValidator validator, CatalogPublisher catalogPublisher) {
        this.validator = validator;
        this.catalogPublisher = catalogPublisher;
    }

    public PreparedDeepZoomPublicationResult importAndPublish(PreparedDeepZoomConfig config)
            throws IngestException {
        validateSourceRoot(config.dzFilesRoot());
        PyramidPlan plan = PyramidMath.plan(config.width(), config.height());
        StorageLayout layout = new StorageLayout(config.dataRoot());
        layout.initialize();

        try (ImportLock ignored = ImportLock.acquire(layout)) {
            String imageVersion = VersionPlanner.nextVersion(layout, config.imageId());
            Path jobDirectory = layout.createJobDirectory();
            Path stagedVersion = jobDirectory.resolve("publish")
                    .resolve(config.imageId())
                    .resolve(imageVersion);

            try {
                System.out.println("[EVAL] preparing DeepZoom pyramid for LUPA...");
                linkExpectedTiles(config.dzFilesRoot(), stagedVersion.resolve("tiles"), plan);
                writeManifest(stagedVersion.resolve("manifest.json"), config.imageId(), imageVersion, plan);

                StagingResult staging = new StagingResult(
                        jobDirectory,
                        stagedVersion,
                        config.imageId(),
                        imageVersion,
                        plan.width(),
                        plan.height(),
                        plan.maxLevel(),
                        plan.levels().size());

                IngestCliConfig validationConfig = new IngestCliConfig(
                        Path.of(config.sourceArchive()),
                        config.imageId(),
                        config.displayName(),
                        config.licenseRef(),
                        config.dataRoot(),
                        "vips",
                        "vipsheader",
                        java.time.Duration.ofSeconds(1),
                        config.jpegQuality(),
                        OriginalPolicy.REFERENCE);

                System.out.println("[EVAL] validating prepared LUPA pyramid...");
                PyramidValidationReport validation = validator.validate(validationConfig, staging);

                Path metadataDir = layout.originalVersionDirectory(config.imageId(), imageVersion);
                if (Files.exists(metadataDir, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IngestException(4, "prepared provenance version already exists: " + metadataDir);
                }
                Files.createDirectories(metadataDir.getParent());
                Files.createDirectory(metadataDir);
                writeProvenance(metadataDir.resolve("import-metadata.json"), config, imageVersion, validation);

                Path publishedDir = layout.publishedVersionDirectory(config.imageId(), imageVersion);
                if (Files.exists(publishedDir, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IngestException(4, "prepared pyramid version already exists: " + publishedDir);
                }
                Files.createDirectories(publishedDir.getParent());
                moveAtomically(stagedVersion, publishedDir);

                ImageManifest manifest = validation.manifest();
                try {
                    catalogPublisher.publish(
                            layout.catalog(),
                            new CatalogImage(
                                    manifest.imageId(),
                                    manifest.imageVersion(),
                                    manifest.width(),
                                    manifest.height(),
                                    manifest.tileSize(),
                                    manifest.levels().size() - 1));
                } catch (CatalogException e) {
                    throw new IngestException(
                            4,
                            "catalog update failed after prepared pyramid publication; complete unreferenced version remains at "
                                    + publishedDir,
                            e);
                }

                FileTreeCleaner.deleteRecursively(jobDirectory);
                return new PreparedDeepZoomPublicationResult(
                        config.imageId(),
                        imageVersion,
                        publishedDir,
                        layout.catalog(),
                        metadataDir.resolve("import-metadata.json"),
                        validation.tileCount(),
                        validation.jpegBytes());
            } catch (IOException e) {
                cleanupJobBestEffort(jobDirectory, e);
                throw new IngestException(4, "prepared DeepZoom import failed because of an I/O error", e);
            } catch (IngestException e) {
                cleanupJobBestEffort(jobDirectory, e);
                throw e;
            }
        }
    }

    private static void validateSourceRoot(Path root) throws IngestException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IngestException(2, "dz-files must be a real directory, not a symlink: " + root);
        }
    }

    private static void linkExpectedTiles(Path sourceRoot, Path targetRoot, PyramidPlan plan)
            throws IngestException, IOException {
        for (PyramidLevel level : plan.levels()) {
            Path sourceLevel = sourceRoot.resolve(Integer.toString(level.z()));
            if (!Files.isDirectory(sourceLevel, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sourceLevel)) {
                throw new IngestException(3, "prepared DeepZoom level is missing or invalid: " + level.z());
            }
            Path targetLevel = targetRoot.resolve(Integer.toString(level.z()));
            Files.createDirectories(targetLevel);

            for (int y = 0; y < level.rows(); y++) {
                for (int x = 0; x < level.columns(); x++) {
                    String name = x + "_" + y + ".jpg";
                    Path source = sourceLevel.resolve(name);
                    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
                        throw new IngestException(3, "prepared DeepZoom tile is missing or invalid: " + source);
                    }
                    Path target = targetLevel.resolve(name);
                    try {
                        Files.createLink(target, source);
                    } catch (UnsupportedOperationException | IOException linkFailure) {
                        try {
                            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                        } catch (IOException copyFailure) {
                            copyFailure.addSuppressed(linkFailure);
                            throw copyFailure;
                        }
                    }
                }
            }
        }
    }

    private void writeManifest(Path destination, String imageId, String imageVersion, PyramidPlan plan)
            throws IngestException {
        List<ImageLevel> levels = plan.levels().stream()
                .map(level -> new ImageLevel(level.z(), level.width(), level.height()))
                .toList();
        ImageManifest manifest = new ImageManifest(
                1,
                imageId,
                imageVersion,
                plan.width(),
                plan.height(),
                PyramidMath.TILE_SIZE,
                0,
                "onetile",
                levels);
        try {
            byte[] bytes = catalogJson.writeManifest(manifest);
            Files.createDirectories(destination.getParent());
            Files.write(destination, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (CatalogException e) {
            throw new IngestException(3, "prepared manifest does not satisfy the LUPA contract", e);
        } catch (IOException e) {
            throw new IngestException(4, "cannot write prepared manifest", e);
        }
    }

    private void writeProvenance(
            Path destination,
            PreparedDeepZoomConfig config,
            String imageVersion,
            PyramidValidationReport validation) throws IngestException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("importKind", "prepared-deepzoom-stream");
        root.put("imageId", config.imageId());
        root.put("imageVersion", imageVersion);
        root.put("displayName", config.displayName());
        root.put("licenseRef", config.licenseRef());
        root.put("sourceArchive", config.sourceArchive());
        root.put("sourceArchiveBytes", config.sourceArchiveBytes());
        root.put("sourceArchiveSha256", config.sourceArchiveSha256());
        root.put("sourceMember", config.sourceMember());
        root.put("sourceMemberBytes", config.sourceMemberBytes());
        root.put("normalizedWidth", config.width());
        root.put("normalizedHeight", config.height());
        root.put("jpegQuality", config.jpegQuality());
        root.put("tileCount", validation.tileCount());
        root.put("tileJpegBytes", validation.jpegBytes());
        root.put("preparedDzFilesRoot", config.dzFilesRoot().toString());
        root.put("importedAtUtc", Instant.now().toString());
        root.put("sourceIntegrityPolicy", "archive SHA-256 supplied from authoritative remote metadata; prepared tiles fully validated locally");

        Path temp = destination.getParent().resolve(".metadata.tmp-" + UUID.randomUUID());
        try {
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            writeAndForce(temp, bytes);
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.deleteIfExists(temp);
                throw new IngestException(4, "atomic prepared metadata publication is not supported", e);
            }
        } catch (IngestException e) {
            throw e;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw new IngestException(4, "cannot write prepared import metadata", e);
        }
    }

    private static void moveAtomically(Path source, Path destination) throws IngestException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IngestException(4, "atomic prepared pyramid publication is not supported", e);
        } catch (IOException e) {
            throw new IngestException(4, "cannot publish prepared pyramid", e);
        }
    }

    private static void writeAndForce(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void cleanupJobBestEffort(Path jobDirectory, Throwable primary) {
        try {
            FileTreeCleaner.deleteRecursively(jobDirectory);
        } catch (IngestException cleanup) {
            primary.addSuppressed(cleanup);
        }
    }
}

record PreparedDeepZoomPublicationResult(
        String imageId,
        String imageVersion,
        Path publishedVersionPath,
        Path catalogPath,
        Path provenancePath,
        long tileCount,
        long tileJpegBytes) {
}
