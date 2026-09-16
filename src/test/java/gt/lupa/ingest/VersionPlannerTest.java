package gt.lupa.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionPlannerTest {
    @TempDir
    Path tempDir;

    @Test
    void returnsV1WhenImageHasNoPublishedVersions() throws Exception {
        StorageLayout layout = new StorageLayout(tempDir.resolve("data"));
        layout.initialize();

        assertEquals("v1", VersionPlanner.nextVersion(layout, "sample-photo"));
    }

    @Test
    void returnsNextNumericVersion() throws Exception {
        StorageLayout layout = new StorageLayout(tempDir.resolve("data"));
        layout.initialize();

        Path imageRoot = layout.pyramids().resolve("sample-photo");
        Files.createDirectories(imageRoot.resolve("v1"));
        Files.createDirectories(imageRoot.resolve("v2"));
        Files.createDirectories(imageRoot.resolve("v4"));

        assertEquals("v5", VersionPlanner.nextVersion(layout, "sample-photo"));
    }
}