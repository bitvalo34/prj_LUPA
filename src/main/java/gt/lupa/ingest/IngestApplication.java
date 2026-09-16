package gt.lupa.ingest;

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
            default -> {
                printUsage();
                yield 2;
            }
        };
    }

    private static int runPreflight(String[] optionArgs) {
        try {
            IngestCliConfig config = IngestCliConfig.parse(optionArgs);
            PreflightService service = new PreflightService(new ProcessRunner(64 * 1024));
            PreflightReport report = service.check(config);

            System.out.println("LUPA A19 preflight OK");
            System.out.println("original=" + report.original());
            System.out.println("originalBytes=" + report.originalBytes());
            System.out.println("formatExtension=" + report.extension());
            System.out.println("dataRoot=" + report.dataRoot());
            System.out.println("usableBytes=" + report.usableBytes());
            System.out.println("libvips=" + report.vipsVersion());
            if (report.vipsOutputTruncated()) {
                System.out.println("warning=libvips version output was truncated");
            }
            return 0;
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

            StageBuildService service = new StageBuildService(
                    new PreflightService(runner),
                    new VipsImageInspector(runner),
                    new NormalizedImageBuilder(runner),
                    new DzsavePyramidBuilder(runner)
            );

            StagingResult result = service.stage(config);

            System.out.println("LUPA A19 staging OK");
            System.out.println("imageId=" + config.imageId());
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

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  preflight --original=/path/file.jpg --image-id=sample-photo --display-name=SamplePhoto --license-ref=OwnPhoto");
        System.err.println("  stage --original=/path/file.jpg --image-id=sample-photo --display-name=SamplePhoto --license-ref=OwnPhoto");
        System.err.println("Optional:");
        System.err.println("  --data-root=data --vips=vips --vipsheader=vipsheader --timeout-seconds=600 --jpeg-quality=85");
    }
}