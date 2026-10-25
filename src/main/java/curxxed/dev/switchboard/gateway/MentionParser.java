package curxxed.dev.switchboard.gateway;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds which agents a piece of text addresses. It works on plain text rather than Telegram
 * entities because agent-written replies need the same treatment (Phase 5), and those never come
 * back from Telegram as updates.
 *
 * <p>Rules: a mention is {@code @name} not preceded by a word character, so {@code a@claude.dev}
 * doesn't count. The name must resolve to an agent alias. Text inside backtick code spans and
 * fenced blocks is ignored, so quoting {@code @Codex} in code never triggers it.
 *
 * <p>{@code @everyone} and {@code @all} address every agent, but only when the caller allows it:
 * for the admin's messages, never for agent replies, where one broadcast would wake both other
 * agents and each of their replies could fan out again.
 */
public final class MentionParser {

    private static final Pattern FENCED = Pattern.compile("```.*?(```|\\z)", Pattern.DOTALL);
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");
    static final Set<String> BROADCAST = Set.of("everyone", "all");

    private final AgentDirectory directory;
    private final Pattern mention;

    public MentionParser(AgentDirectory directory) {
        this.directory = directory;
        String aliases = Stream.concat(BROADCAST.stream(), directory.all().stream()
                        .flatMap(bot -> Stream.of(bot.agentKey(), bot.displayName(), bot.username())))
                .distinct().sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).collect(Collectors.joining("|"));
        this.mention = Pattern.compile("(?<![\\w@])@(" + aliases + ")(?![\\w@])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    }

    /** Agent keys mentioned in {@code text}, in order of first appearance. Broadcasts are ignored. */
    public Set<String> mentionedAgents(String text) {
        return mentionedAgents(text, false);
    }

    /** @param allowBroadcast true to expand {@code @everyone}/{@code @all} to every agent */
    public Set<String> mentionedAgents(String text, boolean allowBroadcast) {
        Set<String> found = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) return found;
        String prose = INLINE_CODE.matcher(FENCED.matcher(text).replaceAll(" ")).replaceAll(" ");
        Matcher m = mention.matcher(prose);
        while (m.find()) {
            String name = m.group(1).toLowerCase(Locale.ROOT);
            if (BROADCAST.contains(name)) {
                if (allowBroadcast) directory.all().forEach(bot -> found.add(bot.agentKey()));
                continue;
            }
            Optional<String> agent = directory.resolveAlias(name);
            agent.ifPresent(found::add);
        }
        return found;
    }
}
