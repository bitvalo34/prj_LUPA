package gt.lupa.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Publishes a complete catalog snapshot with same-filesystem atomic replacement. */
public final class CatalogPublisher {
    private final CatalogJson json = new CatalogJson();

    public CatalogSnapshot publish(Path catalogPath, CatalogImage image) throws CatalogException {
        Path absolute = catalogPath.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new CatalogException("catalog path must have a parent directory");
        }

        CatalogSnapshot current = readExistingOrEmpty(absolute);
        List<CatalogImage> nextImages = new ArrayList<>();
        boolean replaced = false;
        for (CatalogImage existing : current.images()) {
            if (existing.imageId().equals(image.imageId())) {
                nextImages.add(image);
                replaced = true;
            } else {
                nextImages.add(existing);
            }
        }
        if (!replaced) {
            nextImages.add(image);
        }
        nextImages.sort(Comparator.comparing(CatalogImage::imageId));

        CatalogSnapshot next = CatalogValidator.validate(new CatalogSnapshot(1, List.copyOf(nextImages)));
        byte[] bytes = json.writeCatalog(next);
        Path temp = parent.resolve("." + absolute.getFileName() + ".tmp-" + UUID.randomUUID());

        try {
            Files.createDirectories(parent);
            writeAndForce(temp, bytes);
            try {
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.deleteIfExists(temp);
                throw new CatalogException("atomic catalog replacement is not supported on this filesystem", e);
            }
        } catch (CatalogException e) {
            throw e;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw new CatalogException("cannot publish catalog", e);
        }

        return next;
    }

    public CatalogSnapshot readExistingOrEmpty(Path catalogPath) throws CatalogException {
        Path absolute = catalogPath.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            return new CatalogSnapshot(1, List.of());
        }
        if (!Files.isRegularFile(absolute)) {
            throw new CatalogException("catalog path exists but is not a regular file");
        }
        try {
            return json.parseCatalog(Files.readAllBytes(absolute));
        } catch (IOException e) {
            throw new CatalogException("cannot read existing catalog", e);
        }
    }

    private static void writeAndForce(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }
}
