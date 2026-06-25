package forge.headless.server.ai;

import forge.headless.protocol.GameStateView;
import forge.headless.protocol.PlayerStateView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared "how dangerous is this player right now" signal - a single input
 * several other categories (removal targeting, attack-target choice in a
 * pod, board-wipe timing) need and should agree on, rather than each
 * re-deriving "who's winning" with slightly different logic.
 */
public interface ThreatAssessor {
    /** Higher is scarier. Not bounded to any particular range - only
     * meaningful relative to other players in the same call. */
    double threatScore(GameStateView state, PlayerStateView player, PlayerStateView viewer);

    default List<PlayerStateView> rankByThreat(GameStateView state, PlayerStateView viewer) {
        List<PlayerStateView> others = new ArrayList<>();
        if (state != null && state.players != null) {
            for (PlayerStateView p : state.players) {
                if (p != viewer) {
                    others.add(p);
                }
            }
        }
        others.sort(Comparator.comparingDouble((PlayerStateView p) -> threatScore(state, p, viewer)).reversed());
        return others;
    }

    default PlayerStateView mostThreatening(GameStateView state, PlayerStateView viewer) {
        List<PlayerStateView> ranked = rankByThreat(state, viewer);
        return ranked.isEmpty() ? null : ranked.get(0);
    }
}
