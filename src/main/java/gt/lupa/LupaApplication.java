package gt.lupa;

import gt.lupa.concurrent.MonotonicScheduler;
import gt.lupa.concurrent.ShutdownSupport;
import gt.lupa.concurrent.StorageExecutors;
import gt.lupa.concurrent.TileReadAdmission;
import gt.lupa.concurrent.TransientBufferBudget;
import gt.lupa.config.ServerConfig;
import gt.lupa.diagnostics.E22Metrics;
import gt.lupa.http.HttpRouter;
import gt.lupa.http.NioHttpServer;
import gt.lupa.protocol.LupaProtocol;
import gt.lupa.session.LupaSession;
import gt.lupa.session.SessionAdmission;
import gt.lupa.storage.CachingTileReader;
import gt.lupa.storage.CatalogSource;
import gt.lupa.storage.ClasspathCatalogSource;
import gt.lupa.storage.CompressedTileCache;
import gt.lupa.storage.FileCatalogSource;
import gt.lupa.storage.PublishedImageStore;
import gt.lupa.storage.PublishedTileReader;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public final class LupaApplication {
    private LupaApplication() {}

    public static void main(String[] args) throws Exception {
        ServerConfig config =
                ServerConfig.fromArgs(args);

        CatalogSource catalogSource =
                switch (config.catalogMode()) {
                    case "fixture" ->
                            ClasspathCatalogSource
                                    .defaultFixture();
                    case "file" ->
                            new FileCatalogSource(
                                    config.catalogPath(),
                                    config.maxCatalogBytes());
                    default ->
                            throw new IllegalArgumentException(
                                    "catalog mode must be fixture or file");
                };

        HttpRouter router =
                new HttpRouter(
                        catalogSource,
                        config.maxResourceBytes());

        PublishedImageStore imageStore =
                new PublishedImageStore(
                        config.dataRoot(),
                        config.maxCatalogBytes());

        CompressedTileCache tileCache =
                new CompressedTileCache(
                        config.tileCacheBytes());

        CachingTileReader tileReader =
                new CachingTileReader(
                        new PublishedTileReader(
                                imageStore,
                                LupaProtocol.MAX_TILE_BYTES),
                        tileCache);

        SessionAdmission sessionAdmission =
                new SessionAdmission(
                        config.maxSessions());

        TileReadAdmission tileReadAdmission =
                new TileReadAdmission(
                        config.maxTileReads(),
                        config.maxSessions(),
                        config.drrQuantumBytes());

        TransientBufferBudget transientBuffers =
                new TransientBufferBudget(
                        config.transientTileBytes());

        StorageExecutors storageExecutors =
                new StorageExecutors(config);

        E22Metrics metrics =
                new E22Metrics();

        ScheduledExecutorService sessionTimers =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread =
                                    new Thread(
                                            runnable,
                                            "lupa-session-timer");
                            thread.setDaemon(true);
                            return thread;
                        });

        MonotonicScheduler sessionScheduler =
                MonotonicScheduler.system(
                        sessionTimers);

        NioHttpServer server =
                new NioHttpServer(
                        config,
                        router,
                        stateExecutor ->
                                new LupaSession(
                                        imageStore,
                                        tileReader,
                                        stateExecutor,
                                        storageExecutors.diskExecutor(),
                                        storageExecutors.metadataExecutor(),
                                        sessionAdmission,
                                        tileReadAdmission,
                                        transientBuffers,
                                        sessionScheduler,
                                        config.helloTimeout(),
                                        config.releaseTimeout(),
                                        metrics),
                        metrics);

        try {
            server.start();
        } catch (Exception e) {
            ShutdownSupport.shutdownNowAndAwait(
                    sessionTimers,
                    java.time.Duration.ofSeconds(2));
            storageExecutors.close();
            throw e;
        }

        Runtime.getRuntime().addShutdownHook(
                new Thread(
                        () -> {
                            server.close();
                            ShutdownSupport.shutdownNowAndAwait(
                                    sessionTimers,
                                    java.time.Duration.ofSeconds(2));
                            storageExecutors.close();
                        },
                        "lupa-shutdown"));

        System.out.printf(
                "LUPA E22 base listening on http://%s:%d/ "
                        + "(catalog=%s, dataRoot=%s, "
                        + "connections=%d, websockets=%d, sessions=%d, "
                        + "disk=%dx%d, metadata=%dx%d, tileReads=%d, "
                        + "drrQuantumBytes=%d, cacheBytes=%d, transientBytes=%d, "
                        + "helloMs=%d, pingMs=%d, pongMs=%d, "
                        + "releaseMs=%d, writeProgressMs=%d)%n",
                config.host(),
                server.port(),
                config.catalogMode(),
                config.dataRoot(),
                config.maxConnections(),
                config.maxWebSocketConnections(),
                config.maxSessions(),
                config.diskThreads(),
                config.diskQueueCapacity(),
                config.metadataThreads(),
                config.metadataQueueCapacity(),
                config.maxTileReads(),
                config.drrQuantumBytes(),
                config.tileCacheBytes(),
                config.transientTileBytes(),
                config.helloTimeout().toMillis(),
                config.pingInterval().toMillis(),
                config.pongTimeout().toMillis(),
                config.releaseTimeout().toMillis(),
                config.writeProgressTimeout().toMillis());

        try {
            new CountDownLatch(1).await();
        } finally {
            server.close();
            ShutdownSupport.shutdownNowAndAwait(
                    sessionTimers,
                    java.time.Duration.ofSeconds(2));
            storageExecutors.close();
        }
    }
}
