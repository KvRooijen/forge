package forge.headless.server.ai;

import forge.headless.protocol.CardStateView;
import forge.headless.protocol.DecisionRequest;
import forge.headless.protocol.GameStateView;
import forge.headless.protocol.PlayerStateView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Two real pieces of combat math instead of "attack with anything that
 * has power > 0": a lethal-line check (go all-in when the numbers say
 * this attack can just win outright), and per-creature safe/favorable
 * filtering otherwise (don't throw a creature into an obvious bad block
 * for nothing). Magic has no hidden battlefield information - every
 * opponent's creatures, power, toughness, and keywords are fully known
 * at declare-attackers time - so this is tractable straight from
 * CardStateView, no guessing required.
 *
 * Both pieces deliberately stay conservative rather than fully solving
 * combat: hand contents (combat tricks, instant-speed removal) are
 * genuinely hidden information and aren't pretended away, trample's
 * excess-damage carryover isn't modeled (a trampler is treated as a
 * plain ground attacker), and the lethal check assumes the defender
 * blocks optimally (biggest unavoidable attackers first) rather than
 * searching every possible block assignment.
 */
public class GenericAttackStrategy implements AttackStrategy {
    @Override
    public List<String> chooseAttackers(List<DecisionRequest.Option> options, GameStateView state, String defenderName) {
        Map<String, CardStateView> byId = AiUtils.cardsById(state);
        PlayerStateView defender = findPlayer(state, defenderName);

        if (defender == null) {
            // No resolvable defender info - fall back to the original
            // simple rule rather than guessing at combat math blind.
            List<String> chosen = new ArrayList<>();
            for (DecisionRequest.Option o : options) {
                CardStateView card = o.cardId != null ? byId.get(o.cardId) : null;
                if (card == null || (card.power != null ? card.power : 0) > 0) {
                    chosen.add(o.id);
                }
            }
            return chosen;
        }

        List<CardStateView> blockers = new ArrayList<>();
        if (defender.battlefield != null) {
            for (CardStateView c : defender.battlefield) {
                if (!c.tapped && c.typeLine != null && c.typeLine.contains("Creature")) {
                    blockers.add(c);
                }
            }
        }

        List<CardStateView> attackers = new ArrayList<>();
        for (DecisionRequest.Option o : options) {
            CardStateView card = o.cardId != null ? byId.get(o.cardId) : null;
            if (card != null && (card.power == null || card.power <= 0)) {
                continue;
            }
            attackers.add(card);
        }

        if (isLethal(attackers, blockers, defender.life)) {
            return options.stream().map(o -> o.id).toList();
        }

        List<String> chosen = new ArrayList<>();
        for (DecisionRequest.Option o : options) {
            CardStateView card = o.cardId != null ? byId.get(o.cardId) : null;
            if (card == null) {
                continue;
            }
            int power = card.power != null ? card.power : 0;
            if (power <= 0) {
                continue;
            }
            if (isSafeOrFavorable(card, blockers)) {
                chosen.add(o.id);
            }
        }
        return chosen;
    }

    /** Conservative: sums damage that's guaranteed through (evasive
     * attackers the defender literally cannot block) plus, if I have
     * more "blockable" attackers than they have blockers, the smallest
     * excess attackers' power (a defender blocking optimally would let
     * the least damage through, not the most). */
    private boolean isLethal(List<CardStateView> attackers, List<CardStateView> blockers, int defenderLife) {
        double guaranteed = 0;
        List<CardStateView> groundAttackers = new ArrayList<>();
        for (CardStateView a : attackers) {
            if (isGuaranteedUnblocked(a, blockers)) {
                guaranteed += a.power != null ? a.power : 0;
            } else {
                groundAttackers.add(a);
            }
        }
        int excess = groundAttackers.size() - blockers.size();
        if (excess > 0) {
            groundAttackers.sort(Comparator.comparingInt(c -> c.power != null ? c.power : 0));
            for (int i = 0; i < excess; i++) {
                guaranteed += groundAttackers.get(i).power != null ? groundAttackers.get(i).power : 0;
            }
        }
        return guaranteed >= defenderLife;
    }

    private boolean isGuaranteedUnblocked(CardStateView attacker, List<CardStateView> blockers) {
        if (hasKeyword(attacker.keywords, "Unblockable")) {
            return true;
        }
        if (hasKeyword(attacker.keywords, "Flying")) {
            return blockers.stream().noneMatch(b -> hasKeyword(b.keywords, "Flying") || hasKeyword(b.keywords, "Reach"));
        }
        if (hasKeyword(attacker.keywords, "Menace")) {
            return blockers.size() < 2;
        }
        return false;
    }

    /** An attack is unsafe only if the defender has a clean answer: a
     * blocker that kills this attacker without dying itself. Everything
     * else (no blockers, a trade, a block that doesn't kill mine, mutual
     * destruction) is fine to attack into - "favorable or neutral", not
     * just strictly-winning trades. */
    private boolean isSafeOrFavorable(CardStateView attacker, List<CardStateView> blockers) {
        if (isGuaranteedUnblocked(attacker, blockers) || blockers.isEmpty()) {
            return true;
        }
        boolean iHaveDeathtouch = hasKeyword(attacker.keywords, "Deathtouch");
        int myPower = attacker.power != null ? attacker.power : 0;
        int myToughness = attacker.toughness != null ? attacker.toughness : 0;
        for (CardStateView b : blockers) {
            int bPower = b.power != null ? b.power : 0;
            int bToughness = b.toughness != null ? b.toughness : 0;
            boolean blockerKillsMine = bPower >= myToughness || (hasKeyword(b.keywords, "Deathtouch") && bPower > 0);
            // Deathtouch on MY side means any damage I deal is lethal, so
            // the blocker never "survives" blocking me regardless of
            // toughness - at worst this is a trade, never a clean loss.
            boolean blockerSurvives = !iHaveDeathtouch && bToughness > myPower;
            if (blockerKillsMine && blockerSurvives) {
                return false;
            }
        }
        return true;
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

    private PlayerStateView findPlayer(GameStateView state, String name) {
        if (state == null || state.players == null || name == null) {
            return null;
        }
        for (PlayerStateView p : state.players) {
            if (name.equals(p.name)) {
                return p;
            }
        }
        return null;
    }
}
