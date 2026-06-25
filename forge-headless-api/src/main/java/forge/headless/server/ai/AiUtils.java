package forge.headless.server.ai;

import forge.headless.protocol.CardStateView;
import forge.headless.protocol.GameStateView;
import forge.headless.protocol.PlayerStateView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small shared lookups every strategy needs - pulled out once
 * InProcessAiChannel and the new rule-based strategies both needed the
 * same "which player view is me" / "all my own cards by id" logic. */
public final class AiUtils {
    private AiUtils() { }

    public static PlayerStateView you(GameStateView state) {
        if (state == null || state.players == null) {
            return null;
        }
        for (PlayerStateView p : state.players) {
            if (p.isYou) {
                return p;
            }
        }
        return null;
    }

    /** Every card you currently know the id of (hand/battlefield/command
     * zone) - library/graveyard/exile contents of other zones aren't
     * meaningfully "yours to act on" the same way for most decisions. */
    public static Map<String, CardStateView> cardsById(GameStateView state) {
        PlayerStateView you = you(state);
        Map<String, CardStateView> map = new HashMap<>();
        if (you == null) {
            return map;
        }
        for (CardStateView c : you.hand != null ? you.hand : List.<CardStateView>of()) {
            map.put(c.id, c);
        }
        for (CardStateView c : you.battlefield != null ? you.battlefield : List.<CardStateView>of()) {
            map.put(c.id, c);
        }
        for (CardStateView c : you.commandZone != null ? you.commandZone : List.<CardStateView>of()) {
            map.put(c.id, c);
        }
        return map;
    }
}
