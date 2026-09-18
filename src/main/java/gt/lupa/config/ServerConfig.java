package gt.lupa.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public record ServerConfig(
        String host,
        int port,
        String catalogMode,
        Path catalogPath,
        int maxHeaderBytes,
        int maxConnections,
        int workerThreads,
        int workerQueueCapacity,
        int ioThreads,
        Duration headerTimeout,
        int maxResourceBytes,
        int maxCatalogBytes,
        boolean webSocketAllowNoOrigin) {

    public ServerConfig(
            String host,
            int port,
            String catalogMode,
            Path catalogPath,
            int maxHeaderBytes,
            int maxConnections,
            int workerThreads,
            int workerQueueCapacity,
            int ioThreads,
            Duration headerTimeout,
            int maxResourceBytes,
            int maxCatalogBytes) {
        this(
                host,
                port,
                catalogMode,
                catalogPath,
                maxHeaderBytes,
                maxConnections,
                workerThreads,
                workerQueueCapacity,
                ioThreads,
                headerTimeout,
                maxResourceBytes,
                maxCatalogBytes,
                true);
    }

    public ServerConfig {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host is required");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port must be 0..65535");
        if (!"fixture".equals(catalogMode) && !"file".equals(catalogMode)) {
            throw new IllegalArgumentException("catalogMode must be fixture or file");
        }
        if (catalogPath == null) throw new IllegalArgumentException("catalogPath is required");
        catalogPath = catalogPath.toAbsolutePath().normalize();
        if (maxHeaderBytes < 1024) throw new IllegalArgumentException("maxHeaderBytes too small");
        if (maxConnections < 1 || workerThreads < 1 || workerQueueCapacity < 1 || ioThreads < 1) {
            throw new IllegalArgumentException("thread/queue/connection limits must be positive");
        }
        if (headerTimeout == null || headerTimeout.isZero() || headerTimeout.isNegative()) {
            throw new IllegalArgumentException("headerTimeout must be positive");
        }
        if (maxResourceBytes < 1 || maxCatalogBytes < 1) {
            throw new IllegalArgumentException("byte limits must be positive");
        }
    }

    public Path dataRoot() {
        Path parent = catalogPath.getParent();
        return parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
    }

    public static ServerConfig defaults() {
        int cpus = Math.max(2, Runtime.getRuntime().availableProcessors());
        Path dataRoot = Path.of("data").toAbsolutePath().normalize();
        return new ServerConfig(
                "127.0.0.1",
                8080,
                "file",
                dataRoot.resolve("catalog.json"),
                16 * 1024,
                128,
                Math.min(8, cpus),
                256,
                Math.min(4, cpus),
                Duration.ofSeconds(5),
                1024 * 1024,
                256 * 1024,
                true
        );
    }

    public static ServerConfig fromArgs(String[] args) {
        ServerConfig defaults = defaults();
        Map<String, String> values = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("arguments must use --name=value");
            }
            int eq = arg.indexOf('=');
            String key = arg.substring(2, eq);
            String value = arg.substring(eq + 1);
            if (key.isBlank() || value.isBlank()) {
                throw new IllegalArgumentException("argument name and value must not be blank");
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("duplicate argument: --" + key);
            }
        }

        for (String key : values.keySet()) {
            if (!switch (key) {
                case "host", "port", "catalog", "data-root", "catalog-path",
                        "max-header-bytes", "max-connections", "header-timeout-ms",
                        "ws-allow-no-origin" -> true;
                default -> false;
            }) {
                throw new IllegalArgumentException("unknown argument: --" + key);
            }
        }

        String catalogMode = values.getOrDefault("catalog", defaults.catalogMode());
        Path dataRoot = values.containsKey("data-root")
                ? normalizedPath(values.get("data-root"), "data-root")
                : defaults.dataRoot();
        Path catalogPath = values.containsKey("catalog-path")
                ? normalizedPath(values.get("catalog-path"), "catalog-path")
                : dataRoot.resolve("catalog.json").normalize();

        if (values.containsKey("data-root") && values.containsKey("catalog-path")) {
            Path expected = dataRoot.resolve("catalog.json").normalize();
            if (!catalogPath.equals(expected)) {
                throw new IllegalArgumentException(
                        "--catalog-path must equal <data-root>/catalog.json when --data-root is supplied");
            }
        }

        return new ServerConfig(
                values.getOrDefault("host", defaults.host()),
                parseInt(values, "port", defaults.port()),
                catalogMode,
                catalogPath,
                parseInt(values, "max-header-bytes", defaults.maxHeaderBytes()),
                parseInt(values, "max-connections", defaults.maxConnections()),
                defaults.workerThreads(),
                defaults.workerQueueCapacity(),
                defaults.ioThreads(),
                Duration.ofMillis(parseInt(
                        values,
                        "header-timeout-ms",
                        (int) defaults.headerTimeout().toMillis())),
                defaults.maxResourceBytes(),
                defaults.maxCatalogBytes(),
                parseBoolean(values, "ws-allow-no-origin", defaults.webSocketAllowNoOrigin())
        );
    }

    private static Path normalizedPath(String value, String name) {
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("--" + name + " is not a valid path", e);
        }
    }

    private static int parseInt(Map<String, String> values, String name, int fallback) {
        String value = values.get(name);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " must be an integer", e);
        }
    }

    private static boolean parseBoolean(Map<String, String> values, String name, boolean fallback) {
        String value = values.get(name);
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("--" + name + " must be true or false");
    }
}
