package curxxed.dev.switchboard.t3;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class T3RuntimeStateTest {

    // Shape captured from the live T3 Code (Beta) 0.1.2 server on this machine.
    private static final String LIVE = """
            {"version":1,"pid":83880,"host":"127.0.0.1","port":3773,"origin":"http://127.0.0.1:3773",
             "startedAt":"2026-10-25T12:53:38.618Z","serverVersion":"0.1.2","ownerId":"47359c2c"}""";

    @Test
    void parsesLiveRuntimeFile() throws IOException {
        T3RuntimeState state = T3RuntimeState.parse(LIVE);
        assertEquals(3773, state.port());
        assertEquals("0.1.2", state.serverVersion());
        assertEquals("http://127.0.0.1:3773", state.originUri().toString());
    }

    @Test
    void rejectsUnknownVersion() {
        assertThrows(IOException.class, () -> T3RuntimeState.parse(LIVE.replace("\"version\":1", "\"version\":2")));
    }

    @Test
    void rejectsMissingOrigin() {
        assertThrows(IOException.class, () -> T3RuntimeState.parse("{\"version\":1,\"pid\":1,\"port\":1}"));
    }

    @Test
    void detectsStalePid() throws IOException {
        T3RuntimeState self = T3RuntimeState.parse(LIVE.replace("83880", Long.toString(ProcessHandle.current().pid())));
        assertTrue(self.processAlive());
        T3RuntimeState gone = T3RuntimeState.parse(LIVE.replace("83880", Long.toString(Integer.MAX_VALUE)));
        assertFalse(gone.processAlive());
    }
}
