package gt.lupa.ingest;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PreflightService {
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("jpg", "jpeg", "tif", "tiff");
    private final ProcessRunner processRunner;

    public PreflightService(ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    public PreflightReport check(IngestCliConfig config) throws IngestException {
        Path original = config.original();
        try {
            if (!Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(original)) {
                throw new IngestException(2, "original must be a regular non-symbolic file: " + original);
            }
            if (!Files.isReadable(original)) {
                throw new IngestException(2, "original is not readable: " + original);
            }
        } catch (SecurityException e) {
            throw new IngestException(2, "cannot inspect original permissions", e);
        }

        String extension = extensionOf(original);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IngestException(2, "A19 accepts JPEG or TIFF originals; extension was: " + extension);
        }

        long originalBytes;
        try {
            originalBytes = Files.size(original);
        } catch (IOException e) {
            throw new IngestException(2, "cannot read original file size", e);
        }
        if (originalBytes <= 0) {
            throw new IngestException(2, "original file is empty");
        }

        StorageLayout layout = new StorageLayout(config.dataRoot());
        layout.initialize();
        long usableBytes;
        try {
            FileStore store = Files.getFileStore(layout.dataRoot());
            usableBytes = store.getUsableSpace();
        } catch (IOException e) {
            throw new IngestException(4, "cannot determine usable space for data root", e);
        }

        ProcessResult version = processRunner.run(
                List.of(config.vipsExecutable(), "--version"),
                config.processTimeout(),
                layout.dataRoot());
        if (version.timedOut()) {
            throw new IngestException(3, "libvips version check timed out");
        }
        if (version.exitCode() != 0) {
            String detail = !version.stderr().isBlank() ? version.stderr().strip() : version.stdout().strip();
            throw new IngestException(3, "libvips version check failed: " + detail);
        }
        String vipsVersion = !version.stdout().isBlank() ? version.stdout().strip() : version.stderr().strip();
        if (vipsVersion.isBlank()) {
            throw new IngestException(3, "libvips returned an empty version string");
        }

        return new PreflightReport(
                original,
                originalBytes,
                extension,
                layout.dataRoot(),
                usableBytes,
                vipsVersion,
                version.stdoutTruncated() || version.stderrTruncated());
    }

    private static String extensionOf(Path path) throws IngestException {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            throw new IngestException(2, "original has no usable file extension");
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
