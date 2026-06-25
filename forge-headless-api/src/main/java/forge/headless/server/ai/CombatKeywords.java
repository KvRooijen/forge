package forge.headless.server.ai;

import java.util.List;
import java.util.Set;

/** Shared "how much does this keyword set add to a creature's real combat
 * impact" scoring - used both for judging how scary an opponent's board
 * is (ThreatAssessor) and how good a creature is to cast in the first
 * place (SpellSequencer). The same creature should be valued consistently
 * whether it's mine or theirs. */
public final class CombatKeywords {
    private static final Set<String> EVASIVE = Set.of(
            "Flying", "Trample", "Menace", "Shadow", "Horsemanship", "Unblockable", "Fear", "Intimidate");
    private static final Set<String> SCARY = Set.of("Deathtouch", "Double Strike", "Infect", "Lifelink");

    private CombatKeywords() { }

    /** 1.0 base, +0.5 per evasive keyword present, +0.5 per "scary"
     * (deathtouch/double strike/infect/lifelink) keyword present. */
    public static double impactMultiplier(List<String> keywords) {
        double multiplier = 1.0;
        if (keywords == null) {
            return multiplier;
        }
        for (String kw : keywords) {
            if (matchesAny(kw, EVASIVE)) {
                multiplier += 0.5;
            }
            if (matchesAny(kw, SCARY)) {
                multiplier += 0.5;
            }
        }
        return multiplier;
    }

    private static boolean matchesAny(String keyword, Set<String> set) {
        for (String k : set) {
            if (keyword.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
