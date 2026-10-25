package curxxed.dev.switchboard.gateway;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Who the agents are on Telegram: each agent's bot user id and @username (from {@code getMe}), plus
 * the short aliases people type, such as {@code @Claude}, {@code @Codex} and {@code @Gemini}.
 */
public final class AgentDirectory {

    public record AgentBot(String agentKey, String displayName, long botUserId, String username) {}

    private final Map<String, AgentBot> byKey = new LinkedHashMap<>();
    private final Map<Long, AgentBot> byUserId = new LinkedHashMap<>();
    /** Lowercased alias without '@', mapped to agent key. */
    private final Map<String, String> aliases = new LinkedHashMap<>();

    public AgentDirectory(Collection<AgentBot> bots) {
        for (AgentBot bot : bots) {
            if (byKey.put(bot.agentKey(), bot) != null) throw new IllegalArgumentException("duplicate agent " + bot.agentKey());
            if (byUserId.put(bot.botUserId(), bot) != null) {
                throw new IllegalArgumentException("agents " + bot.agentKey() + " and " + byUserId.get(bot.botUserId()).agentKey()
                        + " use the same Telegram bot");
            }
            addAlias(bot.username(), bot.agentKey());
            addAlias(bot.agentKey(), bot.agentKey());
            addAlias(bot.displayName(), bot.agentKey());
        }
    }

    private void addAlias(String alias, String agentKey) {
        String key = alias.toLowerCase(Locale.ROOT);
        String existing = aliases.putIfAbsent(key, agentKey);
        if (existing != null && !existing.equals(agentKey)) {
            throw new IllegalArgumentException("@" + alias + " would refer to both " + existing + " and " + agentKey);
        }
    }

    /** Resolves {@code name} (no '@', any case) to an agent key. */
    public Optional<String> resolveAlias(String name) {
        return Optional.ofNullable(aliases.get(name.toLowerCase(Locale.ROOT)));
    }

    /** Resolves an exact bot @username (any case), as used in {@code /cmd@BotName}. */
    public Optional<String> resolveUsername(String username) {
        return byKey.values().stream().filter(b -> b.username().equalsIgnoreCase(username)).map(AgentBot::agentKey).findFirst();
    }

    public Optional<AgentBot> byUserId(long userId) {
        return Optional.ofNullable(byUserId.get(userId));
    }

    public Optional<AgentBot> byKey(String agentKey) {
        return Optional.ofNullable(byKey.get(agentKey));
    }

    public Collection<AgentBot> all() {
        return byKey.values();
    }

    public String displayNameFor(String agentKey) {
        return byKey(agentKey).map(AgentBot::displayName).orElse(agentKey);
    }
}
