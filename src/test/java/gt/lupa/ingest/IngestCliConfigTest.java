package gt.lupa.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IngestCliConfigTest {
    @Test
    void parsesRequiredAndDefaultOptions() throws Exception {
        IngestCliConfig config = IngestCliConfig.parse(new String[] {
                "--original=/tmp/photo.jpg",
                "--image-id=photo-01",
                "--display-name=Photo 01",
                "--license-ref=Own photograph"
        });
        assertEquals(Path.of("/tmp/photo.jpg"), config.original());
        assertEquals("photo-01", config.imageId());
        assertEquals(85, config.jpegQuality());
        assertEquals(600, config.processTimeout().toSeconds());
        assertEquals("vips", config.vipsExecutable());
    }

    @Test
    void rejectsTraversalLikeIdentifier() {
        assertThrows(IngestException.class, () -> IngestCliConfig.parse(new String[] {
                "--original=/tmp/photo.jpg",
                "--image-id=../escape",
                "--display-name=Photo",
                "--license-ref=Own"
        }));
    }

    @Test
    void rejectsUnknownOption() {
        assertThrows(IngestException.class, () -> IngestCliConfig.parse(new String[] {
                "--original=/tmp/photo.jpg",
                "--image-id=photo",
                "--display-name=Photo",
                "--license-ref=Own",
                "--surprise=yes"
        }));
    }
}
