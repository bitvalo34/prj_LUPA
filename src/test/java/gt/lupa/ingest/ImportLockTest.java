package gt.lupa.ingest;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportLockTest {
    @TempDir Path temp;

    @Test
    void rejectsSecondImporterInSameProcess() throws Exception {
        StorageLayout layout = new StorageLayout(temp.resolve("data"));
        try (ImportLock ignored = ImportLock.acquire(layout)) {
            assertThrows(IngestException.class, () -> ImportLock.acquire(layout));
        }
    }
}
