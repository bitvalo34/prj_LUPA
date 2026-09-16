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
        assertEquals(config.dataRoot().resolve("catalog.json"), config.catalogPath());
    }

    @Test
    void dataRootDerivesTheCatalogPathAndFixtureRemainsExplicit() {
        Path root = Path.of("target", "i19-config-data").toAbsolutePath().normalize();
        ServerConfig file = ServerConfig.fromArgs(new String[]{"--data-root=" + root});
        assertEquals(root, file.dataRoot());
        assertEquals(root.resolve("catalog.json"), file.catalogPath());

        ServerConfig fixture = ServerConfig.fromArgs(new String[]{"--catalog=fixture", "--data-root=" + root});
        assertEquals("fixture", fixture.catalogMode());
    }

    @Test
    void conflictingDataRootAndCatalogPathAreRejected() {
        Path root = Path.of("target", "data-a").toAbsolutePath().normalize();
        Path other = Path.of("target", "data-b", "catalog.json").toAbsolutePath().normalize();
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerConfig.fromArgs(new String[]{
                        "--data-root=" + root,
                        "--catalog-path=" + other
                })
        );
    }
}
