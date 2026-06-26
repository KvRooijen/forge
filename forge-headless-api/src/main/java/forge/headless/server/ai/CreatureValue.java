package forge.headless.server.ai;

import forge.headless.protocol.CardStateView;

/** How much a single permanent is worth, on its own (not as part of a
 * board) - shared between ThreatAssessor's per-board scoring and
 * TargetingStrategy's "which of these is the best/worst target" picks,
 * so a permanent is judged the same way whether it's being sized up as a
 * threat or as a removal target. */
public final class CreatureValue {
    private CreatureValue() { }

    public static double of(CardStateView card) {
        if (card.typeLine == null) {
            return 0;
        }
        if (card.typeLine.contains("Creature")) {
            int power = card.power != null ? card.power : 0;
            int toughness = card.toughness != null ? card.toughness : 0;
            double multiplier = CombatKeywords.impactMultiplier(card.keywords);
            if (card.sick) {
                multiplier *= 0.6;
            }
            // Both stats matter, not just power - a 0/6 wall is a real
            // blocker/threat-soaker that can never attack profitably but
            // is genuinely hard to remove or race through, and a
            // high-toughness creature survives combat that a vanilla
            // same-power one wouldn't.
            double value = (power + toughness) * 0.5 * multiplier;
            if (card.isCommander) {
                value += 3;
            }
            return value;
        }
        if (card.typeLine.contains("Planeswalker")) {
            Integer loyalty = card.counters != null ? card.counters.get("LOYALTY") : null;
            // Loyalty is a direct, real proxy for "how much value is left
            // to extract" - floored rather than zero, since even a
            // freshly-cast walker about to activate/ultimate is a real
            // target, not nothing.
            return Math.max(3, loyalty != null ? loyalty : 0);
        }
        if (card.typeLine.contains("Artifact") || card.typeLine.contains("Enchantment")) {
            // No oracle text to read, so this can't tell a mana rock from
            // a game-winning combo piece apart - a flat "this permanent
            // is probably doing something" value beats the previous "0,
            // completely invisible to threat assessment and targeting"
            // status quo, which made every non-creature permanent on the
            // board simply not exist as far as the AI could tell.
            return 2.5;
        }
        return 0;
    }
}
