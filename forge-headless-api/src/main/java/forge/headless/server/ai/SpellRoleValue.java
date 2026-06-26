package forge.headless.server.ai;

import forge.headless.protocol.CardStateView;

import java.util.List;

/** Board-aware "what is this effect worth right now" scoring for the
 * coarse REMOVAL/SWEEPER/DRAW roles, shared by every consumer that needs
 * it so the same effect is valued identically wherever it appears:
 * GenericSpellSequencer (what does casting this spell / this creature's
 * ETB do) and RemotePlayerController.chooseModeForAbility (which mode of a
 * modal spell is best given the board). Returns 0 for RAMP (callers that
 * care about ramp handle it separately via their own mana-progress taper)
 * and for null/unclassified roles. */
public final class SpellRoleValue {
    private SpellRoleValue() { }

    public static double of(String role, List<CardStateView> opponentCreatures, List<CardStateView> myCreatures) {
        if ("REMOVAL".equals(role)) {
            double bestTarget = 0;
            for (CardStateView c : opponentCreatures) {
                bestTarget = Math.max(bestTarget, CreatureValue.of(c));
            }
            // Genuinely zero, not a small positive floor: a positive value
            // here would always win against the implicit "do nothing"
            // baseline whenever nothing else competes, i.e. it would fire
            // removal into an empty board exactly when there's no
            // profitable target. Zero correctly makes a no-target removal
            // effect lose to any alternative. (See GenericSpellSequencer's
            // knapsack tie-breaking, which relies on exactly this.)
            return bestTarget;
        }
        if ("SWEEPER".equals(role)) {
            double opp = 0;
            for (CardStateView c : opponentCreatures) {
                opp += CreatureValue.of(c);
            }
            double mine = 0;
            for (CardStateView c : myCreatures) {
                mine += CreatureValue.of(c);
            }
            // Negative when I'd lose more than the opponent - "don't wipe
            // when you're ahead".
            return opp - mine;
        }
        if ("DRAW".equals(role)) {
            // Roughly a mid-curve play's worth - competes with creatures
            // without dominating them.
            return 3.0;
        }
        return 0;
    }
}
