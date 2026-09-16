package gt.lupa.ingest;

import java.time.Duration;

public record ProcessResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean stdoutTruncated,
        boolean stderrTruncated,
        boolean timedOut,
        Duration duration) {
}
