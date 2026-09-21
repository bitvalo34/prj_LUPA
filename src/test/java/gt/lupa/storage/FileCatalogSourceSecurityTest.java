package gt.lupa.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCatalogSourceSecurityTest {
    @TempDir Path temp;

    @Test
    void manifestSymlinkCannotRedirectOutsidePublishedTree()
            throws Exception {
        Path dataRoot = temp.resolve("data");
        Path version =
                dataRoot.resolve(
                        "pyramids/photo/v1");

        Files.createDirectories(version);

        new CatalogPublisher().publish(
                dataRoot.resolve("catalog.json"),
                new CatalogImage(
                        "photo",
                        "v1",
                        256,
                        256,
                        256,
                        0));

        Path outside =
                temp.resolve("outside-manifest.json");

        ImageManifest manifest =
                new ImageManifest(
                        1,
                        "photo",
                        "v1",
                        256,
                        256,
                        256,
                        0,
                        "onetile",
                        List.of(
                                new ImageLevel(
                                        0,
                                        256,
                                        256)));

        Files.write(
                outside,
                new CatalogJson()
                        .writeManifest(manifest));

        try {
            Files.createSymbolicLink(
                    version.resolve("manifest.json"),
                    outside);
        } catch (UnsupportedOperationException
                 | IOException
                 | SecurityException e) {
            assumeTrue(
                    false,
                    "symbolic links unavailable: "
                            + e.getMessage());
        }

        FileCatalogSource source =
                new FileCatalogSource(
                        dataRoot.resolve(
                                "catalog.json"));

        assertThrows(
                CatalogException.class,
                () -> source.readManifest(
                        "photo",
                        "v1"));
    }

    @Test
    void catalogSymlinkIsRejected()
            throws Exception {
        Path dataRoot = temp.resolve("data");
        Files.createDirectories(dataRoot);

        Path outside =
                temp.resolve("outside-catalog.json");

        Files.write(
                outside,
                new CatalogJson()
                        .writeCatalog(
                                new CatalogSnapshot(
                                        1,
                                        List.of())));

        try {
            Files.createSymbolicLink(
                    dataRoot.resolve("catalog.json"),
                    outside);
        } catch (UnsupportedOperationException
                 | IOException
                 | SecurityException e) {
            assumeTrue(
                    false,
                    "symbolic links unavailable: "
                            + e.getMessage());
        }

        FileCatalogSource source =
                new FileCatalogSource(
                        dataRoot.resolve(
                                "catalog.json"));

        assertThrows(
                CatalogException.class,
                source::readCatalog);
    }
}
