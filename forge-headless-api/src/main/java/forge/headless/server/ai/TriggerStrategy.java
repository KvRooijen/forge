package forge.headless.server.ai;

import forge.headless.protocol.GameStateView;

/** Covers CONFIRM/CONFIRM_ACTION - "may" triggers and optional costs. */
public interface TriggerStrategy {
    boolean confirm(GameStateView state, String prompt);
}
