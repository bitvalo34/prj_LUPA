package gt.lupa;

import gt.lupa.config.ServerConfig;
import gt.lupa.http.HttpRouter;
import gt.lupa.http.NioHttpServer;
import gt.lupa.session.LupaSession;
import gt.lupa.storage.CatalogSource;
import gt.lupa.storage.ClasspathCatalogSource;
import gt.lupa.storage.FileCatalogSource;
import gt.lupa.storage.PublishedImageStore;

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
        PublishedImageStore imageStore = new PublishedImageStore(config.dataRoot(), config.maxCatalogBytes());
        NioHttpServer server = new NioHttpServer(
                config,
                router,
                workerExecutor -> new LupaSession(imageStore, workerExecutor));

        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "lupa-shutdown"));
        System.out.printf(
                "LUPA E20 listening on http://%s:%d/ (catalog=%s, dataRoot=%s, wsNoOrigin=%s)%n",
                config.host(),
                server.port(),
                config.catalogMode(),
                config.dataRoot(),
                config.webSocketAllowNoOrigin());
        new CountDownLatch(1).await();
    }
}
