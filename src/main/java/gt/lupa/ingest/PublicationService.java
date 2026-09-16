package gt.lupa.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gt.lupa.storage.CatalogException;
import gt.lupa.storage.CatalogImage;
import gt.lupa.storage.CatalogPublisher;
import gt.lupa.storage.ImageManifest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public final class PublicationService {
    private final CatalogPublisher catalogPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PublicationService(CatalogPublisher catalogPublisher) {
        this.catalogPublisher = catalogPublisher;
    }

    public PublicationResult publish(
            IngestCliConfig config,
            ImageInspection inspection,
            PreflightReport preflight,
            StagingResult staging,
            PyramidValidationReport validation) throws IngestException {
        StorageLayout layout = new StorageLayout(config.dataRoot());
        Path originalDir = layout.originalVersionDirectory(staging.imageId(), staging.imageVersion());
        Path publishedDir = layout.publishedVersionDirectory(staging.imageId(), staging.imageVersion());
        boolean versionMoved = false;

        try {
            if (Files.exists(originalDir) || Files.exists(publishedDir)) {
                throw new IngestException(4, "image version already exists and will not be overwritten");
            }
            Files.createDirectories(originalDir.getParent());
            Files.createDirectory(originalDir);

            String sourceHashBefore = sha256(config.original());
            long sourceBytes = Files.size(config.original());
            String extension = inspection.actualFormat() == ActualFormat.JPEG ? ".jpg" : ".tiff";
            Path copiedOriginal = originalDir.resolve("source" + extension);
            copyOriginalAtomically(config.original(), copiedOriginal);
            String copiedHash = sha256(copiedOriginal);
            String sourceHashAfter = sha256(config.original());
            if (!sourceHashBefore.equals(sourceHashAfter) || !sourceHashBefore.equals(copiedHash)) {
                throw new IngestException(4, "original hash changed or copied original does not match the source");
            }

            writePrivateMetadata(
                    originalDir.resolve("import-metadata.json"),
                    config,
                    inspection,
                    preflight,
                    validation,
                    sourceHashBefore,
                    sourceBytes
            );

            Files.createDirectories(publishedDir.getParent());
            moveVersionAtomically(staging.stagedVersionPath(), publishedDir);
            versionMoved = true;

            ImageManifest manifest = validation.manifest();
            CatalogImage image = new CatalogImage(
                    manifest.imageId(),
                    manifest.imageVersion(),
                    manifest.width(),
                    manifest.height(),
                    manifest.tileSize(),
                    manifest.levels().size() - 1
            );
            try {
                catalogPublisher.publish(layout.catalog(), image);
            } catch (CatalogException e) {
                cleanupJobBestEffort(staging.jobDirectory(), e);
                throw new IngestException(
                        4,
                        "catalog update failed after publishing a complete immutable version; "
                                + "the previous catalog remains valid and an unreferenced version remains at "
                                + publishedDir,
                        e
                );
            }

            FileTreeCleaner.deleteRecursively(staging.jobDirectory());
            return new PublicationResult(
                    staging.imageId(),
                    staging.imageVersion(),
                    publishedDir,
                    layout.catalog(),
                    copiedOriginal,
                    sourceHashBefore,
                    sourceBytes,
                    validation.tileCount(),
                    validation.jpegBytes()
            );
        } catch (IngestException e) {
            if (!versionMoved) {
                cleanupOriginalBestEffort(originalDir, e);
            }
            throw e;
        } catch (IOException e) {
            if (!versionMoved) {
                cleanupOriginalBestEffort(originalDir, e);
            }
            throw new IngestException(4, "publication failed because of an I/O error", e);
        }
    }

    private void copyOriginalAtomically(Path source, Path destination) throws IngestException {
        Path temp = destination.getParent().resolve(".source.tmp-" + UUID.randomUUID());
        try {
            Files.copy(source, temp);
            forceFile(temp);
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.deleteIfExists(temp);
                throw new IngestException(4, "atomic publication of the private original is not supported", e);
            }
        } catch (IngestException e) {
            throw e;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw new IngestException(4, "cannot copy original into private storage", e);
        }
    }

    private void writePrivateMetadata(
            Path destination,
            IngestCliConfig config,
            ImageInspection inspection,
            PreflightReport preflight,
            PyramidValidationReport validation,
            String originalSha256,
            long originalBytes) throws IngestException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("displayName", config.displayName());
        root.put("licenseRef", config.licenseRef());
        root.put("sourceFileName", config.original().getFileName().toString());
        root.put("sourceFormat", inspection.actualFormat().name());
        root.put("sourceSha256", originalSha256);
        root.put("sourceBytes", originalBytes);
        root.put("importedAtUtc", Instant.now().toString());
        root.put("libvipsVersion", preflight.vipsVersion());
        root.put("normalizedWidth", validation.manifest().width());
        root.put("normalizedHeight", validation.manifest().height());
        root.put("jpegQuality", config.jpegQuality());
        root.put("tileCount", validation.tileCount());
        root.put("tileJpegBytes", validation.jpegBytes());
        root.put("alphaBackground", "white-255-255-255");
        root.put("colorPolicy", "sRGB");
        root.put("multipagePolicy", "reject");

        Path temp = destination.getParent().resolve(".metadata.tmp-" + UUID.randomUUID());
        try {
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            writeAndForce(temp, bytes);
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.deleteIfExists(temp);
                throw new IngestException(4, "atomic private metadata publication is not supported", e);
            }
        } catch (IngestException e) {
            throw e;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw new IngestException(4, "cannot write private import metadata", e);
        }
    }

    private static void moveVersionAtomically(Path source, Path destination) throws IngestException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IngestException(
                    4,
                    "atomic version publication is not supported; staging and pyramids must be on a compatible filesystem",
                    e
            );
        } catch (IOException e) {
            throw new IngestException(4, "cannot publish immutable pyramid version", e);
        }
    }

    private static String sha256(Path path) throws IngestException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IngestException(1, "SHA-256 is unavailable", e);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IngestException(4, "cannot hash file: " + path, e);
        }
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
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

    private static void cleanupOriginalBestEffort(Path originalDir, Throwable primary) {
        try {
            FileTreeCleaner.deleteRecursively(originalDir);
        } catch (IngestException cleanup) {
            primary.addSuppressed(cleanup);
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

record PublicationResult(
        String imageId,
        String imageVersion,
        Path publishedVersionPath,
        Path catalogPath,
        Path privateOriginalPath,
        String originalSha256,
        long originalBytes,
        long tileCount,
        long tileJpegBytes) {
}
