package curxxed.dev.switchboard.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SupervisorTest {

    private final List<String> log = new CopyOnWriteArrayList<>();

    @Test
    void restartsAWorkerThatThrows() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (Supervisor supervisor = new Supervisor(log::add, Duration.ofMillis(5))) {
            supervisor.start("flaky", () -> {
                if (attempts.incrementAndGet() < 3) throw new IllegalStateException("boom");
            });
            supervisor.awaitAll();
        }
        assertEquals(3, attempts.get());
        assertEquals(2, log.stream().filter(l -> l.startsWith("flaky crashed")).count(), log.toString());
        assertTrue(log.contains("flaky finished"), log.toString());
    }

    @Test
    void doesNotRestartAfterAnError() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (Supervisor supervisor = new Supervisor(log::add, Duration.ofMillis(5))) {
            supervisor.start("doomed", () -> {
                attempts.incrementAndGet();
                throw new AssertionError("fatal");
            });
            supervisor.awaitAll();
        }
        assertEquals(1, attempts.get());
        assertTrue(log.get(0).startsWith("doomed died"), log.toString());
    }

    @Test
    void closeInterruptsBlockedWorkersWithoutRestarting() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        AtomicInteger runs = new AtomicInteger();
        Supervisor supervisor = new Supervisor(log::add, Duration.ofMillis(5));
        for (String name : new String[] {"poller-a", "poller-b"}) {
            supervisor.start(name, () -> {
                runs.incrementAndGet();
                started.countDown();
                Thread.sleep(Duration.ofHours(1));
            });
        }
        assertTrue(started.await(5, TimeUnit.SECONDS));
        supervisor.close();
        supervisor.awaitAll();
        assertEquals(2, runs.get());
        assertTrue(log.stream().noneMatch(l -> l.contains("crashed")), log.toString());
        assertThrows(IllegalStateException.class, () -> supervisor.start("late", () -> {}));
    }

    @Test
    void workersRunOnNamedVirtualThreads() throws Exception {
        String[] seen = new String[2];
        try (Supervisor supervisor = new Supervisor(log::add)) {
            supervisor.start("bot-claude", () -> {
                seen[0] = Thread.currentThread().getName();
                seen[1] = String.valueOf(Thread.currentThread().isVirtual());
            });
            supervisor.awaitAll();
        }
        assertArrayEquals(new String[] {"bot-claude", "true"}, seen);
    }
}
