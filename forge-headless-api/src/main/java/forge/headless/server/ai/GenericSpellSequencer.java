package forge.headless.server.ai;

import forge.headless.protocol.CardStateView;
import forge.headless.protocol.DecisionRequest;
import forge.headless.protocol.GameStateView;
import forge.headless.protocol.PlayerStateView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans the best-VALUE *subset* of currently-castable cards for the mana
 * actually available right now, rather than greedily taking the best
 * value-per-mana card one at a time. Greedy efficiency can strand a much
 * better total turn: with 6 mana, a 2-cost card at efficiency 2.0 and a
 * 5-cost card at efficiency 1.5 (value 4 and 7.5 respectively), greedy
 * takes the 2-cost first, leaves 4 mana, and the 5-cost no longer fits -
 * total value 4, when casting the 5-cost alone would have been 7.5. A 0/1
 * knapsack (maximize total value, budget = mana available) finds the
 * actual best combination instead.
 *
 * Re-solved from scratch on every call rather than planned once and
 * remembered: after casting one of the chosen items, the available mana
 * drops by exactly its cost and it drops out of the candidate list, which
 * by the knapsack's own optimal-substructure property reproduces "the
 * rest of the original plan" as the new optimum - no separate plan object
 * needs to survive between calls, and the result can't go stale if
 * something else about the board changes mid-turn.
 *
 * Two related affordability bugs fixed along the way: the previous
 * version (and the simple heuristic AI before it) used the *total* land
 * count as the mana budget, which doesn't account for lands already
 * tapped to pay for an earlier cast this same turn - and counted only
 * lands, never mana rocks, as mana sources at all. Both are corrected
 * here by deriving the budget from currently-untapped CardStateView.tapped
 * across every producesMana permanent, not just untapped lands.
 */
public class GenericSpellSequencer implements SpellSequencer {
    @Override
    public DecisionRequest.Option chooseSpell(List<DecisionRequest.Option> nonLandOptions, GameStateView state, Set<String> excludedCardIds) {
        PlayerStateView you = AiUtils.you(state);
        Map<String, CardStateView> castableIds = new HashMap<>();
        if (you != null) {
            for (CardStateView c : you.hand != null ? you.hand : List.<CardStateView>of()) {
                castableIds.put(c.id, c);
            }
            for (CardStateView c : you.commandZone != null ? you.commandZone : List.<CardStateView>of()) {
                castableIds.put(c.id, c);
            }
        }
        List<CardStateView> manaSources = new ArrayList<>();
        if (you != null && you.battlefield != null) {
            for (CardStateView c : you.battlefield) {
                if (c.producesMana) {
                    manaSources.add(c);
                }
            }
        }
        // Total mana sources (tapped or not) for the ramp-bonus tapering
        // calculation in valueOf - "how much do I still need more ramp"
        // is about overall game progress, not what happens to be tapped
        // this instant.
        int totalManaSources = manaSources.size();
        int availableMana = (int) manaSources.stream().filter(c -> !c.tapped).count();

        // Commander still gets a hard priority override - casting it as
        // soon as affordable outranks any value/efficiency comparison.
        for (DecisionRequest.Option o : nonLandOptions) {
            String cardId = o.cardId;
            if (cardId == null || !castableIds.containsKey(cardId) || excludedCardIds.contains(cardId)) {
                continue;
            }
            CardStateView card = castableIds.get(cardId);
            if (!card.isCommander) {
                continue;
            }
            if (ManaUtils.manaValue(card.manaCost) > availableMana) {
                continue;
            }
            if (colorAffordable(card, manaSources)) {
                return o;
            }
        }

        return planBestSubset(nonLandOptions, castableIds, excludedCardIds, manaSources, availableMana, totalManaSources);
    }

    private DecisionRequest.Option planBestSubset(List<DecisionRequest.Option> nonLandOptions, Map<String, CardStateView> castableIds,
            Set<String> excludedCardIds, List<CardStateView> manaSources, int availableMana, int totalManaSources) {
        List<DecisionRequest.Option> candidates = new ArrayList<>();
        List<Integer> costs = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (DecisionRequest.Option o : nonLandOptions) {
            String cardId = o.cardId;
            if (cardId == null || !castableIds.containsKey(cardId) || excludedCardIds.contains(cardId)) {
                continue;
            }
            CardStateView card = castableIds.get(cardId);
            int cmc = ManaUtils.manaValue(card.manaCost);
            if (cmc > availableMana || !colorAffordable(card, manaSources)) {
                continue;
            }
            candidates.add(o);
            costs.add(cmc);
            values.add(valueOf(card, totalManaSources));
        }
        if (candidates.isEmpty()) {
            return null;
        }

        // 0/1 knapsack: dp[i][c] = best total value using only the first
        // i candidates with total cost <= c.
        int n = candidates.size();
        double[][] dp = new double[n + 1][availableMana + 1];
        for (int i = 1; i <= n; i++) {
            int cost = costs.get(i - 1);
            double value = values.get(i - 1);
            for (int c = 0; c <= availableMana; c++) {
                dp[i][c] = dp[i - 1][c];
                if (cost <= c) {
                    dp[i][c] = Math.max(dp[i][c], dp[i - 1][c - cost] + value);
                }
            }
        }

        // Backtrack to recover which candidates the optimal plan actually
        // includes - any single one of them is a correct next move, since
        // re-solving after casting it reproduces the rest of the plan.
        List<Integer> chosen = new ArrayList<>();
        int c = availableMana;
        for (int i = n; i >= 1; i--) {
            if (dp[i][c] != dp[i - 1][c]) {
                chosen.add(i - 1);
                c -= costs.get(i - 1);
            }
        }
        if (chosen.isEmpty()) {
            return null;
        }
        // Cast the most expensive chosen item first - mostly a matter of
        // sequencing sanity (bigger effects landing before smaller ones),
        // not load-bearing for the total value achieved.
        int bestIdx = chosen.get(0);
        for (int idx : chosen) {
            if (costs.get(idx) > costs.get(bestIdx)) {
                bestIdx = idx;
            }
        }
        return candidates.get(bestIdx);
    }

    private boolean colorAffordable(CardStateView card, List<CardStateView> manaSources) {
        for (String color : ManaUtils.colorsInCost(card.manaCost)) {
            boolean hasUntappedSource = manaSources.stream()
                    .anyMatch(s -> !s.tapped && s.producedColors != null && s.producedColors.contains(color));
            if (!hasUntappedSource) {
                return false;
            }
        }
        return true;
    }

    private double valueOf(CardStateView card, int totalManaSources) {
        double value = 0;
        if (card.producesMana) {
            // Ramp is worth the most when mana is actually the
            // bottleneck (early), tapering to ~0 once there's already
            // plenty of it - a 7th mana source doesn't accelerate
            // anything that isn't already accelerated.
            value += Math.max(0, 6 - totalManaSources) * 2.0;
        }
        if (card.typeLine != null && card.typeLine.contains("Creature")) {
            int power = card.power != null ? card.power : 0;
            int toughness = card.toughness != null ? card.toughness : 0;
            value += (power + toughness) * 0.5 * CombatKeywords.impactMultiplier(card.keywords);
        } else if (!card.producesMana) {
            // Artifact/enchantment/sorcery/instant with no mana ability
            // and no stats to read - no real signal available without
            // oracle text, so this falls back to CMC as a rough proxy
            // rather than treating it as worthless.
            value += ManaUtils.manaValue(card.manaCost);
        }
        return value;
    }
}
