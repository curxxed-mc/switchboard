package curxxed.dev.switchboard.app;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs long-lived workers (bot pollers, maintenance) each on its own virtual thread. A worker that
 * throws an exception is restarted with exponential backoff. One that returns normally, or dies
 * with an {@link Error}, is not.
 * {@link #close()} interrupts everything, so workers must treat interruption as "stop now".
 */
public final class Supervisor implements AutoCloseable {

    @FunctionalInterface
    public interface Worker {
        void run() throws Exception;
    }

    static final Duration MIN_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(1);
    /** A run that lasted this long counts as healthy, so the next crash restarts quickly again. */
    static final Duration HEALTHY_RUN = Duration.ofMinutes(5);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Future<?>> running = new ArrayList<>();
    private final Consumer<String> log;
    private final Duration minBackoff;
    private volatile boolean closing;

    public Supervisor(Consumer<String> log) {
        this(log, MIN_BACKOFF);
    }

    Supervisor(Consumer<String> log, Duration minBackoff) {
        this.log = log;
        this.minBackoff = minBackoff;
    }

    public synchronized void start(String name, Worker worker) {
        if (closing) throw new IllegalStateException("supervisor is closing");
        running.add(executor.submit(() -> supervise(name, worker)));
    }

    private void supervise(String name, Worker worker) {
        Thread.currentThread().setName(name);
        Duration backoff = minBackoff;
        while (!closing && !Thread.currentThread().isInterrupted()) {
            long startedAt = System.nanoTime();
            try {
                worker.run();
                log.accept(name + " finished");
                return;
            } catch (InterruptedException e) {
                return;
            } catch (Error e) {
                log.accept(name + " died (" + e + "); not restarting");
                return;
            } catch (Exception e) {
                if (closing) return;
                if (Duration.ofNanos(System.nanoTime() - startedAt).compareTo(HEALTHY_RUN) >= 0) backoff = minBackoff;
                log.accept(name + " crashed (" + e + "); restarting in " + backoff.toMillis() + "ms");
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    return;
                }
                backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
            }
        }
    }

    /** Blocks until every worker has finished or {@link #close()} is called. */
    public void awaitAll() throws InterruptedException {
        List<Future<?>> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(running);
        }
        for (Future<?> f : snapshot) {
            try {
                f.get();
            } catch (CancellationException | ExecutionException ignored) {
                // Cancellation happens on close; supervise() never throws.
            }
        }
    }

    @Override
    public void close() {
        closing = true;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) log.accept("some workers did not stop within 10s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
