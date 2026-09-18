package gt.lupa.session;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Serializes mutable state for one connection while keeping the per-session queue bounded. */
final class SerialExecutor implements Executor {
    private static final int DEFAULT_CAPACITY = 64;

    private final Executor backend;
    private final int capacity;
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private Runnable active;

    SerialExecutor(Executor backend) {
        this(backend, DEFAULT_CAPACITY);
    }

    SerialExecutor(Executor backend, int capacity) {
        this.backend = Objects.requireNonNull(backend);
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    @Override
    public synchronized void execute(Runnable command) {
        Objects.requireNonNull(command);
        if (tasks.size() >= capacity) {
            throw new RejectedExecutionException("per-session work queue is full");
        }
        tasks.offer(() -> {
            try {
                command.run();
            } finally {
                scheduleNext();
            }
        });
        if (active == null) {
            try {
                scheduleNext();
            } catch (RuntimeException e) {
                tasks.clear();
                active = null;
                throw e;
            }
        }
    }

    private void scheduleNext() {
        Runnable next;
        synchronized (this) {
            active = tasks.poll();
            next = active;
        }
        if (next != null) {
            try {
                backend.execute(next);
            } catch (RejectedExecutionException e) {
                synchronized (this) {
                    active = null;
                    tasks.clear();
                }
                throw e;
            }
        }
    }
}
