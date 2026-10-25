package curxxed.dev.switchboard.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    @Test
    void exampleConfigIsValid() throws Exception {
        AppConfig config = AppConfig.load(Path.of("config.example.json"));
        assertEquals("claudeAgent", config.agents().get("claude").instanceId());
        assertEquals("antigravity", config.agents().get("gemini").instanceId());
    }

    @Test
    void rejectsSharedBotTokensAndPositiveGroupId(@TempDir Path dir) throws Exception {
        String json = Files.readString(Path.of("config.example.json"))
                .replace("-1001234567890", "42")
                .replace("456:DEF-from-BotFather", "123:ABC-from-BotFather");
        Path file = dir.resolve("config.json");
        Files.writeString(file, json);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AppConfig.load(file));
        assertTrue(e.getMessage().contains("groupId"), e.getMessage());
        assertTrue(e.getMessage().contains("own bot token"), e.getMessage());
    }

    @Test
    void secretsNeverAppearInToString() throws Exception {
        AppConfig config = AppConfig.load(Path.of("config.example.json"));
        String rendered = config.toString();
        assertFalse(rendered.contains("ABC-from-BotFather"));
        assertFalse(rendered.contains("t3 pair"));
    }
}
