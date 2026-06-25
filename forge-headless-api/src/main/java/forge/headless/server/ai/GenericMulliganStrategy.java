package forge.headless.server.ai;

import forge.headless.protocol.CardStateView;
import forge.headless.protocol.GameStateView;
import forge.headless.protocol.PlayerStateView;

import java.util.List;

/** Same land-count check the simple heuristic AI used - not elaborated
 * yet (curve/color-requirement-aware mulligans are a later category). */
public class GenericMulliganStrategy implements MulliganStrategy {
    @Override
    public boolean keepHand(GameStateView state) {
        PlayerStateView you = AiUtils.you(state);
        List<CardStateView> hand = you != null && you.hand != null ? you.hand : List.of();
        long lands = hand.stream().filter(c -> c.typeLine != null && c.typeLine.contains("Land")).count();
        return lands >= 2 && lands <= 5;
    }
}
