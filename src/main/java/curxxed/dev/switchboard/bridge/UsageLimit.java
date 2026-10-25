package curxxed.dev.switchboard.bridge;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record UsageLimit(String window, Instant resetsAt) {
    private static final Set<String> CODES = Set.of("rate_limit_exceeded", "usage_limit_reached",
            "insufficient_quota", "quota_exceeded", "resource_exhausted", "rate_limit_error");
    private static final Pattern LIMIT = Pattern.compile(
            "(?i)\\b(?:usage[ _-]+limit|rate[ _-]+limit|quota[ _-]+(?:exceeded|exhausted|limit)|"
                    + "(?:weekly|5[ -]?(?:h|hour)) (?:usage )?limit|"
                    + "you(?:'ve| have) (?:hit|reached) (?:your|the) (?:usage )?limit)\\b");
    private static final Pattern WEEKLY = Pattern.compile(
            "(?i)\\b(?:weekly|7[ -]day|one[ -]week)(?:\\s+(?:usage|rate))?\\s+(?:limit|quota|cap|window)\\b");
    private static final Pattern FIVE_HOUR = Pattern.compile(
            "(?i)\\b(?:5[ -]?(?:h|hours?)|five[ -]hour)(?:\\s+(?:usage|rate))?\\s+(?:limit|window)\\b");
    private static final Pattern RESET = Pattern.compile(
            "(?i)(?:resets?|try again|available again)[^\\n]{0,60}?"
                    + "(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2}))");
    private static final String DURATION_PART = "(\\d+)\\s*(days?|d|hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b";
    private static final Pattern RELATIVE_RESET = Pattern.compile(
            "(?i)(?:resets?|try again|retry)\\s+in\\s+((?:" + DURATION_PART + "[ ,]*)+)");
    private static final Pattern DURATION = Pattern.compile("(?i)" + DURATION_PART);

    public static Optional<UsageLimit> detect(JsonNode failure, Instant now) {
        String message = failure.path("message").asText("");
        String code = failure.path("code").asText("").toLowerCase(Locale.ROOT).replace('-', '_');
        if (!CODES.contains(code) && !LIMIT.matcher(message).find()) return Optional.empty();
        String explicitWindow = failure.path("window").asText("").toLowerCase(Locale.ROOT);
        boolean weekly = Set.of("weekly", "week", "7-day").contains(explicitWindow) || WEEKLY.matcher(message).find();
        boolean fiveHour = Set.of("5h", "5-hour", "5-hourly").contains(explicitWindow) || FIVE_HOUR.matcher(message).find();
        String window = weekly == fiveHour ? "unknown window" : weekly ? "weekly" : "5-hour";
        Instant reset = reset(failure, now);
        if (reset == null && failure.path("details").isObject()) reset = reset(failure.path("details"), now);
        if (reset == null) {
            Matcher match = RESET.matcher(message);
            if (match.find()) reset = timestamp(match.group(1));
        }
        if (reset == null) reset = relativeReset(message, now);
        return Optional.of(new UsageLimit(window, reset));
    }

    private static Instant relativeReset(String message, Instant now) {
        Matcher reset = RELATIVE_RESET.matcher(message);
        if (!reset.find()) return null;
        Matcher parts = DURATION.matcher(reset.group(1));
        try {
            long seconds = 0;
            while (parts.find()) {
                long multiplier = switch (parts.group(2).toLowerCase(Locale.ROOT).charAt(0)) {
                    case 'd' -> 86400;
                    case 'h' -> 3600;
                    case 'm' -> 60;
                    default -> 1;
                };
                seconds = Math.addExact(seconds, Math.multiplyExact(Long.parseLong(parts.group(1)), multiplier));
            }
            return seconds <= 366L * 24 * 60 * 60 ? now.plusSeconds(seconds) : null;
        } catch (NumberFormatException | ArithmeticException | DateTimeException ignored) {
            return null;
        }
    }

    private static Instant reset(JsonNode failure, Instant now) {
        for (String field : new String[]{"resetsAt", "resetAt", "resets_at", "reset_at"}) {
            JsonNode value = failure.path(field);
            try {
                if (value.isIntegralNumber() && value.canConvertToLong()) {
                    long epoch = value.longValue();
                    if (epoch > 0) return epoch >= 1_000_000_000_000L
                            ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
                }
                if (value.isTextual()) {
                    Instant parsed = timestamp(value.textValue());
                    if (parsed != null) return parsed;
                }
            } catch (DateTimeException ignored) {
            }
        }
        for (String field : new String[]{"retryAfterSeconds", "retry_after"}) {
            JsonNode value = failure.path(field);
            if (value.isIntegralNumber() && value.canConvertToLong()) {
                long seconds = value.longValue();
                if (seconds >= 0 && seconds <= 366L * 24 * 60 * 60) return now.plusSeconds(seconds);
            }
        }
        return null;
    }

    private static Instant timestamp(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeException ignored) {
            return null;
        }
    }
}
