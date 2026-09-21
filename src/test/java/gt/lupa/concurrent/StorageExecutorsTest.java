package gt.lupa.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StorageExecutorsTest {
    @Test
    void diskQueueRejectsInsteadOfRunningStorageInCaller() throws Exception {
        try (StorageExecutors executors = new StorageExecutors(1, 1, 1, 1)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            executors.diskExecutor().execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertTrue(started.await(1, TimeUnit.SECONDS), "first disk task did not start");
            executors.diskExecutor().execute(() -> {});

            StorageExecutors.Snapshot snapshot = executors.snapshot();
            assertEquals(1, snapshot.diskActive());
            assertEquals(1, snapshot.diskQueued());

            assertThrows(
                    RejectedExecutionException.class,
                    () -> executors.diskExecutor().execute(() -> {}));

            release.countDown();
        }
    }
}
