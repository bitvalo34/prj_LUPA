package gt.lupa.http;

import gt.lupa.config.ServerConfig;
import gt.lupa.diagnostics.E22Metrics;
import gt.lupa.storage.ClasspathCatalogSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NioHttpServerShutdownTest {
    @Test
    void closeTerminatesOwnedExecutorsAndIoGroup() throws Exception {
        ServerConfig config =
                new ServerConfig(
                        "127.0.0.1",
                        0,
                        "fixture",
                        Path.of("data/catalog.json"),
                        16 * 1024,
                        8,
                        2,
                        8,
                        2,
                        Duration.ofSeconds(1),
                        1024 * 1024,
                        256 * 1024,
                        true);

        E22Metrics metrics = new E22Metrics();

        NioHttpServer server =
                new NioHttpServer(
                        config,
                        new HttpRouter(
                                ClasspathCatalogSource.defaultFixture()),
                        ignored -> new gt.lupa.websocket.WebSocketEndpoint() {},
                        metrics);

        server.start();
        server.close();
        server.close();

        NioHttpServer.ShutdownSnapshot snapshot =
                server.shutdownSnapshotForTest();

        assertTrue(snapshot.workersTerminated());
        assertTrue(snapshot.timersTerminated());
        assertTrue(snapshot.ioGroupTerminated());
    }
}
