package gt.lupa.ingest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs a process directly, never through a shell, while bounding captured output. */
public final class ProcessRunner {
    private static final Duration STREAM_DRAIN_TIMEOUT = Duration.ofSeconds(5);
    private final int maxOutputBytes;

    public ProcessRunner(int maxOutputBytes) {
        if (maxOutputBytes < 1024) {
            throw new IllegalArgumentException("maxOutputBytes must be at least 1024");
        }
        this.maxOutputBytes = maxOutputBytes;
    }

    public ProcessResult run(List<String> command, Duration timeout, Path workingDirectory)
            throws IngestException {
        return run(command, timeout, workingDirectory, Map.of());
    }

    public ProcessResult run(
            List<String> command,
            Duration timeout,
            Path workingDirectory,
            Map<String, String> environment) throws IngestException {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(environment, "environment");
        if (command.isEmpty() || command.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("command must contain non-null arguments");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        ProcessBuilder builder = new ProcessBuilder(List.copyOf(command));
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toAbsolutePath().normalize().toFile());
        }
        builder.environment().putAll(environment);
        builder.redirectErrorStream(false);

        final Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IngestException(3, "cannot start process: " + command.getFirst(), e);
        }

        Instant started = Instant.now();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CapturedOutput> stdoutFuture = executor.submit(() -> capture(process.getInputStream()));
            Future<CapturedOutput> stderrFuture = executor.submit(() -> capture(process.getErrorStream()));

            boolean finished;
            try {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                terminateOwnedProcess(process);
                Thread.currentThread().interrupt();
                throw new IngestException(130, "process execution interrupted", e);
            }

            boolean timedOut = !finished;
            if (timedOut) {
                terminateOwnedProcess(process);
            }

            CapturedOutput stdout = awaitCapture(stdoutFuture);
            CapturedOutput stderr = awaitCapture(stderrFuture);
            int exit = timedOut ? -1 : process.exitValue();
            return new ProcessResult(
                    exit,
                    stdout.text(),
                    stderr.text(),
                    stdout.truncated(),
                    stderr.truncated(),
                    timedOut,
                    Duration.between(started, Instant.now()));
        }
    }

    private CapturedOutput awaitCapture(Future<CapturedOutput> future) throws IngestException {
        try {
            return future.get(STREAM_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException(130, "interrupted while draining process output", e);
        } catch (ExecutionException e) {
            throw new IngestException(3, "cannot read process output", e.getCause());
        } catch (TimeoutException e) {
            throw new IngestException(3, "timed out while draining process output", e);
        }
    }

    private CapturedOutput capture(InputStream input) throws IOException {
        ByteArrayOutputStream kept = new ByteArrayOutputStream(Math.min(maxOutputBytes, 8192));
        byte[] buffer = new byte[8192];
        int stored = 0;
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            int remaining = maxOutputBytes - stored;
            if (remaining > 0) {
                int toKeep = Math.min(remaining, read);
                kept.write(buffer, 0, toKeep);
                stored += toKeep;
                if (toKeep < read) {
                    truncated = true;
                }
            } else {
                truncated = true;
            }
        }
        return new CapturedOutput(kept.toString(StandardCharsets.UTF_8), truncated);
    }

    private static void terminateOwnedProcess(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>();
        process.toHandle().descendants().forEach(descendants::add);
        for (ProcessHandle child : descendants.reversed()) {
            child.destroy();
        }
        process.destroy();
        try {
            if (!process.waitFor(750, TimeUnit.MILLISECONDS)) {
                for (ProcessHandle child : descendants.reversed()) {
                    if (child.isAlive()) {
                        child.destroyForcibly();
                    }
                }
                process.destroyForcibly();
                process.waitFor(750, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            for (ProcessHandle child : descendants.reversed()) {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
        }
    }

    private record CapturedOutput(String text, boolean truncated) {
    }
}
