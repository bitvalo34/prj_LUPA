package gt.lupa.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class NormalizedImageBuilder {
    private final ProcessRunner processRunner;

    public NormalizedImageBuilder(
            ProcessRunner processRunner) {

        this.processRunner = processRunner;
    }

    public NormalizedImage build(
            IngestCliConfig config,
            ImageInspection inspection,
            Path jobDirectory)
            throws IngestException {

        Path workDirectory =
                jobDirectory.resolve("work");

        try {
            Files.createDirectories(workDirectory);
        } catch (IOException e) {
            throw new IngestException(
                    4,
                    "cannot create work directory",
                    e
            );
        }

        Path orientedPath =
                workDirectory.resolve("oriented.v");

        Path colourPath =
                workDirectory.resolve("colour.v");

        Path normalizedPath =
                workDirectory.resolve("normalized.v");

        /*
         * Orientation is deliberately applied as a libvips
         * operation instead of passing an `autorotate`
         * property to arbitrary loaders.
         */
        runRequired(
                List.of(
                        config.vipsExecutable(),
                        "autorot",
                        inspection.loadSpec(),
                        orientedPath.toString()
                ),
                config,
                "autorotation failed"
        );

        /*
         * Convert image colour data to sRGB.
         * libvips colour operations preserve extra bands
         * such as alpha.
         */
        runRequired(
                List.of(
                        config.vipsExecutable(),
                        "colourspace",
                        orientedPath.toString(),
                        colourPath.toString(),
                        "srgb"
                ),
                config,
                "sRGB colour conversion failed"
        );

        if (inspection.hasAlpha()) {
            /*
             * JPEG has no alpha. Compose transparency
             * against the documented white background.
             */
            runRequired(
                    List.of(
                            config.vipsExecutable(),
                            "flatten",
                            colourPath.toString(),
                            normalizedPath.toString(),
                            "--background=255,255,255"
                    ),
                    config,
                    "alpha flattening failed"
            );
        } else {
            runRequired(
                    List.of(
                            config.vipsExecutable(),
                            "copy",
                            colourPath.toString(),
                            normalizedPath.toString()
                    ),
                    config,
                    "normalized image copy failed"
            );
        }

        VipsImageInspector inspector =
                new VipsImageInspector(processRunner);

        VipsRasterInfo info =
                inspector.inspectRaster(
                        config.vipsHeaderExecutable(),
                        normalizedPath.toString(),
                        config.processTimeout(),
                        config.dataRoot()
                );

        if (info.bands() != 3) {
            throw new IngestException(
                    3,
                    "normalized image must contain exactly "
                            + "3 RGB bands, but libvips reported "
                            + info.bands()
            );
        }

        return new NormalizedImage(
                normalizedPath,
                info.width(),
                info.height(),
                info.bands()
        );
    }

    private void runRequired(
            List<String> command,
            IngestCliConfig config,
            String failureMessage)
            throws IngestException {

        ProcessResult result =
                processRunner.run(
                        command,
                        config.processTimeout(),
                        config.dataRoot()
                );

        if (result.timedOut()) {
            throw new IngestException(
                    3,
                    failureMessage + ": timed out"
            );
        }

        if (result.exitCode() != 0) {
            throw new IngestException(
                    3,
                    failureMessage
                            + ": "
                            + diagnostic(result)
            );
        }
    }

    private static String diagnostic(
            ProcessResult result) {

        if (!result.stderr().isBlank()) {
            return result.stderr().strip();
        }

        if (!result.stdout().isBlank()) {
            return result.stdout().strip();
        }

        return "exitCode=" + result.exitCode();
    }
}

record NormalizedImage(
        Path file,
        int width,
        int height,
        int bands) {
}