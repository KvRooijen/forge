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
 *
 * Standards relax as cardsToReturn grows (how many cards the *next*
 * mulligan would tuck back, under the London rule - 0 on a fresh hand,
 * rising with each mulligan already taken). Without this, the exact same
 * bar applied to a fresh 7 and to the fourth consecutive re-draw alike -
 * with no floor, this could (and did, before the LondonMulligan engine
 * bug was separately fixed) spiral toward an almost-empty forced keep.
 * Continuing to dig gets strictly more expensive (a smaller hand even if
 * it's eventually kept) while the odds of drawing a "perfect" hand don't
 * meaningfully improve, so past a certain depth a mediocre hand is simply
 * worth more than the hand size lost digging further.
 */
public class GenericMulliganStrategy implements MulliganStrategy {
    @Override
    public boolean keepHand(GameStateView state, int cardsToReturn) {
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
        int landMin = cardsToReturn >= 2 ? 1 : 2;
        int landMax = cardsToReturn >= 2 ? 6 : 5;
        if (landCount < landMin || landCount > landMax) {
            return false;
        }
        if (cardsToReturn >= 4) {
            // Dug deep enough that the cost of mulliganing again (an even
            // smaller hand) outweighs being picky about curve/colors - any
            // hand with a workable land count is worth keeping outright.
            return true;
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
