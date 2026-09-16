package gt.lupa.ingest;

import gt.lupa.storage.CatalogException;
import gt.lupa.storage.CatalogJson;
import gt.lupa.storage.ImageLevel;
import gt.lupa.storage.ImageManifest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TilePyramidValidator {
    public static final long MAX_TILE_BYTES = 262_144L;
    private static final Pattern TILE_NAME = Pattern.compile("^(\\d+)_(\\d+)\\.jpg$");
    private static final Pattern LEVEL_NAME = Pattern.compile("^\\d+$");

    private final ProcessRunner processRunner;
    private final VipsImageInspector imageInspector;
    private final CatalogJson catalogJson = new CatalogJson();

    public TilePyramidValidator(ProcessRunner processRunner, VipsImageInspector imageInspector) {
        this.processRunner = processRunner;
        this.imageInspector = imageInspector;
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
        long tileCount = 0L;
        long jpegBytes = 0L;

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
                    Path tile = levelDir.resolve(x + "_" + y + ".jpg");
                    long bytes = validateTile(config, tile, level, x, y);
                    try {
                        tileCount = Math.addExact(tileCount, 1L);
                        jpegBytes = Math.addExact(jpegBytes, bytes);
                    } catch (ArithmeticException e) {
                        throw new IngestException(3, "tile counters overflowed", e);
                    }
                }
            }
        }

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

    private long validateTile(IngestCliConfig config, Path tile, ImageLevel level, int x, int y)
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

        VipsRasterInfo raster = imageInspector.inspectRaster(
                config.vipsHeaderExecutable(),
                tile.toString(),
                perTileTimeout(config),
                config.dataRoot()
        );
        int expectedWidth = Math.min(PyramidMath.TILE_SIZE, level.width() - x * PyramidMath.TILE_SIZE);
        int expectedHeight = Math.min(PyramidMath.TILE_SIZE, level.height() - y * PyramidMath.TILE_SIZE);
        if (raster.width() != expectedWidth || raster.height() != expectedHeight) {
            throw new IngestException(
                    3,
                    "tile dimensions are incorrect for " + tile + "; expected "
                            + expectedWidth + "x" + expectedHeight + " but got "
                            + raster.width() + "x" + raster.height()
            );
        }
        if (raster.bands() != 3) {
            throw new IngestException(3, "published JPEG tile must have 3 bands: " + tile);
        }

        ProcessResult decode = processRunner.run(
                VipsRuntime.command(config, "avg", tile.toString()),
                perTileTimeout(config),
                config.dataRoot()
        );
        if (decode.timedOut()) {
            throw new IngestException(3, "tile decode timed out: " + tile);
        }
        if (decode.exitCode() != 0) {
            String detail = !decode.stderr().isBlank() ? decode.stderr().strip() : decode.stdout().strip();
            throw new IngestException(3, "tile is not fully decodable by libvips: " + tile + " " + detail);
        }
        return bytes;
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

    private static Duration perTileTimeout(IngestCliConfig config) {
        long seconds = Math.max(1L, Math.min(30L, config.processTimeout().toSeconds()));
        return Duration.ofSeconds(seconds);
    }
}

record PyramidValidationReport(
        ImageManifest manifest,
        long tileCount,
        long jpegBytes) {
}
