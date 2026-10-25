package curxxed.dev.switchboard.gateway;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A bot command, such as {@code /model codex gpt-6-astra} or {@code /stopall@ClaudeRelayBot}.
 *
 * @param name        lowercase, without the slash
 * @param args        text after the command, trimmed; empty if none
 * @param addressedTo the {@code @BotName} suffix, or null when sent to every bot
 */
public record Command(String name, String args, String addressedTo) {

    /** Commands only the configured administrator may run. */
    public static final Set<String> ADMIN_ONLY = Set.of("model", "stopall", "mode", "project", "discuss", "reauth");

    private static final Pattern COMMAND =
            Pattern.compile("^/([A-Za-z0-9_]{1,32})(?:@([A-Za-z0-9_]{3,32}))?(?:\\s+(.*))?$", Pattern.DOTALL);

    public static Optional<Command> parse(String text) {
        if (text == null) return Optional.empty();
        Matcher m = COMMAND.matcher(text.strip());
        if (!m.matches()) return Optional.empty();
        String args = m.group(3) == null ? "" : m.group(3).strip();
        return Optional.of(new Command(m.group(1).toLowerCase(Locale.ROOT), args, m.group(2)));
    }

    public boolean adminOnly() {
        return ADMIN_ONLY.contains(name);
    }
}
