package gt.lupa.ingest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageLayoutTest {
    @TempDir Path temp;

    @Test
    void initializesPrivateStorageAndCreatesOwnStagingJob() throws Exception {
        StorageLayout layout = new StorageLayout(temp.resolve("data"));
        layout.initialize();
        assertTrue(Files.isDirectory(layout.originals()));
        assertTrue(Files.isDirectory(layout.staging()));
        assertTrue(Files.isDirectory(layout.pyramids()));
        Path job = layout.createJobDirectory();
        assertTrue(job.startsWith(layout.staging()));
        assertTrue(Files.isDirectory(job));
    }
}
