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
        boolean webSocketAllowNoOrigin,
        int maxWebSocketConnections,
        int maxSessions,
        int diskThreads,
        int diskQueueCapacity,
        int metadataThreads,
        int metadataQueueCapacity,
        Duration webSocketCloseTimeout,
        long tileCacheBytes,
        long transientTileBytes,
        int maxTileReads,
        int drrQuantumBytes,
        Duration helloTimeout,
        Duration pingInterval,
        Duration pongTimeout,
        Duration releaseTimeout,
        Duration writeProgressTimeout) {

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
            int maxCatalogBytes,
            boolean webSocketAllowNoOrigin) {
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
                webSocketAllowNoOrigin,
                Math.min(64, maxConnections),
                Math.min(32, Math.min(64, maxConnections)),
                defaultDiskThreads(),
                64,
                defaultMetadataThreads(),
                32,
                Duration.ofSeconds(2),
                128L * 1024L * 1024L,
                16L * 1024L * 1024L,
                8,
                128 * 1024,
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10));
    }

    public ServerConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host is required");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be 0..65535");
        }
        if (!"fixture".equals(catalogMode) && !"file".equals(catalogMode)) {
            throw new IllegalArgumentException(
                    "catalogMode must be fixture or file");
        }
        if (catalogPath == null) {
            throw new IllegalArgumentException("catalogPath is required");
        }

        catalogPath = catalogPath.toAbsolutePath().normalize();

        if (maxHeaderBytes < 1024) {
            throw new IllegalArgumentException(
                    "maxHeaderBytes too small");
        }

        if (maxConnections < 1
                || workerThreads < 1
                || workerQueueCapacity < 1
                || ioThreads < 1
                || maxWebSocketConnections < 1
                || maxSessions < 1
                || diskThreads < 1
                || diskQueueCapacity < 1
                || metadataThreads < 1
                || metadataQueueCapacity < 1
                || maxTileReads < 1
                || drrQuantumBytes < 1) {
            throw new IllegalArgumentException(
                    "thread/queue/connection/session limits must be positive");
        }

        if (maxWebSocketConnections > maxConnections) {
            throw new IllegalArgumentException(
                    "maxWebSocketConnections cannot exceed maxConnections");
        }

        if (maxSessions > maxWebSocketConnections) {
            throw new IllegalArgumentException(
                    "maxSessions cannot exceed maxWebSocketConnections");
        }

        requirePositive(headerTimeout, "headerTimeout");
        requirePositive(
                webSocketCloseTimeout,
                "webSocketCloseTimeout");
        requirePositive(helloTimeout, "helloTimeout");
        requirePositive(pingInterval, "pingInterval");
        requirePositive(pongTimeout, "pongTimeout");
        requirePositive(releaseTimeout, "releaseTimeout");
        requirePositive(
                writeProgressTimeout,
                "writeProgressTimeout");

        if (drrQuantumBytes > 262_144) {
            throw new IllegalArgumentException(
                    "drrQuantumBytes cannot exceed maximum TILE payload bytes");
        }

        if (tileCacheBytes < 262_144L) {
            throw new IllegalArgumentException(
                    "tileCacheBytes must hold at least one maximum LUPA tile");
        }

        long maximumEnvelopeBytes =
                4L + 4096L + 262_144L;

        long minimumTransientBytes =
                Math.multiplyExact(
                        (long) maxSessions,
                        maximumEnvelopeBytes);

        if (transientTileBytes < minimumTransientBytes) {
            throw new IllegalArgumentException(
                    "transientTileBytes must cover one maximum TILE envelope per active session");
        }

        long diskSubmissionCapacity =
                (long) diskThreads
                        + (long) diskQueueCapacity;

        if (maxTileReads > diskSubmissionCapacity) {
            throw new IllegalArgumentException(
                    "maxTileReads cannot exceed disk threads plus disk queue capacity");
        }

        if (maxResourceBytes < 1
                || maxCatalogBytes < 1) {
            throw new IllegalArgumentException(
                    "byte limits must be positive");
        }
    }

    public Path dataRoot() {
        Path parent = catalogPath.getParent();
        return parent == null
                ? Path.of(".")
                        .toAbsolutePath()
                        .normalize()
                : parent;
    }

    public static ServerConfig defaults() {
        int cpus = availableProcessors();

        Path dataRoot =
                Path.of("data")
                        .toAbsolutePath()
                        .normalize();

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
                true,
                64,
                32,
                Math.min(4, cpus),
                64,
                Math.min(2, cpus),
                32,
                Duration.ofSeconds(2),
                128L * 1024L * 1024L,
                16L * 1024L * 1024L,
                8,
                128 * 1024,
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10));
    }

    public static ServerConfig fromArgs(
            String[] args) {
        ServerConfig defaults = defaults();

        Map<String, String> values =
                new HashMap<>();

        for (String arg : args) {
            if (!arg.startsWith("--")
                    || !arg.contains("=")) {
                throw new IllegalArgumentException(
                        "arguments must use --name=value");
            }

            int eq = arg.indexOf('=');
            String key = arg.substring(2, eq);
            String value = arg.substring(eq + 1);

            if (key.isBlank()
                    || value.isBlank()) {
                throw new IllegalArgumentException(
                        "argument name and value must not be blank");
            }

            if (values.putIfAbsent(key, value)
                    != null) {
                throw new IllegalArgumentException(
                        "duplicate argument: --" + key);
            }
        }

        for (String key : values.keySet()) {
            if (!switch (key) {
                case "host",
                        "port",
                        "catalog",
                        "data-root",
                        "catalog-path",
                        "max-header-bytes",
                        "max-connections",
                        "worker-threads",
                        "worker-queue-capacity",
                        "io-threads",
                        "header-timeout-ms",
                        "ws-allow-no-origin",
                        "max-ws-connections",
                        "max-sessions",
                        "disk-threads",
                        "disk-queue-capacity",
                        "metadata-threads",
                        "metadata-queue-capacity",
                        "ws-close-timeout-ms",
                        "tile-cache-bytes",
                        "transient-tile-bytes",
                        "max-tile-reads",
                        "drr-quantum-bytes",
                        "hello-timeout-ms",
                        "ping-interval-ms",
                        "pong-timeout-ms",
                        "release-timeout-ms",
                        "write-progress-timeout-ms" -> true;
                default -> false;
            }) {
                throw new IllegalArgumentException(
                        "unknown argument: --" + key);
            }
        }

        String catalogMode =
                values.getOrDefault(
                        "catalog",
                        defaults.catalogMode());

        Path dataRoot =
                values.containsKey("data-root")
                        ? normalizedPath(
                                values.get("data-root"),
                                "data-root")
                        : defaults.dataRoot();

        Path catalogPath =
                values.containsKey("catalog-path")
                        ? normalizedPath(
                                values.get("catalog-path"),
                                "catalog-path")
                        : dataRoot.resolve(
                                        "catalog.json")
                                .normalize();

        if (values.containsKey("data-root")
                && values.containsKey("catalog-path")) {
            Path expected =
                    dataRoot.resolve(
                                    "catalog.json")
                            .normalize();

            if (!catalogPath.equals(expected)) {
                throw new IllegalArgumentException(
                        "--catalog-path must equal <data-root>/catalog.json when --data-root is supplied");
            }
        }

        return new ServerConfig(
                values.getOrDefault(
                        "host",
                        defaults.host()),
                parseInt(
                        values,
                        "port",
                        defaults.port()),
                catalogMode,
                catalogPath,
                parseInt(
                        values,
                        "max-header-bytes",
                        defaults.maxHeaderBytes()),
                parseInt(
                        values,
                        "max-connections",
                        defaults.maxConnections()),
                parseInt(
                        values,
                        "worker-threads",
                        defaults.workerThreads()),
                parseInt(
                        values,
                        "worker-queue-capacity",
                        defaults.workerQueueCapacity()),
                parseInt(
                        values,
                        "io-threads",
                        defaults.ioThreads()),
                Duration.ofMillis(
                        parseInt(
                                values,
                                "header-timeout-ms",
                                (int) defaults.headerTimeout()
                                        .toMillis())),
                defaults.maxResourceBytes(),
                defaults.maxCatalogBytes(),
                parseBoolean(
                        values,
                        "ws-allow-no-origin",
                        defaults.webSocketAllowNoOrigin()),
                parseInt(
                        values,
                        "max-ws-connections",
                        defaults.maxWebSocketConnections()),
                parseInt(
                        values,
                        "max-sessions",
                        defaults.maxSessions()),
                parseInt(
                        values,
                        "disk-threads",
                        defaults.diskThreads()),
                parseInt(
                        values,
                        "disk-queue-capacity",
                        defaults.diskQueueCapacity()),
                parseInt(
                        values,
                        "metadata-threads",
                        defaults.metadataThreads()),
                parseInt(
                        values,
                        "metadata-queue-capacity",
                        defaults.metadataQueueCapacity()),
                Duration.ofMillis(
                        parseInt(
                                values,
                                "ws-close-timeout-ms",
                                (int) defaults.webSocketCloseTimeout()
                                        .toMillis())),
                parseLong(
                        values,
                        "tile-cache-bytes",
                        defaults.tileCacheBytes()),
                parseLong(
                        values,
                        "transient-tile-bytes",
                        defaults.transientTileBytes()),
                parseInt(
                        values,
                        "max-tile-reads",
                        defaults.maxTileReads()),
                parseInt(
                        values,
                        "drr-quantum-bytes",
                        defaults.drrQuantumBytes()),
                Duration.ofMillis(
                        parseInt(
                                values,
                                "hello-timeout-ms",
                                (int) defaults.helloTimeout()
                                        .toMillis())),
                Duration.ofMillis(
                        parseInt(
                                values,
                                "ping-interval-ms",
                                (int) defaults.pingInterval()
                                        .toMillis())),
                Duration.ofMillis(
                        parseInt(
                                values,
                                "pong-timeout-ms",
                                (int) defaults.pongTimeout()
                                        .toMillis())),
                Duration.ofMillis(
                        parseInt(
                                values,
                                "release-timeout-ms",
                                (int) defaults.releaseTimeout()
                                        .toMillis())),
                Duration.ofMillis(
                        parseInt(
                                values,
                                "write-progress-timeout-ms",
                                (int) defaults.writeProgressTimeout()
                                        .toMillis())));
    }

    private static void requirePositive(
            Duration value,
            String name) {
        if (value == null
                || value.isZero()
                || value.isNegative()) {
            throw new IllegalArgumentException(
                    name + " must be positive");
        }
    }

    private static int availableProcessors() {
        return Math.max(
                2,
                Runtime.getRuntime()
                        .availableProcessors());
    }

    private static int defaultDiskThreads() {
        return Math.min(
                4,
                availableProcessors());
    }

    private static int defaultMetadataThreads() {
        return Math.min(
                2,
                availableProcessors());
    }

    private static Path normalizedPath(
            String value,
            String name) {
        try {
            return Path.of(value)
                    .toAbsolutePath()
                    .normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(
                    "--" + name
                            + " is not a valid path",
                    e);
        }
    }

    private static int parseInt(
            Map<String, String> values,
            String name,
            int fallback) {
        String value = values.get(name);

        if (value == null) return fallback;

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "--" + name
                            + " must be an integer",
                    e);
        }
    }

    private static long parseLong(
            Map<String, String> values,
            String name,
            long fallback) {
        String value = values.get(name);

        if (value == null) return fallback;

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "--" + name
                            + " must be a long integer",
                    e);
        }
    }

    private static boolean parseBoolean(
            Map<String, String> values,
            String name,
            boolean fallback) {
        String value = values.get(name);

        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }

        throw new IllegalArgumentException(
                "--" + name
                        + " must be true or false");
    }
}
