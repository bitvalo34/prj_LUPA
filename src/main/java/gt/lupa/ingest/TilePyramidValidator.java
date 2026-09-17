package gt.lupa.ingest;

import gt.lupa.storage.CatalogException;
import gt.lupa.storage.CatalogJson;
import gt.lupa.storage.ImageLevel;
import gt.lupa.storage.ImageManifest;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

public final class TilePyramidValidator {
    public static final long MAX_TILE_BYTES = 262_144L;
    private static final Pattern TILE_NAME = Pattern.compile("^(\\d+)_(\\d+)\\.jpg$");
    private static final Pattern LEVEL_NAME = Pattern.compile("^\\d+$");
    private static final int DEFAULT_WORKERS = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final int IN_FLIGHT_MULTIPLIER = 4;

    private final CatalogJson catalogJson = new CatalogJson();
    private final int validationWorkers;

    public TilePyramidValidator() {
        this(DEFAULT_WORKERS);
    }

    public TilePyramidValidator(ProcessRunner ignoredProcessRunner, VipsImageInspector ignoredImageInspector) {
        this(DEFAULT_WORKERS);
    }

    TilePyramidValidator(int validationWorkers) {
        if (validationWorkers < 1 || validationWorkers > 8) {
            throw new IllegalArgumentException("validationWorkers must be between 1 and 8");
        }
        this.validationWorkers = validationWorkers;
    }

    public PyramidValidationReport validate(IngestCliConfig config, StagingResult staging)
            throws IngestException {
        Path manifestPath = staging.stagedVersionPath().resolve("manifest.json");
        ImageManifest manifest = readManifest(manifestPath);
        verifyManifestIdentity(config, staging, manifest);

        Path tilesRoot = staging.stagedVersionPath().resolve("tiles");
        if (!Files.isDirectory(tilesRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(tilesRoot)) {
            throw new IngestException(3, "tiles root is missing or invalid: " + tilesRoot);
        }

        validateLevelDirectorySet(tilesRoot, manifest.levels().size());
        long totalExpected = totalExpectedTiles(manifest);
        long tileCount = 0L;
        long jpegBytes = 0L;
        ProgressPrinter progress = new ProgressPrinter(totalExpected);

        ExecutorService executor = Executors.newFixedThreadPool(validationWorkers, runnable -> {
            Thread thread = new Thread(runnable, "lupa-tile-validator");
            thread.setDaemon(true);
            return thread;
        });
        ArrayDeque<Future<TileValidationResult>> inFlight = new ArrayDeque<>();
        int maxInFlight = Math.multiplyExact(validationWorkers, IN_FLIGHT_MULTIPLIER);

        try {
            for (ImageLevel level : manifest.levels()) {
                int columns = PyramidMath.ceilDivide(level.width(), manifest.tileSize());
                int rows = PyramidMath.ceilDivide(level.height(), manifest.tileSize());
                if (level.z() == 0 && (columns != 1 || rows != 1)) {
                    throw new IngestException(3, "level 0 must contain exactly one tile");
                }

                Path levelDir = tilesRoot.resolve(Integer.toString(level.z()));
                validateLevelFileSet(levelDir, columns, rows, level.z());

                for (int y = 0; y < rows; y++) {
                    for (int x = 0; x < columns; x++) {
                        final int tileX = x;
                        final int tileY = y;
                        Path tile = levelDir.resolve(tileX + "_" + tileY + ".jpg");
                        inFlight.addLast(executor.submit(() -> validateTile(tile, level, tileX, tileY)));

                        if (inFlight.size() >= maxInFlight) {
                            TileValidationResult completed = await(inFlight.removeFirst());
                            tileCount = Math.addExact(tileCount, 1L);
                            jpegBytes = Math.addExact(jpegBytes, completed.bytes());
                            progress.maybePrint(tileCount);
                        }
                    }
                }
            }

            while (!inFlight.isEmpty()) {
                TileValidationResult completed = await(inFlight.removeFirst());
                tileCount = Math.addExact(tileCount, 1L);
                jpegBytes = Math.addExact(jpegBytes, completed.bytes());
                progress.maybePrint(tileCount);
            }
        } catch (ArithmeticException e) {
            cancelOutstanding(inFlight);
            throw new IngestException(3, "tile counters overflowed", e);
        } catch (IngestException e) {
            cancelOutstanding(inFlight);
            throw e;
        } finally {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("A19 warning: tile validation workers did not stop within 5 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        progress.finish(tileCount);
        return new PyramidValidationReport(manifest, tileCount, jpegBytes);
    }

    private ImageManifest readManifest(Path path) throws IngestException {
        try {
            return catalogJson.parseManifest(Files.readAllBytes(path));
        } catch (CatalogException e) {
            throw new IngestException(3, "manifest does not satisfy the S19 contract", e);
        } catch (IOException e) {
            throw new IngestException(4, "cannot read staged manifest", e);
        }
    }

    private static void verifyManifestIdentity(
            IngestCliConfig config,
            StagingResult staging,
            ImageManifest manifest) throws IngestException {
        if (!manifest.imageId().equals(config.imageId())
                || !manifest.imageId().equals(staging.imageId())
                || !manifest.imageVersion().equals(staging.imageVersion())) {
            throw new IngestException(3, "manifest identity does not match the staged import");
        }
        if (manifest.width() != staging.width()
                || manifest.height() != staging.height()
                || manifest.levels().size() != staging.levelCount()
                || manifest.levels().size() - 1 != staging.maxLevel()) {
            throw new IngestException(3, "manifest dimensions or levels do not match the staged import");
        }
    }

    private static long totalExpectedTiles(ImageManifest manifest) throws IngestException {
        long total = 0L;
        try {
            for (ImageLevel level : manifest.levels()) {
                long columns = PyramidMath.ceilDivide(level.width(), manifest.tileSize());
                long rows = PyramidMath.ceilDivide(level.height(), manifest.tileSize());
                total = Math.addExact(total, Math.multiplyExact(columns, rows));
            }
            return total;
        } catch (ArithmeticException e) {
            throw new IngestException(3, "expected tile count overflowed", e);
        }
    }

    private static void validateLevelDirectorySet(Path tilesRoot, int expectedLevels) throws IngestException {
        Set<Integer> seen = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tilesRoot)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(entry)
                        || !LEVEL_NAME.matcher(name).matches()) {
                    throw new IngestException(3, "unexpected entry in tiles root: " + name);
                }
                int z;
                try {
                    z = Integer.parseInt(name);
                } catch (NumberFormatException e) {
                    throw new IngestException(3, "invalid level directory name: " + name, e);
                }
                if (z < 0 || z >= expectedLevels || !seen.add(z)) {
                    throw new IngestException(3, "unexpected level directory: " + name);
                }
            }
        } catch (IOException e) {
            throw new IngestException(4, "cannot enumerate tile levels", e);
        }
        if (seen.size() != expectedLevels) {
            throw new IngestException(3, "missing tile level directory");
        }
    }

    private static void validateLevelFileSet(Path levelDir, int columns, int rows, int z)
            throws IngestException {
        if (!Files.isDirectory(levelDir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(levelDir)) {
            throw new IngestException(3, "missing or invalid tile level directory: " + z);
        }
        long actual = 0L;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(levelDir)) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry)) {
                    throw new IngestException(3, "unexpected non-file entry in level " + z + ": " + entry.getFileName());
                }
                Matcher matcher = TILE_NAME.matcher(entry.getFileName().toString());
                if (!matcher.matches()) {
                    throw new IngestException(3, "unexpected tile filename at level " + z + ": " + entry.getFileName());
                }
                int x;
                int y;
                try {
                    x = Integer.parseInt(matcher.group(1));
                    y = Integer.parseInt(matcher.group(2));
                } catch (NumberFormatException e) {
                    throw new IngestException(3, "invalid tile coordinate at level " + z, e);
                }
                if (x < 0 || x >= columns || y < 0 || y >= rows) {
                    throw new IngestException(3, "unexpected tile coordinate at level " + z + ": " + entry.getFileName());
                }
                actual++;
            }
        } catch (IOException e) {
            throw new IngestException(4, "cannot enumerate tiles at level " + z, e);
        }
        long expected = (long) columns * (long) rows;
        if (actual != expected) {
            throw new IngestException(3, "level " + z + " expected " + expected + " tiles but found " + actual);
        }
    }

    private static TileValidationResult validateTile(Path tile, ImageLevel level, int x, int y)
            throws IngestException {
        if (!Files.isRegularFile(tile, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(tile)) {
            throw new IngestException(3, "missing tile: " + tile);
        }

        long bytes;
        try {
            bytes = Files.size(tile);
        } catch (IOException e) {
            throw new IngestException(4, "cannot read tile size: " + tile, e);
        }
        if (bytes <= 0L || bytes > MAX_TILE_BYTES) {
            throw new IngestException(3, "tile size violates LUPA limit: " + tile + " bytes=" + bytes);
        }
        verifyJpegSignature(tile);

        int expectedWidth = Math.min(PyramidMath.TILE_SIZE, level.width() - x * PyramidMath.TILE_SIZE);
        int expectedHeight = Math.min(PyramidMath.TILE_SIZE, level.height() - y * PyramidMath.TILE_SIZE);
        decodeAndValidateJpeg(tile, expectedWidth, expectedHeight);

        return new TileValidationResult(bytes);
    }

    private static void decodeAndValidateJpeg(Path tile, int expectedWidth, int expectedHeight)
            throws IngestException {
        try (ImageInputStream input = ImageIO.createImageInputStream(tile.toFile())) {
            if (input == null) {
                throw new IngestException(3, "cannot open tile as an image: " + tile);
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IngestException(3, "tile has no available image decoder: " + tile);
            }

            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("jpeg") && !format.equals("jpg")) {
                    throw new IngestException(3, "tile is not decoded as JPEG: " + tile + " format=" + format);
                }

                reader.setInput(input, true, true);
                int actualWidth = reader.getWidth(0);
                int actualHeight = reader.getHeight(0);
                if (actualWidth != expectedWidth || actualHeight != expectedHeight) {
                    throw new IngestException(
                            3,
                            "tile dimensions are incorrect for " + tile + "; expected "
                                    + expectedWidth + "x" + expectedHeight + " but got "
                                    + actualWidth + "x" + actualHeight
                    );
                }

                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw new IngestException(3, "tile JPEG decoder returned no image: " + tile);
                }
                if (decoded.getRaster().getNumBands() != 3) {
                    throw new IngestException(3, "published JPEG tile must decode to 3 bands: " + tile);
                }
            } finally {
                reader.dispose();
            }
        } catch (IngestException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new IngestException(3, "tile is not fully decodable as JPEG: " + tile, e);
        }
    }

    private static void verifyJpegSignature(Path tile) throws IngestException {
        try (InputStream input = Files.newInputStream(tile)) {
            int first = input.read();
            int second = input.read();
            if (first != 0xff || second != 0xd8) {
                throw new IngestException(3, "tile does not have a JPEG signature: " + tile);
            }
        } catch (IOException e) {
            throw new IngestException(4, "cannot inspect JPEG signature: " + tile, e);
        }
    }

    private static TileValidationResult await(Future<TileValidationResult> future) throws IngestException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException(3, "tile validation interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IngestException ingestException) {
                throw ingestException;
            }
            throw new IngestException(3, "unexpected tile validation failure", cause);
        }
    }

    private static void cancelOutstanding(ArrayDeque<Future<TileValidationResult>> futures) {
        for (Future<TileValidationResult> future : futures) {
            future.cancel(true);
        }
        futures.clear();
    }

    private static final class ProgressPrinter {
        private final long total;
        private long nextPercent = 10L;

        private ProgressPrinter(long total) {
            this.total = total;
            System.out.printf("[A19] validating tiles 0 / %d (0.0%%)%n", total);
        }

        private void maybePrint(long completed) {
            if (total <= 0L) {
                return;
            }
            long percent = completed * 100L / total;
            if (percent >= nextPercent || completed == total) {
                System.out.printf("[A19] validating tiles %d / %d (%.1f%%)%n",
                        completed, total, completed * 100.0 / total);
                while (nextPercent <= percent) {
                    nextPercent += 10L;
                }
            }
        }

        private void finish(long completed) {
            if (completed == total && total > 0L) {
                System.out.printf("[A19] validation complete: %d tiles%n", completed);
            }
        }
    }
}

record TileValidationResult(long bytes) {
}

record PyramidValidationReport(
        ImageManifest manifest,
        long tileCount,
        long jpegBytes) {
}
