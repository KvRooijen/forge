package forge.headless.server.ai;

import forge.headless.protocol.CardStateView;

import java.util.Map;

/** How much a single permanent is worth, on its own (not as part of a
 * board) - shared between ThreatAssessor's per-board scoring and
 * TargetingStrategy's "which of these is the best/worst target" picks,
 * so a permanent is judged the same way whether it's being sized up as a
 * threat or as a removal target. */
public final class CreatureValue {
    /** Flat, board-context-free fallback for deathRole/attackRole - this
     * class has no access to opponent/my-board CardStateView lists the
     * way GenericSpellSequencer.roleValue does (and can't, without
     * threading board context through every CreatureValue.of call site -
     * ThreatAssessor, TargetingStrategy, BlockStrategy, AttackStrategy),
     * so these are deliberately rougher than the cast-time valuation,
     * not a second source of truth to keep in sync. */
    private static final Map<String, Double> FLAT_ROLE_VALUE = Map.of(
            "REMOVAL", 3.0, "SWEEPER", 2.5, "DRAW", 2.0);

    private CreatureValue() { }

    /** Map.of(...)'s immutable map rejects null keys even for a read-only
     * getOrDefault lookup (throws NPE instead of just missing) - and
     * deathRole/attackRole are null on every creature without one, by far
     * the common case, so a plain FLAT_ROLE_VALUE.getOrDefault(role, 0.0)
     * crashed almost every single decision. */
    private static double flatRoleValue(String role) {
        return role == null ? 0.0 : FLAT_ROLE_VALUE.getOrDefault(role, 0.0);
    }

    public static double of(CardStateView card) {
        if (card.typeLine == null) {
            return 0;
        }
        double anthem = card.anthemValue; // continuously active while alive - full value, no discount
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
            // A death/attack trigger makes a creature strictly better
            // than a vanilla one of the same stats *while it's alive*
            // (unlike an ETB, which is a one-time event already consumed
            // by the time a creature is just sitting resolved on the
            // board) - discounted for being conditional on actually
            // dying/attacking, since neither is guaranteed every game.
            value += flatRoleValue(card.deathRole) * 0.4;
            value += flatRoleValue(card.attackRole) * 0.6;
            value += anthem;
            return value;
        }
        if (card.typeLine.contains("Planeswalker")) {
            Integer loyalty = card.counters != null ? card.counters.get("LOYALTY") : null;
            // Loyalty is a direct, real proxy for "how much value is left
            // to extract" - floored rather than zero, since even a
            // freshly-cast walker about to activate/ultimate is a real
            // target, not nothing.
            return Math.max(3, loyalty != null ? loyalty : 0) + anthem;
        }
        if (card.typeLine.contains("Artifact") || card.typeLine.contains("Enchantment")) {
            // No oracle text to read, so this can't tell a mana rock from
            // a game-winning combo piece apart - a flat "this permanent
            // is probably doing something" value beats the previous "0,
            // completely invisible to threat assessment and targeting"
            // status quo, which made every non-creature permanent on the
            // board simply not exist as far as the AI could tell. anthem
            // is added on top since an enchantment-lord (Glorious Anthem)
            // is genuinely worth much more than a generic artifact/
            // enchantment guess once its real team-buff value is known.
            return 2.5 + anthem;
        }
        return anthem;
    }
}
