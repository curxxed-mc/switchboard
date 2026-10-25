package curxxed.dev.switchboard.bridge;

import curxxed.dev.switchboard.dispatch.Turn;

/** Tells an agent, in its prompt, whether its mentions will start other agents' turns. */
public interface HandoffPolicy {

    /** True when the mode, or a {@code /discuss} chain, lets this turn's mentions start turns. */
    boolean live(Turn turn);

    /** Further hand-offs the depth limit allows after this turn. */
    int hopsLeft(Turn turn);
}
