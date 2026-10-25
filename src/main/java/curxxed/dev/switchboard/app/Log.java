package curxxed.dev.switchboard.app;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Timestamped lines on stderr, tagged with the current thread (each worker names its thread). */
public final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {}

    public static void info(String message) {
        write("INFO", message);
    }

    public static void warn(String message) {
        write("WARN", message);
    }

    private static void write(String level, String message) {
        String thread = Thread.currentThread().getName();
        System.err.println(LocalTime.now().format(TIME) + " " + level + " [" + (thread.isEmpty() ? "vthread" : thread)
                + "] " + message);
    }
}
