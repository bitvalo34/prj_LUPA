package gt.lupa.ingest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

public final class VipsImageInspector {
    private final ProcessRunner processRunner;

    public VipsImageInspector(ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    public ImageInspection inspect(IngestCliConfig config)
            throws IngestException {

        Path original = config.original();
        String originalPath = original.toString();

        if (originalPath.contains("[") || originalPath.contains("]")) {
            throw new IngestException(
                    2,
                    "original path cannot contain [ or ] because libvips uses that syntax for loader options"
            );
        }

        String loader = readRequiredString(
                config.vipsHeaderExecutable(),
                originalPath,
                "vips-loader",
                config.processTimeout(),
                config.dataRoot()
        );

        ActualFormat actualFormat = detectActualFormat(loader);

        validateExtensionMatchesActualFormat(original, actualFormat);

        int width = readRequiredInt(
                config.vipsHeaderExecutable(),
                originalPath,
                "width",
                config.processTimeout(),
                config.dataRoot()
        );

        int height = readRequiredInt(
                config.vipsHeaderExecutable(),
                originalPath,
                "height",
                config.processTimeout(),
                config.dataRoot()
        );

        int bands = readRequiredInt(
                config.vipsHeaderExecutable(),
                originalPath,
                "bands",
                config.processTimeout(),
                config.dataRoot()
        );

        String interpretation = readRequiredString(
                config.vipsHeaderExecutable(),
                originalPath,
                "interpretation",
                config.processTimeout(),
                config.dataRoot()
        );

        if (width <= 0 || height <= 0) {
            throw new IngestException(
                    2,
                    "invalid image dimensions reported by libvips: "
                            + width + "x" + height
            );
        }

        int pageCount = 1;
        String loadSpec = originalPath;

        if (actualFormat == ActualFormat.TIFF) {
            pageCount = readOptionalInt(
                    config.vipsHeaderExecutable(),
                    originalPath,
                    "n-pages",
                    config.processTimeout(),
                    config.dataRoot()
            ).orElse(1);

            if (pageCount > 1) {
                throw new IngestException(
                        2,
                        "A19 rejects multipage TIFF files; n-pages="
                                + pageCount
                );
            }

            // Explicitly select the first and only accepted page.
            loadSpec = originalPath + "[page=0,n=1]";
        }

        boolean hasAlpha = hasAlpha(bands, interpretation);

        return new ImageInspection(
                original,
                loadSpec,
                actualFormat,
                loader,
                width,
                height,
                bands,
                interpretation,
                hasAlpha,
                pageCount
        );
    }

    public VipsRasterInfo inspectRaster(
            String vipsHeaderExecutable,
            String sourceSpec,
            Duration timeout,
            Path workDir) throws IngestException {

        int width = readRequiredInt(
                vipsHeaderExecutable,
                sourceSpec,
                "width",
                timeout,
                workDir
        );

        int height = readRequiredInt(
                vipsHeaderExecutable,
                sourceSpec,
                "height",
                timeout,
                workDir
        );

        int bands = readRequiredInt(
                vipsHeaderExecutable,
                sourceSpec,
                "bands",
                timeout,
                workDir
        );

        String interpretation = readRequiredString(
                vipsHeaderExecutable,
                sourceSpec,
                "interpretation",
                timeout,
                workDir
        );

        if (width <= 0 || height <= 0) {
            throw new IngestException(
                    2,
                    "invalid image dimensions reported by libvips: "
                            + width + "x" + height
            );
        }

        if (bands <= 0) {
            throw new IngestException(
                    2,
                    "invalid band count reported by libvips: " + bands
            );
        }

        return new VipsRasterInfo(
                width,
                height,
                bands,
                interpretation
        );
    }

    private ActualFormat detectActualFormat(String loader)
            throws IngestException {

        String normalized = loader.toLowerCase(Locale.ROOT);

        if (normalized.contains("jpegload")) {
            return ActualFormat.JPEG;
        }

        if (normalized.contains("tiffload")) {
            return ActualFormat.TIFF;
        }

        throw new IngestException(
                2,
                "unsupported actual image format. "
                        + "A19 accepts JPEG or TIFF, but libvips selected loader: "
                        + loader
        );
    }

    private void validateExtensionMatchesActualFormat(
            Path original,
            ActualFormat actualFormat) throws IngestException {

        String name = original
                .getFileName()
                .toString()
                .toLowerCase(Locale.ROOT);

        boolean jpegExtension =
                name.endsWith(".jpg")
                        || name.endsWith(".jpeg");

        boolean tiffExtension =
                name.endsWith(".tif")
                        || name.endsWith(".tiff");

        boolean compatible =
                (actualFormat == ActualFormat.JPEG && jpegExtension)
                        || (actualFormat == ActualFormat.TIFF && tiffExtension);

        if (!compatible) {
            throw new IngestException(
                    2,
                    "file extension does not match the actual image format "
                            + "detected by libvips; file="
                            + original.getFileName()
                            + ", actualFormat="
                            + actualFormat
            );
        }
    }

    private static boolean hasAlpha(
            int bands,
            String interpretation) throws IngestException {

        String normalized =
                interpretation == null
                        ? ""
                        : interpretation.toLowerCase(Locale.ROOT);

        return switch (bands) {
            case 1 -> false;
            case 2 -> true;
            case 3 -> false;

            // CMYK normally uses four colour bands,
            // not RGB + alpha.
            case 4 -> !normalized.equals("cmyk");

            default -> throw new IngestException(
                    2,
                    "unsupported band layout: bands="
                            + bands
                            + ", interpretation="
                            + interpretation
            );
        };
    }

    private int readRequiredInt(
            String executable,
            String sourceSpec,
            String field,
            Duration timeout,
            Path workDir) throws IngestException {

        String value = readRequiredString(
                executable,
                sourceSpec,
                field,
                timeout,
                workDir
        );

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IngestException(
                    3,
                    "libvips returned a non-integer value for "
                            + field
                            + ": "
                            + value,
                    e
            );
        }
    }

    private OptionalInt readOptionalInt(
            String executable,
            String sourceSpec,
            String field,
            Duration timeout,
            Path workDir) throws IngestException {

        ProcessResult result = processRunner.run(
                List.of(
                        executable,
                        "-f",
                        field,
                        sourceSpec
                ),
                timeout,
                workDir
        );

        if (result.timedOut()) {
            throw new IngestException(
                    3,
                    "timed out while reading optional field "
                            + field
            );
        }

        if (result.exitCode() != 0) {
            return OptionalInt.empty();
        }

        String text =
                firstNonBlank(
                        result.stdout(),
                        result.stderr()
                );

        if (text.isEmpty()) {
            return OptionalInt.empty();
        }

        try {
            return OptionalInt.of(
                    Integer.parseInt(text)
            );
        } catch (NumberFormatException e) {
            throw new IngestException(
                    3,
                    "libvips returned a non-integer value for "
                            + field
                            + ": "
                            + text,
                    e
            );
        }
    }

    private String readRequiredString(
            String executable,
            String sourceSpec,
            String field,
            Duration timeout,
            Path workDir) throws IngestException {

        ProcessResult result = processRunner.run(
                List.of(
                        executable,
                        "-f",
                        field,
                        sourceSpec
                ),
                timeout,
                workDir
        );

        if (result.timedOut()) {
            throw new IngestException(
                    3,
                    "timed out while reading "
                            + field
                            + " with vipsheader"
            );
        }

        if (result.exitCode() != 0) {
            throw new IngestException(
                    3,
                    "vipsheader failed while reading "
                            + field
                            + ": "
                            + diagnostic(result)
            );
        }

        String text =
                firstNonBlank(
                        result.stdout(),
                        result.stderr()
                );

        if (text.isEmpty()) {
            throw new IngestException(
                    3,
                    "libvips returned an empty value for "
                            + field
            );
        }

        return text;
    }

    private static String firstNonBlank(
            String stdout,
            String stderr) {

        if (stdout != null && !stdout.isBlank()) {
            return stdout.strip();
        }

        if (stderr != null && !stderr.isBlank()) {
            return stderr.strip();
        }

        return "";
    }

    private static String diagnostic(
            ProcessResult result) {

        String text =
                firstNonBlank(
                        result.stderr(),
                        result.stdout()
                );

        return text.isEmpty()
                ? "exitCode=" + result.exitCode()
                : text;
    }
}

enum ActualFormat {
    JPEG,
    TIFF
}

record ImageInspection(
        Path original,
        String loadSpec,
        ActualFormat actualFormat,
        String loader,
        int sourceWidth,
        int sourceHeight,
        int bands,
        String interpretation,
        boolean hasAlpha,
        int pageCount) {
}

record VipsRasterInfo(
        int width,
        int height,
        int bands,
        String interpretation) {
}