package gt.lupa.ingest;

import java.nio.file.Path;

public record PreflightReport(
        Path original,
        long originalBytes,
        String extension,
        Path dataRoot,
        long usableBytes,
        String vipsVersion,
        boolean vipsOutputTruncated) {
}
