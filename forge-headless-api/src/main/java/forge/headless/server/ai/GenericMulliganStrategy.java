package forge.headless.server.ai;

import forge.headless.protocol.CardStateView;
import forge.headless.protocol.GameStateView;
import forge.headless.protocol.PlayerStateView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Land count (2-5, same range the simple heuristic AI used) plus a real
 * curve/color check: among the lands actually in this hand, is there at
 * least one nonland card whose CMC fits the land count and whose colored
 * pips are all covered by what these specific lands can produce? A hand
 * with "the right number" of lands but the wrong colors, or nothing but
 * 6+ CMC bombs with no early play, is barely better than a screwed hand -
 * it just doesn't show up as one from land count alone.
 */
public class GenericMulliganStrategy implements MulliganStrategy {
    @Override
    public boolean keepHand(GameStateView state) {
        PlayerStateView you = AiUtils.you(state);
        List<CardStateView> hand = you != null && you.hand != null ? you.hand : List.of();
        List<CardStateView> lands = new ArrayList<>();
        List<CardStateView> nonlands = new ArrayList<>();
        for (CardStateView c : hand) {
            if (c.typeLine != null && c.typeLine.contains("Land")) {
                lands.add(c);
            } else {
                nonlands.add(c);
            }
        }
        int landCount = lands.size();
        if (landCount < 2 || landCount > 5) {
            return false;
        }

        Set<String> producibleColors = new HashSet<>();
        for (CardStateView l : lands) {
            if (l.producedColors != null) {
                producibleColors.addAll(l.producedColors);
            }
        }
        for (CardStateView c : nonlands) {
            int cmc = ManaUtils.manaValue(c.manaCost);
            if (cmc <= landCount && producibleColors.containsAll(ManaUtils.colorsInCost(c.manaCost))) {
                return true;
            }
        }
        return false;
    }
}
