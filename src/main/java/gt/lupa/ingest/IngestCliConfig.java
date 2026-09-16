package gt.lupa.ingest;

import gt.lupa.storage.CatalogException;
import gt.lupa.storage.CatalogValidator;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public record IngestCliConfig(
        Path original,
        String imageId,
        String displayName,
        String licenseRef,
        Path dataRoot,
        String vipsExecutable,
        String vipsHeaderExecutable,
        Duration processTimeout,
        int jpegQuality) {

    public static IngestCliConfig parse(String[] args) throws IngestException {
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
        Path original = Path.of(required(values, "original")).toAbsolutePath().normalize();
        String imageId = required(values, "image-id");
        validateImageId(imageId);
        String displayName = required(values, "display-name").strip();
        String licenseRef = required(values, "license-ref").strip();
        if (displayName.length() > 200) {
            throw new IngestException(2, "display-name is too long");
        }
        if (licenseRef.length() > 500) {
            throw new IngestException(2, "license-ref is too long");
        }

        Path dataRoot = Path.of(values.getOrDefault("data-root", "data")).toAbsolutePath().normalize();
        String vips = values.getOrDefault("vips", "vips");
        String vipsHeader = values.getOrDefault("vipsheader", "vipsheader");
        long timeoutSeconds = parseLong(values.getOrDefault("timeout-seconds", "600"), "timeout-seconds", 1, 7200);
        int quality = (int) parseLong(values.getOrDefault("jpeg-quality", "85"), "jpeg-quality", 40, 95);
        return new IngestCliConfig(
                original,
                imageId,
                displayName,
                licenseRef,
                dataRoot,
                vips,
                vipsHeader,
                Duration.ofSeconds(timeoutSeconds),
                quality);
    }

    private static void rejectUnknown(Map<String, String> values) throws IngestException {
        for (String key : values.keySet()) {
            if (!switch (key) {
                case "original", "image-id", "display-name", "license-ref", "data-root",
                     "vips", "vipsheader", "timeout-seconds", "jpeg-quality" -> true;
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

    private static void validateImageId(String imageId) throws IngestException {
        try {
            CatalogValidator.validateId(imageId);
        } catch (CatalogException e) {
            throw new IngestException(2, e.getMessage(), e);
        }
    }
}
