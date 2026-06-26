package forge.headless.server.ai;

import java.util.List;

/** Shared first/double-strike-aware "who actually gets to deal damage"
 * resolution - used by both GenericBlockStrategy and GenericAttackStrategy,
 * which previously both assumed simultaneous damage (forge-ai audit Tier 2
 * #4). Combat step ordering matters specifically when exactly one side of
 * a fight has first or double strike and the other has neither: that side's
 * damage lands in an earlier combat step, and if it's lethal, the other
 * creature dies before ever getting a chance to deal its own damage back -
 * not a "trade" at all, a one-sided loss for whoever's missing the
 * keyword. When both sides have it (or neither does), damage really is
 * simultaneous, same as the rest of this codebase already assumes. */
public final class CombatMath {
    private CombatMath() { }

    public static boolean hasFirstOrDoubleStrike(List<String> keywords) {
        return hasKeyword(keywords, "First Strike") || hasKeyword(keywords, "Double Strike");
    }

    /** True if `attacker`'s combat-step ordering puts its damage to
     * `defender` strictly before `defender` ever deals damage back - i.e.
     * attacker has first/double strike and defender has neither. */
    public static boolean strikesFirst(List<String> attackerKeywords, List<String> defenderKeywords) {
        return hasFirstOrDoubleStrike(attackerKeywords) && !hasFirstOrDoubleStrike(defenderKeywords);
    }

    public static boolean isLethalTo(int power, boolean deathtouch, int targetToughness) {
        return power >= targetToughness || (deathtouch && power > 0);
    }

    /** True if `victim` dies before it ever gets to deal damage back to
     * `dealer` - i.e. dealer strikes first (see strikesFirst) and dealer's
     * damage is lethal to victim. When this is true, victim's own power is
     * irrelevant to the outcome: it contributes nothing, no matter how
     * large, because it's dead before the regular damage step. This is
     * the concrete case simultaneous-damage math gets wrong and calls a
     * "trade" when it's really just a clean loss for victim. */
    public static boolean diesWithoutStriking(int dealerPower, boolean dealerDeathtouch, List<String> dealerKeywords,
            int victimToughness, List<String> victimKeywords) {
        return strikesFirst(dealerKeywords, victimKeywords) && isLethalTo(dealerPower, dealerDeathtouch, victimToughness);
    }

    private static boolean hasKeyword(List<String> keywords, String name) {
        if (keywords == null) {
            return false;
        }
        for (String k : keywords) {
            if (k.contains(name)) {
                return true;
            }
        }
        return false;
    }
}
