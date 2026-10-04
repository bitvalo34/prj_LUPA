package gt.lupa.ingest;

import gt.lupa.storage.CatalogPublisher;
import java.util.Arrays;

public final class IngestApplication {
    private IngestApplication() {
    }

    public static void main(String[] args) {
        int exit = run(args);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static int run(String[] args) {
        if (args.length == 0) {
            printUsage();
            return 2;
        }

        String command = args[0];
        String[] optionArgs = Arrays.copyOfRange(args, 1, args.length);

        return switch (command) {
            case "preflight" -> runPreflight(optionArgs);
            case "stage" -> runStage(optionArgs);
            case "import" -> runImport(optionArgs);
            case "import-prepared-dz" -> runPreparedDeepZoomImport(optionArgs);
            default -> {
                printUsage();
                yield 2;
            }
        };
    }

    private static int runPreflight(String[] optionArgs) {
        try {
            IngestCliConfig config = IngestCliConfig.parse(optionArgs);
            ProcessRunner runner = new ProcessRunner(64 * 1024);
            PreflightService service = new PreflightService(runner);
            VipsImageInspector inspector = new VipsImageInspector(runner);
            PreflightReport report = service.check(config);
            ImageInspection inspection = inspector.inspect(config);
            SpaceEstimate estimate = SpaceEstimator.estimate(
                    report.originalBytes(),
                    inspection.sourceWidth(),
                    inspection.sourceHeight(),
                    config.originalPolicy()
            );

            System.out.println("LUPA A19 preflight OK");
            System.out.println("original=" + report.original());
            System.out.println("originalBytes=" + report.originalBytes());
            System.out.println("formatExtension=" + report.extension());
            System.out.println("dimensions=" + inspection.sourceWidth() + "x" + inspection.sourceHeight());
            System.out.println("dataRoot=" + report.dataRoot());
            System.out.println("usableBytes=" + report.usableBytes());
            System.out.println("estimatedRequiredBytes=" + estimate.requiredBytes());
            System.out.println("originalPolicy=" + config.originalPolicy().cliValue());
            System.out.println("preflightSpace=" + (report.usableBytes() >= estimate.requiredBytes() ? "OK" : "INSUFFICIENT"));
            System.out.println("libvips=" + report.vipsVersion());
            if (report.vipsOutputTruncated()) {
                System.out.println("warning=libvips version output was truncated");
            }
            return report.usableBytes() >= estimate.requiredBytes() ? 0 : 4;
        } catch (IngestException e) {
            System.err.println("A19 error: " + e.getMessage());
            return e.exitCode();
        } catch (RuntimeException e) {
            System.err.println("A19 unexpected error: " + e.getMessage());
            return 1;
        }
    }

    private static int runStage(String[] optionArgs) {
        try {
            IngestCliConfig config = IngestCliConfig.parse(optionArgs);
            ProcessRunner runner = new ProcessRunner(128 * 1024);
            StageBuildService service = createStageService(runner);
            StagingResult result = service.stage(config);

            System.out.println("LUPA A19 staging OK");
            System.out.println("imageId=" + result.imageId());
            System.out.println("imageVersion=" + result.imageVersion());
            System.out.println("jobDirectory=" + result.jobDirectory());
            System.out.println("stagedVersionPath=" + result.stagedVersionPath());
            System.out.println("normalizedWidth=" + result.width());
            System.out.println("normalizedHeight=" + result.height());
            System.out.println("tileSize=" + PyramidMath.TILE_SIZE);
            System.out.println("maxLevel=" + result.maxLevel());
            System.out.println("levelCount=" + result.levelCount());
            System.out.println("manifest=" + result.stagedVersionPath().resolve("manifest.json"));
            System.out.println("tilesRoot=" + result.stagedVersionPath().resolve("tiles"));
            return 0;
        } catch (IngestException e) {
            System.err.println("A19 error: " + e.getMessage());
            return e.exitCode();
        } catch (RuntimeException e) {
            System.err.println("A19 unexpected error: " + e.getMessage());
            return 1;
        }
    }

    private static int runImport(String[] optionArgs) {
        try {
            IngestCliConfig config = IngestCliConfig.parse(optionArgs);
            ProcessRunner runner = new ProcessRunner(128 * 1024);
            PreflightService preflight = new PreflightService(runner);
            VipsImageInspector inspector = new VipsImageInspector(runner);
            StageBuildService stage = new StageBuildService(
                    preflight,
                    inspector,
                    new NormalizedImageBuilder(runner),
                    new DzsavePyramidBuilder(runner)
            );
            FullImportService service = new FullImportService(
                    preflight,
                    inspector,
                    stage,
                    new TilePyramidValidator(),
                    new PublicationService(new CatalogPublisher())
            );
            PublicationResult result = service.importAndPublish(config);

            System.out.println("LUPA A19 import OK");
            System.out.println("imageId=" + result.imageId());
            System.out.println("imageVersion=" + result.imageVersion());
            System.out.println("publishedVersionPath=" + result.publishedVersionPath());
            System.out.println("catalog=" + result.catalogPath());
            System.out.println("originalPolicy=" + result.originalPolicy().cliValue());
            System.out.println("privateOriginal=" + result.privateOriginalPath());
            System.out.println("originalSha256=" + result.originalSha256());
            System.out.println("originalBytes=" + result.originalBytes());
            System.out.println("tileCount=" + result.tileCount());
            System.out.println("tileJpegBytes=" + result.tileJpegBytes());
            return 0;
        } catch (IngestException e) {
            System.err.println("A19 error: " + e.getMessage());
            return e.exitCode();
        } catch (RuntimeException e) {
            System.err.println("A19 unexpected error: " + e.getMessage());
            return 1;
        }
    }

    private static int runPreparedDeepZoomImport(String[] optionArgs) {
        try {
            PreparedDeepZoomConfig config = PreparedDeepZoomConfig.parse(optionArgs);
            PreparedDeepZoomPublicationResult result = new PreparedDeepZoomImportService().importAndPublish(config);

            System.out.println("LUPA prepared DeepZoom import OK");
            System.out.println("imageId=" + result.imageId());
            System.out.println("imageVersion=" + result.imageVersion());
            System.out.println("publishedVersionPath=" + result.publishedVersionPath());
            System.out.println("catalog=" + result.catalogPath());
            System.out.println("provenance=" + result.provenancePath());
            System.out.println("tileCount=" + result.tileCount());
            System.out.println("tileJpegBytes=" + result.tileJpegBytes());
            return 0;
        } catch (IngestException e) {
            System.err.println("Prepared DeepZoom import error: " + e.getMessage());
            return e.exitCode();
        } catch (RuntimeException e) {
            System.err.println("Prepared DeepZoom unexpected error: " + e.getMessage());
            return 1;
        }
    }

    private static StageBuildService createStageService(ProcessRunner runner) {
        return new StageBuildService(
                new PreflightService(runner),
                new VipsImageInspector(runner),
                new NormalizedImageBuilder(runner),
                new DzsavePyramidBuilder(runner)
        );
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  preflight --original=/path/file.jpg --image-id=sample-photo --display-name=SamplePhoto --license-ref=OwnPhoto");
        System.err.println("  stage --original=/path/file.jpg --image-id=sample-photo --display-name=SamplePhoto --license-ref=OwnPhoto");
        System.err.println("  import --original=/path/file.jpg --image-id=sample-photo --display-name=SamplePhoto --license-ref=OwnPhoto");
        System.err.println("  import-prepared-dz --dz-files=/path/pyramid_files --width=75471 --height=75471 --image-id=eval-17 --display-name='Evaluation 17 GB' --license-ref='Authorized evaluation sample' --source-archive='Erwin:PROYECTO-2-IMAGES/Imagenes-28G-17G-Comprimidas.zip' --source-archive-bytes=4159630824 --source-archive-sha256=<64hex> --source-member=017-110-000-24650032.png --source-member-bytes=17114955374");
        System.err.println("Optional:");
        System.err.println("  --data-root=data --vips=vips --vipsheader=vipsheader --timeout-seconds=600 --jpeg-quality=85");
        System.err.println("  --original-policy=copy|reference (default: copy)");
    }
}
