package gt.lupa.session;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Serializes per-connection mutable state on top of a bounded shared executor. */
final class SerialExecutor implements Executor {
    private final Executor backend;
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private Runnable active;

    SerialExecutor(Executor backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    @Override
    public synchronized void execute(Runnable command) {
        Objects.requireNonNull(command);
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
