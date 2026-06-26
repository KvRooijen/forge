package forge.headless.server.ai;

import java.util.List;

/** Shared "how much does this keyword set add to a creature's real combat
 * impact" scoring - used both for judging how scary an opponent's board
 * is (ThreatAssessor/CreatureValue) and how good a creature is to cast in
 * the first place (SpellSequencer). The same creature should be valued
 * consistently whether it's mine or theirs.
 *
 * Modeled on forge-ai's CreatureEvaluator (forge-ai/.../CreatureEvaluator.java),
 * which scores ~30 keywords individually rather than one blanket
 * "evasive/scary" multiplier - the previous version of this class. A
 * vanilla 4/4 and a deathtouch-vigilance-hexproof 4/4 are wildly
 * different cards; collapsing that into a single +0.5-per-category
 * multiplier was real lost signal, the same kind of gap as the ETB/death/
 * attack-trigger work, just for keywords instead of triggers. Point
 * values are forge-ai's, scaled down by roughly 10x to fit this codebase's
 * existing 0-15ish CreatureValue range rather than forge-ai's 0-100+ scale.
 *
 * Deliberately scoped to boolean/presence keywords only - magnitude-
 * bearing ones (Rampage N, Toxic N, Afflict N, Bushido N, Flanking N) are
 * skipped rather than guessed at, since CardStateView only carries each
 * keyword's full *display string* (Card.getUnhiddenKeywords().toString()),
 * not a parsed magnitude, and these are rare in modern Commander decks
 * anyway (mostly older-block mechanics) - a much smaller miss than the
 * common evasion/protection/combat keywords below. */
public final class CombatKeywords {
    private CombatKeywords() { }

    /** Additive value delta (not a multiplier) from this creature's
     * keyword set, given its power/toughness - added directly onto a
     * value computation already keyed off power/toughness, the same way
     * forge-ai's CreatureEvaluator adds onto its own power/toughness
     * base. */
    public static double keywordValue(int power, int toughness, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return 0;
        }
        double value = 0;

        boolean unblockable = has(keywords, "can't be blocked") || has(keywords, "Unblockable");
        boolean flying = has(keywords, "Flying");
        boolean horsemanship = has(keywords, "Horsemanship");
        if (unblockable) {
            value += power * 1.0;
        } else if (flying) {
            value += power * 1.0;
        } else if (horsemanship) {
            value += power * 1.0;
        } else {
            // Partial evasion - only matters if the opponent might lack
            // the specific answer, so weighted well below true evasion.
            if (has(keywords, "can't be blocked except by creatures with flying")
                    || has(keywords, "Fear") || has(keywords, "Intimidate")) {
                value += power * 0.6;
            }
            if (has(keywords, "Menace")) {
                value += power * 0.4;
            }
            if (has(keywords, "Skulk")) {
                value += power * 0.3;
            }
        }

        if (power > 0) {
            if (has(keywords, "Double Strike")) {
                value += 1.0 + power * 1.5;
            } else if (has(keywords, "First Strike")) {
                value += 1.0 + power * 0.5;
            }
            if (has(keywords, "Deathtouch")) {
                value += 2.5;
            }
            if (has(keywords, "Lifelink")) {
                value += power * 1.0;
            }
            if (power > 1 && has(keywords, "Trample")) {
                value += (power - 1) * 0.5;
            }
            if (has(keywords, "Vigilance")) {
                value += power * 0.5 + toughness * 0.5;
            }
            if (has(keywords, "Infect")) {
                value += power * 1.5;
            } else if (has(keywords, "Wither")) {
                value += power * 1.0;
            }
        }

        if (has(keywords, "Reach") && !flying) {
            value += 0.5;
        }

        if (has(keywords, "Indestructible")) {
            value += 7.0;
        }
        if (has(keywords, "Hexproof")) {
            value += 3.5;
        } else if (has(keywords, "Shroud")) {
            value += 3.0;
        } else if (has(keywords, "Ward")) {
            value += 1.0;
        }
        if (has(keywords, "Protection from")) {
            value += 2.0;
        }

        if (has(keywords, "Defender") || has(keywords, "can't attack")) {
            value -= power * 0.9 + 4.0;
        } else if (has(keywords, "can't block")) {
            value -= 1.0;
        }

        return value;
    }

    private static boolean has(List<String> keywords, String text) {
        for (String k : keywords) {
            if (k.contains(text)) {
                return true;
            }
        }
        return false;
    }
}
