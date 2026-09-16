package gt.lupa.ingest;

public final class FullImportService {
    private final PreflightService preflightService;
    private final VipsImageInspector imageInspector;
    private final StageBuildService stageBuildService;
    private final TilePyramidValidator validator;
    private final PublicationService publicationService;

    public FullImportService(
            PreflightService preflightService,
            VipsImageInspector imageInspector,
            StageBuildService stageBuildService,
            TilePyramidValidator validator,
            PublicationService publicationService) {
        this.preflightService = preflightService;
        this.imageInspector = imageInspector;
        this.stageBuildService = stageBuildService;
        this.validator = validator;
        this.publicationService = publicationService;
    }

    public PublicationResult importAndPublish(IngestCliConfig config) throws IngestException {
        PreflightReport preflight = preflightService.check(config);
        ImageInspection inspection = imageInspector.inspect(config);
        SpaceEstimate estimate = SpaceEstimator.estimate(preflight.originalBytes(), inspection.sourceWidth(), inspection.sourceHeight());
        if (preflight.usableBytes() < estimate.requiredBytes()) {
            throw new IngestException(
                    4,
                    "insufficient free space for conservative import estimate; required="
                            + estimate.requiredBytes() + " usable=" + preflight.usableBytes()
            );
        }

        StorageLayout layout = new StorageLayout(config.dataRoot());
        try (ImportLock ignored = ImportLock.acquire(layout)) {
            StagingResult staging = stageBuildService.stagePrepared(config, inspection, layout);
            try {
                PyramidValidationReport validation = validator.validate(config, staging);
                return publicationService.publish(config, inspection, preflight, staging, validation);
            } catch (IngestException e) {
                try {
                    FileTreeCleaner.deleteRecursively(staging.jobDirectory());
                } catch (IngestException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
        }
    }
}

record SpaceEstimate(long baseBytes, long safetyBytes, long requiredBytes) {
}

final class SpaceEstimator {
    private static final long MINIMUM_SAFETY_BYTES = 256L * 1024L * 1024L;

    private SpaceEstimator() {
    }

    static SpaceEstimate estimate(long originalBytes, int width, int height) throws IngestException {
        try {
            long pixels = Math.multiplyExact((long) width, (long) height);
            long rgbBytes = Math.multiplyExact(pixels, 3L);
            long workIntermediates = Math.multiplyExact(rgbBytes, 3L);
            long pyramidRawEquivalent = Math.addExact(rgbBytes, (rgbBytes + 2L) / 3L);
            long base = Math.addExact(originalBytes, Math.addExact(workIntermediates, pyramidRawEquivalent));
            long safety = Math.max(MINIMUM_SAFETY_BYTES, base / 4L);
            return new SpaceEstimate(base, safety, Math.addExact(base, safety));
        } catch (ArithmeticException e) {
            throw new IngestException(2, "image dimensions overflow the import space estimate", e);
        }
    }
}
