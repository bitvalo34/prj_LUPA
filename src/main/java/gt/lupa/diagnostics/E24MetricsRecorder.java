package gt.lupa.diagnostics;

import gt.lupa.concurrent.StorageExecutors;
import gt.lupa.concurrent.TileReadAdmission;
import gt.lupa.concurrent.TransientBufferBudget;
import gt.lupa.session.SessionAdmission;
import gt.lupa.storage.CompressedTileCache;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in E24 sampler. Disabled unless lupa.e24.metricsFile is configured. */
public final class E24MetricsRecorder implements AutoCloseable {
    public static final String FILE_PROPERTY = "lupa.e24.metricsFile";
    public static final String INTERVAL_PROPERTY = "lupa.e24.metricsIntervalMs";
    private static final long DEFAULT_INTERVAL_MS = 200L;
    private static final long MIN_INTERVAL_MS = 50L;

    private final BufferedWriter out;
    private final ScheduledExecutorService sampler;
    private final SessionAdmission sessions;
    private final StorageExecutors storage;
    private final TileReadAdmission tileReads;
    private final CompressedTileCache cache;
    private final TransientBufferBudget transientBuffers;
    private final E22Metrics e22;
    private final Runtime runtime = Runtime.getRuntime();
    private final long processStartNanos = System.nanoTime();
    private final AtomicBoolean closed = new AtomicBoolean();
    private long samples;

    private E24MetricsRecorder(Path path, long intervalMs,
            SessionAdmission sessions, StorageExecutors storage,
            TileReadAdmission tileReads, CompressedTileCache cache,
            TransientBufferBudget transientBuffers, E22Metrics e22) throws IOException {
        this.sessions = Objects.requireNonNull(sessions);
        this.storage = Objects.requireNonNull(storage);
        this.tileReads = Objects.requireNonNull(tileReads);
        this.cache = Objects.requireNonNull(cache);
        this.transientBuffers = Objects.requireNonNull(transientBuffers);
        this.e22 = Objects.requireNonNull(e22);

        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        out = Files.newBufferedWriter(absolute, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        writeHeader();
        sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lupa-e24-metrics");
            thread.setDaemon(true);
            return thread;
        });
        sampleSafely();
        sampler.scheduleAtFixedRate(this::sampleSafely, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    public static E24MetricsRecorder startIfConfigured(
            SessionAdmission sessions, StorageExecutors storage,
            TileReadAdmission tileReads, CompressedTileCache cache,
            TransientBufferBudget transientBuffers, E22Metrics e22) throws IOException {
        String rawPath = System.getProperty(FILE_PROPERTY, "").trim();
        if (rawPath.isEmpty()) return null;
        long intervalMs = Long.getLong(INTERVAL_PROPERTY, DEFAULT_INTERVAL_MS);
        if (intervalMs < MIN_INTERVAL_MS) {
            throw new IllegalArgumentException(INTERVAL_PROPERTY + " must be >= " + MIN_INTERVAL_MS);
        }
        return new E24MetricsRecorder(Path.of(rawPath), intervalMs, sessions, storage,
                tileReads, cache, transientBuffers, e22);
    }

    private synchronized void writeHeader() throws IOException {
        out.write(String.join(",",
                "wallTime","processMonotonicNanos","sessionsActive","sessionsAvailable",
                "diskActive","diskQueued","metadataActive","metadataQueued",
                "tileReadsInFlight","tileReadWaiting","tileReadHighWatermark","tileReadRejected",
                "tileReadChargedBytes","drrVisits","cacheResidentBytes","cacheRetainedEvictedBytes",
                "cacheEntries","cacheActiveLeases","cacheHits","cacheMisses","cacheEvictions",
                "cacheDuplicateLoads","cacheOversizedBypasses","transientReservedBytes",
                "transientHighWatermarkBytes","transientActiveLeases","transientRejections",
                "heapUsedBytes","heapCommittedBytes","heapMaxBytes","headerTimeouts","helloTimeouts",
                "pongTimeouts","releaseTimeouts","writeProgressTimeouts","connectionReleases",
                "webSocketReleases","sessionReleases","sessionCleanupRuns","lateCallbacksDiscarded"));
        out.newLine();
        out.flush();
    }

    private void sampleSafely() {
        if (closed.get()) return;
        try {
            sample();
        } catch (IOException | RuntimeException failure) {
            System.err.println("E24 metrics sample failed: " + failure);
        }
    }

    private synchronized void sample() throws IOException {
        if (closed.get()) return;
        SessionAdmission.Snapshot session = sessions.snapshot();
        StorageExecutors.Snapshot executor = storage.snapshot();
        TileReadAdmission.Snapshot reads = tileReads.snapshot();
        CompressedTileCache.Snapshot cacheSnapshot = cache.snapshot();
        TransientBufferBudget.Snapshot transientSnapshot = transientBuffers.snapshot();
        E22Metrics.Snapshot timeoutSnapshot = e22.snapshot();
        long heapCommitted = runtime.totalMemory();
        long heapUsed = heapCommitted - runtime.freeMemory();

        String[] values = {
                Instant.now().toString(), Long.toString(System.nanoTime() - processStartNanos),
                Integer.toString(session.active()), Integer.toString(session.available()),
                Integer.toString(executor.diskActive()), Integer.toString(executor.diskQueued()),
                Integer.toString(executor.metadataActive()), Integer.toString(executor.metadataQueued()),
                Integer.toString(reads.inFlight()), Integer.toString(reads.waiting()),
                Integer.toString(reads.highWatermark()), Long.toString(reads.rejected()),
                Long.toString(reads.chargedBytes()), Long.toString(reads.drrVisits()),
                Long.toString(cacheSnapshot.residentBytes()), Long.toString(cacheSnapshot.retainedEvictedBytes()),
                Integer.toString(cacheSnapshot.entries()), Long.toString(cacheSnapshot.activeLeases()),
                Long.toString(cacheSnapshot.hits()), Long.toString(cacheSnapshot.misses()),
                Long.toString(cacheSnapshot.evictions()), Long.toString(cacheSnapshot.duplicateLoads()),
                Long.toString(cacheSnapshot.oversizedBypasses()), Long.toString(transientSnapshot.reservedBytes()),
                Long.toString(transientSnapshot.highWatermarkBytes()), Long.toString(transientSnapshot.activeLeases()),
                Long.toString(transientSnapshot.rejections()), Long.toString(heapUsed),
                Long.toString(heapCommitted), Long.toString(runtime.maxMemory()),
                Long.toString(timeoutSnapshot.headerTimeouts()), Long.toString(timeoutSnapshot.helloTimeouts()),
                Long.toString(timeoutSnapshot.pongTimeouts()), Long.toString(timeoutSnapshot.releaseTimeouts()),
                Long.toString(timeoutSnapshot.writeProgressTimeouts()), Long.toString(timeoutSnapshot.connectionReleases()),
                Long.toString(timeoutSnapshot.webSocketReleases()), Long.toString(timeoutSnapshot.sessionReleases()),
                Long.toString(timeoutSnapshot.sessionCleanupRuns()), Long.toString(timeoutSnapshot.lateCallbacksDiscarded())
        };
        out.write(String.join(",", values));
        out.newLine();
        samples++;
        if ((samples % 10) == 0) out.flush();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        sampler.shutdownNow();
        try {
            sampler.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            try { out.flush(); } catch (IOException failure) {
                System.err.println("E24 metrics flush failed: " + failure);
            }
            try { out.close(); } catch (IOException failure) {
                System.err.println("E24 metrics close failed: " + failure);
            }
        }
    }
}
