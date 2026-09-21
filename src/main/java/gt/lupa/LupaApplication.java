package gt.lupa;

import gt.lupa.concurrent.StorageExecutors;
import gt.lupa.config.ServerConfig;
import gt.lupa.http.HttpRouter;
import gt.lupa.http.NioHttpServer;
import gt.lupa.protocol.LupaProtocol;
import gt.lupa.session.LupaSession;
import gt.lupa.session.SessionAdmission;
import gt.lupa.storage.CatalogSource;
import gt.lupa.storage.ClasspathCatalogSource;
import gt.lupa.storage.FileCatalogSource;
import gt.lupa.storage.PublishedImageStore;
import gt.lupa.storage.PublishedTileReader;

import java.util.concurrent.CountDownLatch;

public final class LupaApplication {
    private LupaApplication() {}

    public static void main(String[] args) throws Exception {
        ServerConfig config = ServerConfig.fromArgs(args);

        CatalogSource catalogSource = switch (config.catalogMode()) {
            case "fixture" -> ClasspathCatalogSource.defaultFixture();
            case "file" -> new FileCatalogSource(config.catalogPath(), config.maxCatalogBytes());
            default -> throw new IllegalArgumentException("catalog mode must be fixture or file");
        };

        HttpRouter router = new HttpRouter(catalogSource, config.maxResourceBytes());
        PublishedImageStore imageStore =
                new PublishedImageStore(config.dataRoot(), config.maxCatalogBytes());
        PublishedTileReader tileReader =
                new PublishedTileReader(imageStore, LupaProtocol.MAX_TILE_BYTES);
        SessionAdmission sessionAdmission = new SessionAdmission(config.maxSessions());
        StorageExecutors storageExecutors = new StorageExecutors(config);

        NioHttpServer server = new NioHttpServer(
                config,
                router,
                stateExecutor -> new LupaSession(
                        imageStore,
                        tileReader,
                        stateExecutor,
                        storageExecutors.diskExecutor(),
                        storageExecutors.metadataExecutor(),
                        sessionAdmission));

        try {
            server.start();
        } catch (Exception e) {
            storageExecutors.close();
            throw e;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> {
                    server.close();
                    storageExecutors.close();
                },
                "lupa-shutdown"));

        System.out.printf(
                "LUPA E22 base listening on http://%s:%d/ "
                        + "(catalog=%s, dataRoot=%s, connections=%d, websockets=%d, sessions=%d, "
                        + "disk=%dx%d, metadata=%dx%d)%n",
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
                config.metadataQueueCapacity());

        try {
            new CountDownLatch(1).await();
        } finally {
            server.close();
            storageExecutors.close();
        }
    }
}
