package curxxed.dev.switchboard.bridge;

import curxxed.dev.switchboard.dispatch.Turn;

/** Shows a turn's live progress in the group as one message that is edited, not re-posted. */
public interface ProgressReporter {

    interface Progress {
        /** New status text. Implementations throttle and skip unchanged text. */
        void update(String text);

        /** The last edit, sent regardless of throttling. */
        void finish(String text);

        /** Removes the status message once it has nothing left to say. */
        void dismiss();
    }

    Progress start(Turn turn, String initialText);

    /** For tests and environments without Telegram. */
    ProgressReporter NONE = (turn, text) -> new Progress() {
        @Override
        public void update(String t) {}

        @Override
        public void finish(String t) {}

        @Override
        public void dismiss() {}
    };
}
