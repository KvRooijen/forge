package forge.headless.server.ai;

import forge.headless.protocol.CardStateView;
import forge.headless.protocol.DecisionRequest;
import forge.headless.protocol.GameStateView;
import forge.headless.protocol.PlayerStateView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Two passes, same spirit as AttackStrategy's combat math - tractable
 * because Magic has no hidden battlefield information, every attacker's
 * power/toughness/keywords are fully known:
 *
 * 1. "Free" blocks first, regardless of life total: a blocker that kills
 *    the attacker without dying (a clean kill), or a mutual trade where
 *    the attacker is worth more than the blocker - both are good
 *    regardless of how much danger I'm in. Uses the *cheapest* qualifying
 *    blocker for each, saving more valuable creatures for later turns.
 * 2. Chump-blocking to survive: only if the attackers still unblocked
 *    after pass 1 would deal lethal-or-more damage, sacrifice the
 *    cheapest available creature against the biggest remaining
 *    attackers (most damage prevented per creature lost) until the
 *    remaining damage drops below my life total.
 *
 * Deliberately not modeled: gang-blocking (multiple creatures on one
 * attacker to kill something no single blocker can answer alone), first
 * strike/double strike combat-step ordering, and trample's
 * excess-damage carryover - all real simplifications, not oversights.
 */
public class GenericBlockStrategy implements BlockStrategy {
    @Override
    public Map<String, List<String>> chooseBlocks(List<DecisionRequest.Group> groups, GameStateView state) {
        PlayerStateView me = AiUtils.you(state);
        int life = me != null ? me.life : 0;

        List<DecisionRequest.Group> byThreat = new ArrayList<>(groups);
        byThreat.sort(Comparator.comparingDouble((DecisionRequest.Group g) -> CreatureValue.of(g.attacker)).reversed());

        Map<String, List<String>> result = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();

        for (DecisionRequest.Group g : byThreat) {
            DecisionRequest.Option freeBlock = bestFreeOrTradeBlocker(g.attacker, g.options, used);
            if (freeBlock != null) {
                result.put(g.id, List.of(freeBlock.id));
                used.add(freeBlock.id);
            }
        }

        List<DecisionRequest.Group> stillUnblocked = new ArrayList<>();
        for (DecisionRequest.Group g : byThreat) {
            if (!result.containsKey(g.id)) {
                stillUnblocked.add(g);
            }
        }
        int incoming = stillUnblocked.stream().mapToInt(g -> power(g.attacker)).sum();
        if (incoming >= life) {
            // Chump the biggest remaining attackers first - most damage
            // prevented per creature spent.
            stillUnblocked.sort(Comparator.comparingInt((DecisionRequest.Group g) -> power(g.attacker)).reversed());
            for (DecisionRequest.Group g : stillUnblocked) {
                if (incoming < life) {
                    break;
                }
                DecisionRequest.Option chump = cheapestAvailable(g.options, used);
                if (chump != null) {
                    result.put(g.id, List.of(chump.id));
                    used.add(chump.id);
                    incoming -= power(g.attacker);
                }
            }
        }
        return result;
    }

    /** Prefers a clean kill (blocker survives, attacker dies) over a
     * trade (both die, but the attacker was worth more) over nothing -
     * and within either category, the cheapest qualifying blocker, so a
     * small creature handles what a small creature can. */
    private DecisionRequest.Option bestFreeOrTradeBlocker(CardStateView attacker, List<DecisionRequest.Option> options, Set<String> used) {
        boolean attackerHasDeathtouch = hasKeyword(attacker.keywords, "Deathtouch");
        int attackerPower = power(attacker);
        int attackerToughness = toughness(attacker);
        double attackerValue = CreatureValue.of(attacker);

        DecisionRequest.Option bestKill = null;
        double bestKillValue = Double.POSITIVE_INFINITY;
        DecisionRequest.Option bestTrade = null;
        double bestTradeValue = Double.POSITIVE_INFINITY;

        for (DecisionRequest.Option o : options) {
            if (used.contains(o.id) || o.card == null) {
                continue;
            }
            CardStateView b = o.card;
            double blockerValue = CreatureValue.of(b);
            boolean blockerKillsAttacker = power(b) >= attackerToughness || (hasKeyword(b.keywords, "Deathtouch") && power(b) > 0);
            boolean blockerSurvives = !attackerHasDeathtouch && toughness(b) > attackerPower;
            if (blockerKillsAttacker && blockerSurvives && blockerValue < bestKillValue) {
                bestKillValue = blockerValue;
                bestKill = o;
            } else if (blockerKillsAttacker && attackerValue > blockerValue && blockerValue < bestTradeValue) {
                bestTradeValue = blockerValue;
                bestTrade = o;
            }
        }
        return bestKill != null ? bestKill : bestTrade;
    }

    private DecisionRequest.Option cheapestAvailable(List<DecisionRequest.Option> options, Set<String> used) {
        DecisionRequest.Option best = null;
        double bestValue = Double.POSITIVE_INFINITY;
        for (DecisionRequest.Option o : options) {
            if (used.contains(o.id) || o.card == null) {
                continue;
            }
            double v = CreatureValue.of(o.card);
            if (v < bestValue) {
                bestValue = v;
                best = o;
            }
        }
        return best;
    }

    private static int power(CardStateView c) {
        return c.power != null ? c.power : 0;
    }

    private static int toughness(CardStateView c) {
        return c.toughness != null ? c.toughness : 0;
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
