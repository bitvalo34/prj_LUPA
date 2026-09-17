package gt.lupa.ingest;

import gt.lupa.storage.CatalogException;
import gt.lupa.storage.CatalogValidator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class StageBuildService {
    private final PreflightService preflightService;
    private final VipsImageInspector imageInspector;
    private final NormalizedImageBuilder normalizedImageBuilder;
    private final DzsavePyramidBuilder dzsavePyramidBuilder;

    public StageBuildService(
            PreflightService preflightService,
            VipsImageInspector imageInspector,
            NormalizedImageBuilder normalizedImageBuilder,
            DzsavePyramidBuilder dzsavePyramidBuilder) {
        this.preflightService = preflightService;
        this.imageInspector = imageInspector;
        this.normalizedImageBuilder = normalizedImageBuilder;
        this.dzsavePyramidBuilder = dzsavePyramidBuilder;
    }

    public StagingResult stage(IngestCliConfig config) throws IngestException {
        preflightService.check(config);
        ImageInspection inspection = imageInspector.inspect(config);
        StorageLayout layout = new StorageLayout(config.dataRoot());

        try (ImportLock ignored = ImportLock.acquire(layout)) {
            return stagePrepared(config, inspection, layout);
        }
    }

    StagingResult stagePrepared(IngestCliConfig config, ImageInspection inspection, StorageLayout layout)
            throws IngestException {
        String imageVersion = VersionPlanner.nextVersion(layout, config.imageId());
        Path jobDirectory = layout.createJobDirectory();

        try {
            System.out.println("[A19] normalizing image...");
            NormalizedImage normalizedImage = normalizedImageBuilder.build(config, inspection, jobDirectory);
            PyramidPlan plan = PyramidMath.plan(normalizedImage.width(), normalizedImage.height());
            System.out.println("[A19] generating pyramid: levels=" + plan.levels().size()
                    + " maxLevel=" + plan.maxLevel()
                    + " normalized=" + plan.width() + "x" + plan.height());
            return dzsavePyramidBuilder.build(
                    config,
                    config.imageId(),
                    imageVersion,
                    jobDirectory,
                    normalizedImage,
                    plan
            );
        } catch (IngestException e) {
            try {
                FileTreeCleaner.deleteRecursively(jobDirectory);
            } catch (IngestException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        } catch (RuntimeException e) {
            try {
                FileTreeCleaner.deleteRecursively(jobDirectory);
            } catch (IngestException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw new IngestException(1, "unexpected runtime failure during staging", e);
        }
    }
}

record StagingResult(
        Path jobDirectory,
        Path stagedVersionPath,
        String imageId,
        String imageVersion,
        int width,
        int height,
        int maxLevel,
        int levelCount) {
}

final class VersionPlanner {
    private static final Pattern VERSION = Pattern.compile("^v([1-9]\\d{0,9})$");
    private static final long MAX_VERSION_NUMBER = 9_999_999_999L;

    private VersionPlanner() {
    }

    static String nextVersion(StorageLayout layout, String imageId) throws IngestException {
        try {
            CatalogValidator.validateId(imageId);
        } catch (CatalogException e) {
            throw new IngestException(2, e.getMessage(), e);
        }
        layout.initialize();
        long max = Math.max(
                maxVersionUnder(layout.pyramids().resolve(imageId)),
                maxVersionUnder(layout.originals().resolve(imageId))
        );
        if (max >= MAX_VERSION_NUMBER) {
            throw new IngestException(4, "no further imageVersion can be allocated for " + imageId);
        }
        return "v" + (max + 1L);
    }

    private static long maxVersionUnder(Path imageRoot) throws IngestException {
        if (!Files.isDirectory(imageRoot)) {
            return 0L;
        }
        long max = 0L;
        try (Stream<Path> stream = Files.list(imageRoot)) {
            for (Path child : stream.filter(Files::isDirectory).toList()) {
                Matcher matcher = VERSION.matcher(child.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                long numeric = Long.parseLong(matcher.group(1));
                max = Math.max(max, numeric);
            }
            return max;
        } catch (IOException | NumberFormatException e) {
            throw new IngestException(4, "cannot inspect existing versions under " + imageRoot, e);
        }
    }
}

final class FileTreeCleaner {
    private FileTreeCleaner() {
    }

    static void deleteRecursively(Path root) throws IngestException {
        if (root == null || !Files.exists(root)) {
            return;
        }

        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw new IngestException(4, "cannot clean failed staging directory " + root, e.getCause());
        } catch (IOException e) {
            throw new IngestException(4, "cannot clean failed staging directory " + root, e);
        }
    }
}
