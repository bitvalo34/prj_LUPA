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

    @Test
    void diskExecutorRecoversAfterBoundedQueueSaturation() throws Exception {
        try (StorageExecutors executors = new StorageExecutors(1, 1, 1, 1)) {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch queuedRan = new CountDownLatch(1);
            CountDownLatch recoveredRan = new CountDownLatch(1);

            executors.diskExecutor().execute(() -> {
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            executors.diskExecutor().execute(queuedRan::countDown);

            StorageExecutors.Snapshot saturated = executors.snapshot();
            assertEquals(1, saturated.diskActive());
            assertEquals(1, saturated.diskQueued());
            assertThrows(
                    RejectedExecutionException.class,
                    () -> executors.diskExecutor().execute(() -> {}));

            releaseFirst.countDown();
            assertTrue(
                    queuedRan.await(1, TimeUnit.SECONDS),
                    "queued work must run once the saturated worker is released");

            executors.diskExecutor().execute(recoveredRan::countDown);
            assertTrue(
                    recoveredRan.await(1, TimeUnit.SECONDS),
                    "executor must accept fresh work after saturation drains");

            StorageExecutors.Snapshot recovered = executors.snapshot();
            assertTrue(recovered.diskQueued() <= 1);
            assertTrue(recovered.diskActive() <= 1);
        }
    }

    @Test
    void closeTerminatesBothOwnedPools() {
        StorageExecutors executors =
                new StorageExecutors(1, 1, 1, 1);

        executors.close();
        executors.close();

        assertTrue(executors.terminatedForTest());
    }
}
