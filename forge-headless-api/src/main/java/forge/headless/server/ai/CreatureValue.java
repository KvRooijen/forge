package forge.headless.server.ai;

import forge.headless.protocol.CardStateView;

/** How much a single creature is worth, on its own (not as part of a
 * board) - shared between ThreatAssessor's per-board scoring and
 * TargetingStrategy's "which of these is the best/worst target" picks,
 * so a creature is judged the same way whether it's being sized up as a
 * threat or as a removal target. */
public final class CreatureValue {
    private CreatureValue() { }

    public static double of(CardStateView card) {
        if (card.typeLine == null || !card.typeLine.contains("Creature")) {
            return 0;
        }
        int power = card.power != null ? card.power : 0;
        if (power <= 0) {
            return 0;
        }
        double multiplier = CombatKeywords.impactMultiplier(card.keywords);
        if (card.sick) {
            multiplier *= 0.6;
        }
        double value = power * multiplier;
        if (card.isCommander) {
            value += 3;
        }
        return value;
    }
}
