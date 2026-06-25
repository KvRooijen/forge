package forge.headless.server.ai;

import forge.headless.protocol.CardStateView;
import forge.headless.protocol.DecisionRequest;
import forge.headless.protocol.GameStateView;
import forge.headless.protocol.PlayerStateView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Land sequencing from real per-card signals (Card.canProduceColorMana,
 * "enters tapped" detection - see CardStateView.producedColors and
 * .entersTapped) instead of "play whichever land option happened first",
 * which is all the simple heuristic AI does.
 *
 * Two ideas drive the scoring: (1) a land that fixes a color my hand
 * actually needs and I don't have a source for yet is worth far more than
 * one that doesn't, even if both "count" as one mana; (2) a tapped land
 * only costs tempo on a turn I'd otherwise have used that mana - free on a
 * turn I have nothing castable anyway, so get tapped lands out of the way
 * early rather than holding them for a turn they'd actually hurt.
 */
public class GenericLandPlayStrategy implements LandPlayStrategy {
    @Override
    public DecisionRequest.Option chooseLand(GameStateView state, PlayerStateView me, List<DecisionRequest.Option> landOptions) {
        if (landOptions.isEmpty()) {
            return null;
        }
        if (landOptions.size() == 1) {
            return landOptions.get(0);
        }

        Set<String> colorsNeeded = colorsNeededByUncastSpells(me);
        Set<String> colorsAlreadyAvailable = colorsCurrentlyProducible(me);
        boolean haveCastableNow = hasCastableNonLand(me);

        DecisionRequest.Option best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (DecisionRequest.Option o : landOptions) {
            double score = scoreLand(o.card, colorsNeeded, colorsAlreadyAvailable, haveCastableNow);
            if (score > bestScore) {
                bestScore = score;
                best = o;
            }
        }
        return best != null ? best : landOptions.get(0);
    }

    private double scoreLand(CardStateView land, Set<String> colorsNeeded, Set<String> colorsAvailable, boolean haveCastableNow) {
        if (land == null) {
            return 0;
        }
        double score = 0;
        List<String> produced = land.producedColors != null ? land.producedColors : List.of();
        for (String color : produced) {
            if (colorsNeeded.contains(color) && !colorsAvailable.contains(color)) {
                // The single biggest signal: this is the first source of a
                // color my hand actually needs. Without it, a card needing
                // that color is uncastable all game no matter how many
                // generic-mana lands I play.
                score += 5;
            } else if (colorsNeeded.contains(color)) {
                // Still useful (a second source reduces flood-on-one-color
                // risk) - just nowhere near as urgent as the first.
                score += 1;
            }
        }
        // Flexibility for whatever's drawn later, beyond just what's
        // in hand right now.
        score += produced.size() * 0.3;

        if (land.entersTapped) {
            score += haveCastableNow ? -2 : 0.5;
        } else if (!haveCastableNow) {
            // Mild preference to save the more flexible untapped land for
            // a turn it actually matters.
            score += 0.2;
        }
        return score;
    }

    private Set<String> colorsNeededByUncastSpells(PlayerStateView me) {
        Set<String> needed = new HashSet<>();
        for (CardStateView c : allUncastSpells(me)) {
            needed.addAll(ManaUtils.colorsInCost(c.manaCost));
        }
        return needed;
    }

    private Set<String> colorsCurrentlyProducible(PlayerStateView me) {
        Set<String> have = new HashSet<>();
        if (me == null || me.battlefield == null) {
            return have;
        }
        for (CardStateView c : me.battlefield) {
            if (c.producedColors != null) {
                have.addAll(c.producedColors);
            }
        }
        return have;
    }

    private boolean hasCastableNonLand(PlayerStateView me) {
        if (me == null || me.battlefield == null) {
            return false;
        }
        long lands = me.battlefield.stream().filter(c -> c.typeLine != null && c.typeLine.contains("Land")).count();
        for (CardStateView c : allUncastSpells(me)) {
            if (ManaUtils.manaValue(c.manaCost) <= lands) {
                return true;
            }
        }
        return false;
    }

    private List<CardStateView> allUncastSpells(PlayerStateView me) {
        List<CardStateView> all = new ArrayList<>();
        if (me == null) {
            return all;
        }
        if (me.hand != null) {
            all.addAll(me.hand);
        }
        if (me.commandZone != null) {
            all.addAll(me.commandZone);
        }
        return all;
    }
}
