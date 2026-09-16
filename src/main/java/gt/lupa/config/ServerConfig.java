package gt.lupa.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public record ServerConfig(String host, int port, String catalogMode, Path catalogPath, int maxHeaderBytes, int maxConnections, int workerThreads, int workerQueueCapacity, int ioThreads, Duration headerTimeout, int maxResourceBytes, int maxCatalogBytes) {
    public ServerConfig {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host is required");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("port must be 0..65535");
        if (!"fixture".equals(catalogMode) && !"file".equals(catalogMode)) throw new IllegalArgumentException("catalogMode must be fixture or file");
        if (catalogPath == null) throw new IllegalArgumentException("catalogPath is required");
        if (maxHeaderBytes < 1024) throw new IllegalArgumentException("maxHeaderBytes too small");
        if (maxConnections < 1 || workerThreads < 1 || workerQueueCapacity < 1 || ioThreads < 1) throw new IllegalArgumentException("thread/queue/connection limits must be positive");
        if (headerTimeout == null || headerTimeout.isZero() || headerTimeout.isNegative()) throw new IllegalArgumentException("headerTimeout must be positive");
        if (maxResourceBytes < 1 || maxCatalogBytes < 1) throw new IllegalArgumentException("byte limits must be positive");
    }

    public static ServerConfig defaults() {
        int cpus = Math.max(2, Runtime.getRuntime().availableProcessors());
        return new ServerConfig("127.0.0.1", 8080, "fixture", Path.of("data", "catalog.json"), 16 * 1024, 128, Math.min(8, cpus), 256, Math.min(4, cpus), Duration.ofSeconds(5), 1024 * 1024, 256 * 1024);
    }

    public static ServerConfig fromArgs(String[] args) {
        ServerConfig d = defaults();
        Map<String, String> values = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) throw new IllegalArgumentException("arguments must use --name=value");
            int eq = arg.indexOf('='); values.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
        for (String key : values.keySet()) if (!key.equals("host") && !key.equals("port") && !key.equals("catalog") && !key.equals("catalog-path") && !key.equals("max-header-bytes") && !key.equals("max-connections") && !key.equals("header-timeout-ms")) throw new IllegalArgumentException("unknown argument: --" + key);
        return new ServerConfig(values.getOrDefault("host", d.host()), parseInt(values,"port",d.port()), values.getOrDefault("catalog",d.catalogMode()), Path.of(values.getOrDefault("catalog-path",d.catalogPath().toString())), parseInt(values,"max-header-bytes",d.maxHeaderBytes()), parseInt(values,"max-connections",d.maxConnections()), d.workerThreads(), d.workerQueueCapacity(), d.ioThreads(), Duration.ofMillis(parseInt(values,"header-timeout-ms",(int)d.headerTimeout().toMillis())), d.maxResourceBytes(), d.maxCatalogBytes());
    }

    private static int parseInt(Map<String,String> values,String name,int fallback) {
        String value=values.get(name); if(value==null)return fallback;
        try{return Integer.parseInt(value);}catch(NumberFormatException e){throw new IllegalArgumentException("--"+name+" must be an integer",e);}
    }
}
