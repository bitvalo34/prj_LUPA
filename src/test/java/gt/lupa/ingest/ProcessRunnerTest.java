package gt.lupa.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessRunnerTest {
    @Test
    void runsExecutableWithoutShellAndCapturesOutput() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessResult result = new ProcessRunner(16 * 1024)
                .run(List.of(java, "-version"), Duration.ofSeconds(10), null);
        assertEquals(0, result.exitCode());
        assertFalse(result.timedOut());
        assertTrue((result.stdout() + result.stderr()).contains("version"));
    }
}
