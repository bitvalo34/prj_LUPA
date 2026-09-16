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
        System.out.println("[A19] preflight...");
        PreflightReport preflight = preflightService.check(config);

        System.out.println("[A19] inspecting original...");
        ImageInspection inspection = imageInspector.inspect(config);

        SpaceEstimate estimate = SpaceEstimator.estimate(
                preflight.originalBytes(),
                inspection.sourceWidth(),
                inspection.sourceHeight(),
                config.originalPolicy()
        );

        System.out.println("[A19] preflight summary");
        System.out.println("[A19] originalBytes=" + preflight.originalBytes());
        System.out.println("[A19] dimensions=" + inspection.sourceWidth() + "x" + inspection.sourceHeight());
        System.out.println("[A19] dataRoot=" + preflight.dataRoot());
        System.out.println("[A19] usableBytes=" + preflight.usableBytes());
        System.out.println("[A19] estimatedRequiredBytes=" + estimate.requiredBytes());
        System.out.println("[A19] originalPolicy=" + config.originalPolicy().cliValue());

        if (preflight.usableBytes() < estimate.requiredBytes()) {
            throw new IngestException(
                    4,
                    "insufficient free space for conservative import estimate; required="
                            + estimate.requiredBytes() + " usable=" + preflight.usableBytes()
            );
        }

        StorageLayout layout = new StorageLayout(config.dataRoot());
        try (ImportLock ignored = ImportLock.acquire(layout)) {
            System.out.println("[A19] staging and generating pyramid...");
            StagingResult staging = stageBuildService.stagePrepared(config, inspection, layout);
            try {
                System.out.println("[A19] validating pyramid...");
                PyramidValidationReport validation = validator.validate(config, staging);

                System.out.println("[A19] preserving original metadata/data...");
                PublicationResult result = publicationService.publish(config, inspection, preflight, staging, validation);
                System.out.println("[A19] publication complete");
                return result;
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

    static SpaceEstimate estimate(long originalBytes, int width, int height, OriginalPolicy originalPolicy)
            throws IngestException {
        try {
            long pixels = Math.multiplyExact((long) width, (long) height);
            long rgbBytes = Math.multiplyExact(pixels, 3L);
            long workIntermediates = Math.multiplyExact(rgbBytes, 3L);
            long pyramidRawEquivalent = Math.addExact(rgbBytes, (rgbBytes + 2L) / 3L);
            long preservedOriginalBytes = originalPolicy == OriginalPolicy.COPY ? originalBytes : 0L;
            long base = Math.addExact(
                    preservedOriginalBytes,
                    Math.addExact(workIntermediates, pyramidRawEquivalent)
            );
            long safety = Math.max(MINIMUM_SAFETY_BYTES, base / 4L);
            return new SpaceEstimate(base, safety, Math.addExact(base, safety));
        } catch (ArithmeticException e) {
            throw new IngestException(2, "image dimensions overflow the import space estimate", e);
        }
    }
}
