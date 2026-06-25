package forge.headless.server.ai;

import forge.headless.protocol.CardStateView;
import forge.headless.protocol.GameStateView;
import forge.headless.protocol.PlayerStateView;

import java.util.List;

/**
 * Deck-agnostic threat scoring: board power (weighted for evasive/
 * hard-to-block keywords, since a 4/4 flier is a much bigger clock than a
 * 4/4 vanilla that can be chump-blocked forever), hand size, available
 * mana, and Commander-specific ongoing-value signals (Monarch/Initiative,
 * poison as a separate win condition). Deliberately not deck-specific -
 * doesn't know "this card is the combo piece", just what's observable
 * from anyone's state view, same as a player meeting an unfamiliar deck
 * for the first time would judge it.
 */
public class GenericThreatAssessor implements ThreatAssessor {
    @Override
    public double threatScore(GameStateView state, PlayerStateView player, PlayerStateView viewer) {
        if (player == null) {
            return 0;
        }
        double score = 0;
        List<CardStateView> battlefield = player.battlefield != null ? player.battlefield : List.of();

        for (CardStateView c : battlefield) {
            if (c.typeLine == null || !c.typeLine.contains("Creature")) {
                continue;
            }
            int power = c.power != null ? c.power : 0;
            if (power <= 0) {
                continue;
            }
            double multiplier = CombatKeywords.impactMultiplier(c.keywords);
            // Can't swing immediately, but still a real near-term threat -
            // discounted, not ignored.
            if (c.sick) {
                multiplier *= 0.6;
            }
            score += power * multiplier;
            // Usually the centerpiece of the deck's plan (extra abilities,
            // what recursion targets) - worth more than its raw stats say.
            if (c.isCommander) {
                score += 3;
            }
        }

        // More unknown cards in hand = more unplayed-for plays coming.
        score += player.handCount * 1.5;

        // More lands = bigger spells coming online soon, even before they
        // hit the battlefield.
        long lands = battlefield.stream().filter(c -> c.typeLine != null && c.typeLine.contains("Land")).count();
        score += lands * 0.5;

        // Monarch/Initiative are real ongoing value engines in Commander
        // specifically (extra card or dungeon-venturing every turn) -
        // whoever holds either is accruing advantage every turn it's not
        // taken away from them.
        if (state != null && state.monarch != null && state.monarch.equals(player.name)) {
            score += 4;
        }
        if (state != null && state.hasInitiative != null && state.hasInitiative.equals(player.name)) {
            score += 3;
        }

        // Poison is a separate win condition entirely, and danger scales
        // faster than linearly as it approaches the 10-counter threshold.
        if (player.poisonCounters > 0) {
            score += player.poisonCounters * player.poisonCounters * 0.3;
        }

        return score;
    }
}
