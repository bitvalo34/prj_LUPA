package gt.lupa.ingest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Centralizes conservative libvips process limits used by A19. */
final class VipsRuntime {
    static final int CONCURRENCY = 2;
    static final int CACHE_MAX_OPERATIONS = 100;
    static final long CACHE_MAX_MEMORY_BYTES = 256L * 1024L * 1024L;
    static final int CACHE_MAX_FILES = 100;

    private VipsRuntime() {
    }

    static List<String> command(IngestCliConfig config, String... operationAndArgs) {
        ArrayList<String> command = new ArrayList<>(operationAndArgs.length + 5);
        command.add(config.vipsExecutable());
        command.add("--vips-concurrency=" + CONCURRENCY);
        command.add("--vips-cache-max=" + CACHE_MAX_OPERATIONS);
        command.add("--vips-cache-max-memory=" + CACHE_MAX_MEMORY_BYTES);
        command.add("--vips-cache-max-files=" + CACHE_MAX_FILES);
        command.addAll(Arrays.asList(operationAndArgs));
        return List.copyOf(command);
    }
}
