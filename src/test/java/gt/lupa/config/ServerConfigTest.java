package gt.lupa.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServerConfigTest {
    @Test
    void normalExecutionUsesPublishedFileCatalog() {
        ServerConfig config = ServerConfig.defaults();
        assertEquals("file", config.catalogMode());
        assertTrue(config.catalogPath().isAbsolute());
        assertEquals(
                config.dataRoot().resolve("catalog.json"),
                config.catalogPath());
    }

    @Test
    void e22DefaultsDistinguishConnectionsWebSocketsAndSessions() {
        ServerConfig config = ServerConfig.defaults();

        assertEquals(128, config.maxConnections());
        assertEquals(64, config.maxWebSocketConnections());
        assertEquals(32, config.maxSessions());
        assertTrue(
                config.maxSessions()
                        <= config.maxWebSocketConnections());
        assertTrue(
                config.maxWebSocketConnections()
                        <= config.maxConnections());

        assertEquals(64, config.diskQueueCapacity());
        assertEquals(32, config.metadataQueueCapacity());
        assertTrue(config.diskThreads() > 0);
        assertTrue(config.metadataThreads() > 0);

        assertEquals(
                128L * 1024L * 1024L,
                config.tileCacheBytes());
        assertEquals(
                16L * 1024L * 1024L,
                config.transientTileBytes());
        assertEquals(8, config.maxTileReads());
        assertEquals(128 * 1024, config.drrQuantumBytes());

        assertEquals(5000, config.helloTimeout().toMillis());
        assertEquals(15000, config.pingInterval().toMillis());
        assertEquals(10000, config.pongTimeout().toMillis());
        assertEquals(30000, config.releaseTimeout().toMillis());
        assertEquals(
                10000,
                config.writeProgressTimeout().toMillis());
    }

    @Test
    void e22ResourceLimitsAreConfigurable() {
        ServerConfig config =
                ServerConfig.fromArgs(
                        new String[]{
                                "--max-connections=20",
                                "--max-ws-connections=12",
                                "--max-sessions=7",
                                "--worker-threads=3",
                                "--worker-queue-capacity=17",
                                "--io-threads=2",
                                "--disk-threads=2",
                                "--disk-queue-capacity=9",
                                "--metadata-threads=1",
                                "--metadata-queue-capacity=5",
                                "--ws-close-timeout-ms=750",
                                "--tile-cache-bytes=1048576",
                                "--transient-tile-bytes=4000000",
                                "--max-tile-reads=4",
                                "--drr-quantum-bytes=65536",
                                "--hello-timeout-ms=800",
                                "--ping-interval-ms=900",
                                "--pong-timeout-ms=300",
                                "--release-timeout-ms=1200",
                                "--write-progress-timeout-ms=700"
                        });

        assertEquals(20, config.maxConnections());
        assertEquals(12, config.maxWebSocketConnections());
        assertEquals(7, config.maxSessions());
        assertEquals(3, config.workerThreads());
        assertEquals(17, config.workerQueueCapacity());
        assertEquals(2, config.ioThreads());
        assertEquals(2, config.diskThreads());
        assertEquals(9, config.diskQueueCapacity());
        assertEquals(1, config.metadataThreads());
        assertEquals(5, config.metadataQueueCapacity());
        assertEquals(
                750,
                config.webSocketCloseTimeout().toMillis());
        assertEquals(1048576L, config.tileCacheBytes());
        assertEquals(4000000L, config.transientTileBytes());
        assertEquals(4, config.maxTileReads());
        assertEquals(65536, config.drrQuantumBytes());

        assertEquals(800, config.helloTimeout().toMillis());
        assertEquals(900, config.pingInterval().toMillis());
        assertEquals(300, config.pongTimeout().toMillis());
        assertEquals(1200, config.releaseTimeout().toMillis());
        assertEquals(
                700,
                config.writeProgressTimeout().toMillis());
    }

    @Test
    void e23ReducedLimitsRemainCoherentForControlledSaturationTests() {
        ServerConfig config =
                ServerConfig.fromArgs(
                        new String[]{
                                "--max-connections=4",
                                "--max-ws-connections=2",
                                "--max-sessions=2",
                                "--worker-threads=1",
                                "--worker-queue-capacity=2",
                                "--io-threads=1",
                                "--disk-threads=1",
                                "--disk-queue-capacity=1",
                                "--metadata-threads=1",
                                "--metadata-queue-capacity=1",
                                "--max-tile-reads=1",
                                "--drr-quantum-bytes=32768",
                                "--tile-cache-bytes=262144",
                                "--transient-tile-bytes=600000",
                                "--header-timeout-ms=150",
                                "--hello-timeout-ms=150",
                                "--ping-interval-ms=150",
                                "--pong-timeout-ms=100",
                                "--release-timeout-ms=200",
                                "--write-progress-timeout-ms=150",
                                "--ws-close-timeout-ms=100"
                        });

        assertEquals(4, config.maxConnections());
        assertEquals(2, config.maxWebSocketConnections());
        assertEquals(2, config.maxSessions());
        assertEquals(1, config.diskThreads());
        assertEquals(1, config.diskQueueCapacity());
        assertEquals(1, config.maxTileReads());
        assertEquals(32768, config.drrQuantumBytes());
        assertEquals(262144L, config.tileCacheBytes());
        assertEquals(600000L, config.transientTileBytes());
        assertEquals(150, config.headerTimeout().toMillis());
        assertEquals(200, config.releaseTimeout().toMillis());
    }

    @Test
    void incoherentAdmissionLimitsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.fromArgs(
                        new String[]{
                                "--max-connections=10",
                                "--max-ws-connections=11"
                        }));

        assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.fromArgs(
                        new String[]{
                                "--max-connections=10",
                                "--max-ws-connections=8",
                                "--max-sessions=9"
                        }));
    }

    @Test
    void incoherentMemoryAndReadLimitsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.fromArgs(
                        new String[]{
                                "--tile-cache-bytes=262143"
                        }));

        assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.fromArgs(
                        new String[]{
                                "--transient-tile-bytes=1024"
                        }));

        assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.fromArgs(
                        new String[]{
                                "--disk-threads=1",
                                "--disk-queue-capacity=1",
                                "--max-tile-reads=3"
                        }));
    }

    @Test
    void invalidDrrQuantumIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.fromArgs(
                        new String[]{
                                "--drr-quantum-bytes=0"
                        }));

        assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.fromArgs(
                        new String[]{
                                "--drr-quantum-bytes=262145"
                        }));
    }

    @Test
    void nonPositiveTimeoutsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.fromArgs(
                        new String[]{"--hello-timeout-ms=0"}));

        assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.fromArgs(
                        new String[]{"--pong-timeout-ms=-1"}));

        assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.fromArgs(
                        new String[]{
                                "--write-progress-timeout-ms=0"
                        }));
    }

    @Test
    void dataRootDerivesTheCatalogPathAndFixtureRemainsExplicit() {
        Path root =
                Path.of("target", "i19-config-data")
                        .toAbsolutePath()
                        .normalize();

        ServerConfig file =
                ServerConfig.fromArgs(
                        new String[]{
                                "--data-root=" + root
                        });

        assertEquals(root, file.dataRoot());
        assertEquals(
                root.resolve("catalog.json"),
                file.catalogPath());

        ServerConfig fixture =
                ServerConfig.fromArgs(
                        new String[]{
                                "--catalog=fixture",
                                "--data-root=" + root
                        });

        assertEquals("fixture", fixture.catalogMode());
    }

    @Test
    void conflictingDataRootAndCatalogPathAreRejected() {
        Path root =
                Path.of("target", "data-a")
                        .toAbsolutePath()
                        .normalize();

        Path other =
                Path.of("target", "data-b", "catalog.json")
                        .toAbsolutePath()
                        .normalize();

        assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.fromArgs(
                        new String[]{
                                "--data-root=" + root,
                                "--catalog-path=" + other
                        }));
    }
}
