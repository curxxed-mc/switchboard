package curxxed.dev.switchboard.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import curxxed.dev.switchboard.app.Log;
import curxxed.dev.switchboard.dispatch.Turn;
import curxxed.dev.switchboard.gateway.AgentDirectory;
import curxxed.dev.switchboard.gateway.ButtonHandler;
import curxxed.dev.switchboard.gateway.ButtonPress;
import curxxed.dev.switchboard.t3.T3RpcClient;
import curxxed.dev.switchboard.telegram.TelegramApi;
import curxxed.dev.switchboard.telegram.TelegramApi.Button;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Brings T3 approval requests into the group. Each pending request becomes a message from the
 * agent's bot with one button per option T3 offers. Only the admin's tap counts. The decision goes
 * back to T3 as {@code runtime-request.respond}, and the message is edited to record who decided
 * what. Requests settled some other way (in the T3 app, expired, run ended) lose their buttons.
 */
public final class ApprovalDesk implements ButtonHandler {

    static final String PREFIX = "ap:";
    static final Duration RESPOND_TIMEOUT = Duration.ofSeconds(30);

    /** What the respond call does. Separate so tests needn't run a T3 socket. */
    @FunctionalInterface
    public interface Responder {
        void respond(String threadId, String requestId, String decision) throws Exception;
    }

    /** Offered when T3 sends a request without its own options. */
    static final List<RunTracker.ApprovalOption> DEFAULT_OPTIONS = List.of(
            new RunTracker.ApprovalOption("accept", "Approve", null),
            new RunTracker.ApprovalOption("acceptForSession", "Approve for this session", null),
            new RunTracker.ApprovalOption("decline", "Deny", null));

    private static final Set<String> DENIALS = Set.of("decline", "cancel");

    private final class Ticket {
        final String token;
        final Turn turn;
        final String threadId;
        final RunTracker.Approval approval;
        final List<RunTracker.ApprovalOption> options;
        final long messageId;
        boolean deciding;

        Ticket(String token, Turn turn, String threadId, RunTracker.Approval approval, long messageId) {
            this.token = token;
            this.turn = turn;
            this.threadId = threadId;
            this.approval = approval;
            this.options = approval.options().isEmpty() ? DEFAULT_OPTIONS : approval.options();
            this.messageId = messageId;
        }
    }

    private final Map<String, TelegramApi> bots;
    private final Responder responder;
    private final AgentDirectory directory;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Ticket> byToken = new LinkedHashMap<>();
    private final Map<String, String> tokenByRequest = new LinkedHashMap<>();

    public ApprovalDesk(Map<String, TelegramApi> bots, Responder responder, AgentDirectory directory) {
        this.bots = bots;
        this.responder = responder;
        this.directory = directory;
    }

    /** The responder that answers through T3's {@code orchestration.dispatchCommand}. */
    public static Responder viaT3(T3RpcClient rpc, ObjectMapper mapper) {
        return (threadId, requestId, decision) -> {
            ObjectNode cmd = mapper.createObjectNode();
            cmd.put("type", "runtime-request.respond");
            cmd.put("commandId", UUID.randomUUID().toString());
            cmd.put("threadId", threadId);
            cmd.put("requestId", requestId);
            cmd.put("decision", decision);
            rpc.call("orchestration.dispatchCommand", cmd, RESPOND_TIMEOUT);
        };
    }

    /**
     * Brings the group in line with a run's pending approvals: posts new ones and retires ones
     * that were settled elsewhere. Called by the runner whenever the run changes.
     */
    public void sync(Turn turn, String threadId, List<RunTracker.Approval> pending) {
        Set<String> open = pending.stream().map(RunTracker.Approval::requestId).collect(Collectors.toSet());
        List<Ticket> settled = new ArrayList<>();
        List<RunTracker.Approval> fresh = new ArrayList<>();
        synchronized (this) {
            for (Ticket t : byToken.values()) {
                if (t.threadId.equals(threadId) && !t.deciding && !open.contains(t.approval.requestId())) settled.add(t);
            }
            settled.forEach(this::forget);
            for (RunTracker.Approval a : pending) if (!tokenByRequest.containsKey(a.requestId())) fresh.add(a);
        }
        for (Ticket t : settled) edit(t, describe(t, true) + "\n\nSettled in T3 Code.");
        for (RunTracker.Approval a : fresh) announce(turn, threadId, a);
    }

    /** The run ended: whatever is still open can no longer be answered here. */
    public void close(String threadId, String why) {
        List<Ticket> left = new ArrayList<>();
        synchronized (this) {
            for (Ticket t : byToken.values()) if (t.threadId.equals(threadId) && !t.deciding) left.add(t);
            left.forEach(this::forget);
        }
        for (Ticket t : left) edit(t, describe(t, true) + "\n\nNo longer needed: " + why + ".");
    }

    private void announce(Turn turn, String threadId, RunTracker.Approval approval) {
        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        List<RunTracker.ApprovalOption> options = approval.options().isEmpty() ? DEFAULT_OPTIONS : approval.options();
        List<List<Button>> rows = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            rows.add(List.of(new Button(options.get(i).label(), PREFIX + token + ":" + i)));
        }
        String text = describe(turn.agentKey(), approval, false) + warnings(options);
        try {
            long messageId = bots.get(turn.agentKey()).sendMessage(turn.chatId(), text, turn.triggerMessageId(), rows);
            synchronized (this) {
                Ticket t = new Ticket(token, turn, threadId, approval, messageId);
                byToken.put(token, t);
                tokenByRequest.put(approval.requestId(), token);
            }
            Log.info(turn.agentKey() + " asked for approval (" + approval.kind() + "), request …" + shortId(approval.requestId()));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.warn("could not post approval request for " + turn.agentKey() + ": " + e.getMessage());
        }
    }

    @Override
    public void press(ButtonPress press, TelegramApi via) throws Exception {
        if (!press.data().startsWith(PREFIX)) {
            via.answerCallbackQuery(press.queryId(), null, false);
            return;
        }
        String[] parts = press.data().substring(PREFIX.length()).split(":");
        Ticket ticket;
        RunTracker.ApprovalOption option = null;
        String refusal = null;
        boolean alert = false;
        synchronized (this) { // decide under the lock, talk to Telegram outside it
            ticket = parts.length == 2 ? byToken.get(parts[0]) : null;
            int index = -1;
            try {
                if (parts.length == 2) index = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                // stays -1
            }
            if (ticket == null || ticket.deciding) {
                refusal = "This approval is no longer open.";
            } else if (!press.fromAdmin()) {
                refusal = "Only the relay administrator can approve.";
                alert = true;
            } else if (index < 0 || index >= ticket.options.size()) {
                refusal = "Unknown choice.";
            } else {
                option = ticket.options.get(index);
                ticket.deciding = true; // a second tap now says "no longer open"
            }
        }
        if (refusal != null) {
            via.answerCallbackQuery(press.queryId(), refusal, alert);
            return;
        }
        try {
            responder.respond(ticket.threadId, ticket.approval.requestId(), option.decision());
        } catch (Exception e) {
            synchronized (this) {
                ticket.deciding = false; // let the admin try again
            }
            Log.warn("T3 rejected approval …" + shortId(ticket.approval.requestId()) + ": " + e.getMessage());
            via.answerCallbackQuery(press.queryId(), "T3 didn't accept that: " + RunTracker.clip(String.valueOf(e.getMessage()), 150), true);
            return;
        }
        synchronized (this) {
            forget(ticket);
        }
        boolean denied = DENIALS.contains(option.decision());
        Log.info(ticket.turn.agentKey() + " approval …" + shortId(ticket.approval.requestId()) + ": " + option.decision()
                + " by " + press.fromName());
        via.answerCallbackQuery(press.queryId(), denied ? "Denied" : "Approved", false);
        edit(ticket, describe(ticket, true) + "\n\n" + (denied ? "Denied" : "Approved") + " by " + press.fromName()
                + " (" + option.label() + ")");
    }

    /** T3 request ids run to hundreds of characters; the tail is unique enough for a log line. */
    static String shortId(String requestId) {
        return requestId.length() <= 12 ? requestId : requestId.substring(requestId.length() - 12);
    }

    private void forget(Ticket t) {
        byToken.remove(t.token);
        tokenByRequest.remove(t.approval.requestId());
    }

    private void edit(Ticket t, String text) {
        try {
            bots.get(t.turn.agentKey()).editMessageText(t.turn.chatId(), t.messageId, text);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.warn("could not update approval message: " + e.getMessage());
        }
    }

    /** Requests still waiting for a tap, for tests and diagnostics. */
    synchronized int open() {
        return byToken.size();
    }

    private String describe(Ticket t, boolean past) {
        return describe(t.turn.agentKey(), t.approval, past);
    }

    String describe(String agentKey, RunTracker.Approval a, boolean past) {
        String what = switch (a.kind()) {
            case "command" -> "to run a command";
            case "file-read" -> "to read files";
            case "file-change" -> "to change files";
            case "mcp-elicitation" -> "for an MCP tool request";
            default -> "for a permission";
        };
        StringBuilder s = new StringBuilder(directory.displayNameFor(agentKey))
                .append(past ? " wanted approval " : " wants approval ").append(what).append(':');
        if (a.prompt() != null && !a.prompt().isBlank()) s.append('\n').append(RunTracker.clip(a.prompt(), 1500));
        if (a.reason() != null && !a.reason().isBlank()) s.append("\n\nWhy: ").append(RunTracker.clip(a.reason(), 500));
        return s.toString();
    }

    private static String warnings(List<RunTracker.ApprovalOption> options) {
        StringBuilder s = new StringBuilder();
        for (RunTracker.ApprovalOption o : options) {
            if (o.warning() != null && !o.warning().isBlank()) s.append("\n• ").append(o.label()).append(": ").append(o.warning());
        }
        return s.isEmpty() ? "" : "\n" + s;
    }
}
