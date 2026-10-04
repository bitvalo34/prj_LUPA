package gt.lupa.ingest;

import gt.lupa.storage.CatalogException;
import gt.lupa.storage.CatalogValidator;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Configuration for publishing an already generated DeepZoom pyramid. */
public record PreparedDeepZoomConfig(
        Path dzFilesRoot,
        int width,
        int height,
        String imageId,
        String displayName,
        String licenseRef,
        Path dataRoot,
        int jpegQuality,
        String sourceArchive,
        String sourceMember,
        long sourceArchiveBytes,
        String sourceArchiveSha256,
        long sourceMemberBytes) {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public static PreparedDeepZoomConfig parse(String[] args) throws IngestException {
        Map<String, String> values = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IngestException(2, "arguments must use --name=value syntax: " + arg);
            }
            int equals = arg.indexOf('=');
            String key = arg.substring(2, equals);
            String value = arg.substring(equals + 1);
            if (key.isBlank() || value.isBlank()) {
                throw new IngestException(2, "argument name and value must not be blank");
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new IngestException(2, "duplicate argument: --" + key);
            }
        }

        rejectUnknown(values);
        Path dzFilesRoot = Path.of(required(values, "dz-files")).toAbsolutePath().normalize();
        int width = parseInt(required(values, "width"), "width", 1, Integer.MAX_VALUE);
        int height = parseInt(required(values, "height"), "height", 1, Integer.MAX_VALUE);
        String imageId = required(values, "image-id");
        try {
            CatalogValidator.validateId(imageId);
        } catch (CatalogException e) {
            throw new IngestException(2, e.getMessage(), e);
        }
        String displayName = bounded(required(values, "display-name").strip(), "display-name", 200);
        String licenseRef = bounded(required(values, "license-ref").strip(), "license-ref", 500);
        Path dataRoot = Path.of(values.getOrDefault("data-root", "data")).toAbsolutePath().normalize();
        int jpegQuality = parseInt(values.getOrDefault("jpeg-quality", "85"), "jpeg-quality", 40, 95);
        String sourceArchive = bounded(required(values, "source-archive").strip(), "source-archive", 2000);
        String sourceMember = bounded(required(values, "source-member").strip(), "source-member", 1000);
        long sourceArchiveBytes = parseLong(required(values, "source-archive-bytes"), "source-archive-bytes", 1L, Long.MAX_VALUE);
        long sourceMemberBytes = parseLong(required(values, "source-member-bytes"), "source-member-bytes", 1L, Long.MAX_VALUE);
        String sourceArchiveSha256 = required(values, "source-archive-sha256").toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(sourceArchiveSha256).matches()) {
            throw new IngestException(2, "source-archive-sha256 must be exactly 64 hexadecimal characters");
        }

        return new PreparedDeepZoomConfig(
                dzFilesRoot,
                width,
                height,
                imageId,
                displayName,
                licenseRef,
                dataRoot,
                jpegQuality,
                sourceArchive,
                sourceMember,
                sourceArchiveBytes,
                sourceArchiveSha256,
                sourceMemberBytes);
    }

    private static void rejectUnknown(Map<String, String> values) throws IngestException {
        for (String key : values.keySet()) {
            if (!switch (key) {
                case "dz-files", "width", "height", "image-id", "display-name", "license-ref",
                     "data-root", "jpeg-quality", "source-archive", "source-member",
                     "source-archive-bytes", "source-archive-sha256", "source-member-bytes" -> true;
                default -> false;
            }) {
                throw new IngestException(2, "unknown argument: --" + key);
            }
        }
    }

    private static String required(Map<String, String> values, String name) throws IngestException {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IngestException(2, "missing required argument: --" + name);
        }
        return value;
    }

    private static String bounded(String value, String name, int maxLength) throws IngestException {
        if (value.isBlank()) {
            throw new IngestException(2, name + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IngestException(2, name + " is too long");
        }
        return value;
    }

    private static int parseInt(String value, String name, int min, int max) throws IngestException {
        long parsed = parseLong(value, name, min, max);
        return (int) parsed;
    }

    private static long parseLong(String value, String name, long min, long max) throws IngestException {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < min || parsed > max) {
                throw new IngestException(2, name + " must be between " + min + " and " + max);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IngestException(2, name + " must be an integer", e);
        }
    }
}
