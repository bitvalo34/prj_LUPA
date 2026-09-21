package gt.lupa.test;

import gt.lupa.concurrent.MonotonicScheduler;

import java.time.Duration;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class ManualMonotonicScheduler implements MonotonicScheduler {
    private final AtomicLong sequence = new AtomicLong();
    private final PriorityQueue<Task> tasks =
            new PriorityQueue<>(
                    Comparator.comparingLong((Task task) -> task.deadlineNanos)
                            .thenComparingLong(task -> task.sequence));

    private long nowNanos;

    @Override
    public long nowNanos() {
        return nowNanos;
    }

    @Override
    public Handle schedule(Duration delay, Runnable task) {
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        Task scheduled =
                new Task(
                        nowNanos + delay.toNanos(),
                        sequence.getAndIncrement(),
                        task);
        tasks.add(scheduled);
        return () -> {
            if (scheduled.cancelled) return false;
            scheduled.cancelled = true;
            return true;
        };
    }

    public void advance(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        advanceTo(nowNanos + duration.toNanos());
    }

    public void runDue() {
        advanceTo(nowNanos);
    }

    public int pendingTasks() {
        int count = 0;
        for (Task task : tasks) {
            if (!task.cancelled) count++;
        }
        return count;
    }

    private void advanceTo(long target) {
        while (!tasks.isEmpty()) {
            Task next = tasks.peek();
            if (next.deadlineNanos > target) break;
            tasks.remove();
            nowNanos = next.deadlineNanos;
            if (!next.cancelled) {
                next.cancelled = true;
                next.runnable.run();
            }
        }
        nowNanos = target;
    }

    private static final class Task {
        private final long deadlineNanos;
        private final long sequence;
        private final Runnable runnable;
        private boolean cancelled;

        private Task(
                long deadlineNanos,
                long sequence,
                Runnable runnable) {
            this.deadlineNanos = deadlineNanos;
            this.sequence = sequence;
            this.runnable = runnable;
        }
    }
}
