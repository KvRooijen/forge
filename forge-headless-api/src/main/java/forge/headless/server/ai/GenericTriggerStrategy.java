package forge.headless.server.ai;

import forge.headless.protocol.GameStateView;

/** Same blanket "yes" the simple heuristic AI used - right for most "may
 * you draw a card" triggers, actively wrong for ones with a real cost
 * ("may you sacrifice a permanent", "may you pay 2 life") where it's not
 * always worth it. Not elaborated yet - needs case-by-case cost/benefit,
 * not a blanket answer. */
public class GenericTriggerStrategy implements TriggerStrategy {
    @Override
    public boolean confirm(GameStateView state, String prompt) {
        return true;
    }
}
