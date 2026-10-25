package curxxed.dev.switchboard.dispatch;

/**
 * Runs one agent turn to completion. Phase 5 implements this against T3 Code. The dispatcher calls
 * it on a dedicated virtual thread and interrupts that thread to cancel.
 */
@FunctionalInterface
public interface AgentRunner {

    /** @return the agent's final reply text, which the dispatcher posts and scans for mentions */
    String run(Turn turn) throws Exception;
}
