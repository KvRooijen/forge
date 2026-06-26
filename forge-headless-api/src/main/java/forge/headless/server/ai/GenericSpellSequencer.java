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
 * The "value" the knapsack maximizes is board-aware (see valueOf), NOT a
 * mana-cost proxy - that distinction is the whole point. Scoring every
 * spell by ~CMC (the prior version, and what the simple baseline does
 * via "cast highest CMC") makes the knapsack converge on the same plays
 * as the baseline however cleverly it packs them. valueOf instead scores
 * removal by what it kills, a sweeper by how far behind I am, etc.
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
 *
 * The CMC-only knapsack doesn't know about color contention between the
 * cards it plans together - colorAffordable only checks "a source
 * exists" per card independently, so it can plan a {B}{B} card with only
 * one black source, or two single-R-pip cards sharing one Mountain.
 * Verified via a bipartite-matching feasibility check
 * (colorFeasibleTogether) over the knapsack's chosen subset; an
 * infeasible result excludes the least valuable colored offender from
 * the candidate pool entirely and re-solves the knapsack from scratch,
 * rather than just trimming that one subset - trimming alone can empty
 * out to nothing and give up even when a cheaper, perfectly castable
 * card was sitting right there in the candidate pool the whole time.
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

        // Board context so non-creature spells can be valued by what they
        // actually accomplish right now (removal by its best target, a
        // sweeper by how far behind I am) instead of by mana cost.
        List<CardStateView> myCreatures = creaturesOf(you);
        List<CardStateView> opponentCreatures = new ArrayList<>();
        if (state != null && state.players != null) {
            for (PlayerStateView p : state.players) {
                if (p != null && !p.isYou) {
                    opponentCreatures.addAll(creaturesOf(p));
                }
            }
        }
        // How much board pressure I'm actually under right now (total
        // opponent power relative to my life) - feeds creature value's
        // power/toughness weighting: under real pressure a blocker's
        // toughness is worth more than its power (I need to survive),
        // with little pressure power matters more (I'm free to race).
        double incomingPower = 0;
        for (CardStateView c : opponentCreatures) {
            int p = c.power != null ? c.power : 0;
            if (p > 0) {
                incomingPower += p;
            }
        }
        double myLife = you != null ? you.life : 40;
        double pressureRatio = incomingPower / Math.max(1.0, myLife);

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

        return planBestSubset(nonLandOptions, castableIds, excludedCardIds, manaSources, availableMana, totalManaSources,
                opponentCreatures, myCreatures, pressureRatio);
    }

    private List<CardStateView> creaturesOf(PlayerStateView p) {
        List<CardStateView> out = new ArrayList<>();
        if (p == null || p.battlefield == null) {
            return out;
        }
        for (CardStateView c : p.battlefield) {
            if (c.typeLine != null && c.typeLine.contains("Creature")) {
                out.add(c);
            }
        }
        return out;
    }

    private DecisionRequest.Option planBestSubset(List<DecisionRequest.Option> nonLandOptions, Map<String, CardStateView> castableIds,
            Set<String> excludedCardIds, List<CardStateView> manaSources, int availableMana, int totalManaSources,
            List<CardStateView> opponentCreatures, List<CardStateView> myCreatures, double pressureRatio) {
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
                // Purely informational - see DecisionRequest.Option.castable's
                // javadoc - doesn't change anything else here.
                o.castable = false;
                continue;
            }
            double value = valueOf(o, card, totalManaSources, opponentCreatures, myCreatures, pressureRatio);
            candidates.add(o);
            costs.add(cmc);
            values.add(value);
            // Purely informational - see DecisionRequest.Option.value's
            // javadoc - doesn't feed back into the knapsack itself.
            o.value = value;
            o.castable = true;
        }
        if (candidates.isEmpty()) {
            return null;
        }
        int n = candidates.size();

        // The CMC-only knapsack can plan a card (or combination of cards)
        // that's individually colorAffordable (a source merely *exists*
        // per needed color) but isn't really jointly payable - e.g. a
        // single {B}{B} card with only one black source on the
        // battlefield, or two single-R-pip cards sharing one Mountain.
        // If the optimal subset turns out infeasible, the fix is NOT to
        // just shrink that one subset and give up when it hits empty -
        // that throws away perfectly castable cheaper cards that simply
        // weren't part of *this* optimal combination (e.g. a 5-mana
        // creature needing a color we lack two of, sitting next to a
        // 2-mana creature that's completely fine, but never reconsidered
        // once the 5-drop's subset got trimmed to nothing). Instead,
        // permanently exclude the least valuable colored offender from
        // the *candidate pool* and re-solve the knapsack from scratch -
        // repeating until the freshly re-optimized subset is itself
        // jointly payable, or no candidates remain.
        Set<Integer> excludedIndices = new java.util.HashSet<>();
        while (true) {
            List<Integer> chosen = solveKnapsack(n, costs, values, availableMana, excludedIndices);
            if (chosen.isEmpty()) {
                return null;
            }
            if (colorFeasibleTogether(chosen, candidates, castableIds, manaSources)) {
                // Cast the most expensive chosen item first - mostly a
                // matter of sequencing sanity (bigger effects landing
                // before smaller ones), not load-bearing for the total
                // value achieved.
                int bestIdx = chosen.get(0);
                for (int idx : chosen) {
                    if (costs.get(idx) > costs.get(bestIdx)) {
                        bestIdx = idx;
                    }
                }
                return candidates.get(bestIdx);
            }

            int worst = -1;
            for (int idx : chosen) {
                CardStateView card = castableIds.get(candidates.get(idx).cardId);
                if (ManaUtils.colorPipCounts(card.manaCost).isEmpty()) {
                    continue; // excluding a colorless card can't fix color contention
                }
                if (worst == -1 || values.get(idx) < values.get(worst)) {
                    worst = idx;
                }
            }
            if (worst == -1) {
                return null; // nothing colored left to exclude - shouldn't happen given the per-card gate already passed, but don't loop forever
            }
            excludedIndices.add(worst);
        }
    }

    /** 0/1 knapsack restricted to candidates not in excludedIndices -
     * excluded items are simply never affordable in the DP, which keeps
     * this a plain re-solve rather than needing a separate code path. */
    private List<Integer> solveKnapsack(int n, List<Integer> costs, List<Double> values, int availableMana, Set<Integer> excludedIndices) {
        double[][] dp = new double[n + 1][availableMana + 1];
        for (int i = 1; i <= n; i++) {
            int cost = costs.get(i - 1);
            double value = values.get(i - 1);
            boolean excluded = excludedIndices.contains(i - 1);
            for (int c = 0; c <= availableMana; c++) {
                dp[i][c] = dp[i - 1][c];
                if (!excluded && cost <= c) {
                    dp[i][c] = Math.max(dp[i][c], dp[i - 1][c - cost] + value);
                }
            }
        }
        List<Integer> chosen = new ArrayList<>();
        int c = availableMana;
        for (int i = n; i >= 1; i--) {
            if (dp[i][c] != dp[i - 1][c]) {
                chosen.add(i - 1);
                c -= costs.get(i - 1);
            }
        }
        return chosen;
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

    /** Bipartite matching: one node per required colored pip across every
     * chosen card (a {R}{R} cost contributes two separate "R" pips, not
     * one), one node per currently-untapped mana source, an edge wherever
     * a source can produce that color. Feasible iff every pip can be
     * matched to a distinct source (Kuhn's algorithm / augmenting paths -
     * trivial at this scale, a handful of pips against a handful of
     * sources). */
    private boolean colorFeasibleTogether(List<Integer> chosenIndices, List<DecisionRequest.Option> candidates,
            Map<String, CardStateView> castableIds, List<CardStateView> manaSources) {
        List<String> pips = new ArrayList<>();
        for (int idx : chosenIndices) {
            CardStateView card = castableIds.get(candidates.get(idx).cardId);
            for (Map.Entry<String, Integer> e : ManaUtils.colorPipCounts(card.manaCost).entrySet()) {
                for (int i = 0; i < e.getValue(); i++) {
                    pips.add(e.getKey());
                }
            }
        }
        if (pips.isEmpty()) {
            return true;
        }
        List<CardStateView> sources = manaSources.stream().filter(s -> !s.tapped).toList();
        int[] matchedTo = new int[sources.size()];
        java.util.Arrays.fill(matchedTo, -1);
        for (int i = 0; i < pips.size(); i++) {
            if (!tryAssignPip(i, pips, sources, matchedTo, new boolean[sources.size()])) {
                return false;
            }
        }
        return true;
    }

    private boolean tryAssignPip(int pipIdx, List<String> pips, List<CardStateView> sources, int[] matchedTo, boolean[] visited) {
        String color = pips.get(pipIdx);
        for (int j = 0; j < sources.size(); j++) {
            if (visited[j]) {
                continue;
            }
            CardStateView source = sources.get(j);
            if (source.producedColors == null || !source.producedColors.contains(color)) {
                continue;
            }
            visited[j] = true;
            if (matchedTo[j] == -1 || tryAssignPip(matchedTo[j], pips, sources, matchedTo, visited)) {
                matchedTo[j] = pipIdx;
                return true;
            }
        }
        return false;
    }

    /**
     * What a single castable spell is worth *right now*, given the board -
     * the heart of "what's actually good to play". The previous version
     * scored creatures as (power+toughness)*0.5 and every non-creature as
     * its CMC, which made the whole knapsack a proxy for "spend the most
     * mana on the biggest thing" - i.e. the same behaviour as the simple
     * baseline's "cast highest CMC". This values a spell by what it
     * accomplishes against the current board instead:
     *
     * - ramp (a mana producer, or a RAMP-classified spell): worth most
     *   while mana is the bottleneck, tapering to ~0 once there's plenty
     * - creature: power+toughness, but the *split* between them shifts
     *   with board pressure (see pressureRatio) - under real pressure a
     *   blocker's toughness matters more than its power (I need to
     *   survive), with little pressure power matters more (free to
     *   race) - plus a flat per-body bonus so two on-curve creatures
     *   beat one bigger one on a stat tie (more blockers, resilient to a
     *   single removal spell, better combat math than one big body)
     * - REMOVAL: worth as much as the best thing it can kill right now -
     *   and almost nothing cast into a board with nothing worth killing,
     *   so the AI stops blowing removal on an empty board
     * - SWEEPER: (opponent board value - my board value), so it's a
     *   strong play when behind on board and a *negative*-value one (never
     *   chosen) when ahead
     * - DRAW: a steady board-independent refuel
     * - anything still unclassified: the CMC proxy, unchanged
     *
     * A creature's own "when this enters" trigger (CardStateView.etbRole
     * - see RemotePlayerController.classifyEtbRole) is scored the same
     * way and added *on top of* its stat value, not in place of it - a
     * removal spell stapled to a body is strictly better than the same
     * removal spell alone, since it leaves a permanent behind too.
     * Without this, a vanilla 4/4 and a 4/4 "when this enters, destroy
     * target creature" scored identically (stats are all CreatureValue
     * and the old version of this method ever looked at) - a real gap,
     * since exactly this pattern (an effect stapled to a body) is one of
     * the most common power sources in modern Magic, Commander
     * especially, not a marginal case.
     */
    private double valueOf(DecisionRequest.Option o, CardStateView card, int totalManaSources,
            List<CardStateView> opponentCreatures, List<CardStateView> myCreatures, double pressureRatio) {
        double value = 0;
        String role = o.spellRole;

        boolean isRamp = card.producesMana || "RAMP".equals(role);
        if (isRamp) {
            value += Math.max(0, 6 - totalManaSources) * 2.0;
        }

        if (card.typeLine != null && card.typeLine.contains("Creature")) {
            int power = card.power != null ? card.power : 0;
            int toughness = card.toughness != null ? card.toughness : 0;
            // Weights still sum to 1 (same overall scale as the old flat
            // 0.5/0.5 split), just no longer fixed: 0 pressure -> 0.35
            // toughness/0.65 power (free to race), heavy pressure caps at
            // 0.75 toughness/0.25 power (need to survive, not attack).
            double defenseWeight = Math.min(0.75, Math.max(0.35, 0.5 + pressureRatio * 0.3));
            double powerWeight = 1.0 - defenseWeight;
            // Keyword value is additive on top, not a multiplier on the
            // weighted sum - see CombatKeywords.keywordValue's javadoc;
            // same reasoning as CreatureValue's identical change.
            value += power * powerWeight + toughness * defenseWeight;
            value += CombatKeywords.keywordValue(power, toughness, card.keywords);
            // Flat per-body bonus, independent of stats: on a near-tie in
            // raw stats, two separate creatures beat one bigger one (more
            // blockers, survives a single removal spell, better combat
            // math than one all-in body).
            value += 1.0;
            value += roleValue(card.etbRole, opponentCreatures, myCreatures);
            // Death/attack triggers are conditional (not guaranteed the
            // way an ETB is - they depend on the creature actually dying
            // or being attacked with), so their board-aware value is
            // discounted before adding, same reasoning as
            // CreatureValue's flatter version of this same idea.
            value += roleValue(card.deathRole, opponentCreatures, myCreatures) * 0.4;
            value += roleValue(card.attackRole, opponentCreatures, myCreatures) * 0.6;
            // Static team-buff value (lords/anthems) is continuously
            // active while alive - full value, no discount, same as ETB.
            value += card.anthemValue;
            return value; // creature body + every real effect it has is the whole story
        }

        if (!isRamp) {
            if (role != null) {
                value += roleValue(role, opponentCreatures, myCreatures);
            } else {
                // Planeswalker / enchantment engine / pump / unclassified -
                // no reliable effect signal, fall back to CMC as a rough
                // proxy rather than treating it as worthless, same as before.
                value += ManaUtils.manaValue(card.manaCost);
            }
            // A noncreature anthem (Glorious Anthem) is real, additive
            // team-buff value on top of whatever else this spell does -
            // same reasoning as the creature branch above.
            value += card.anthemValue;
        }
        return value;
    }

    /** The REMOVAL/SWEEPER/DRAW board-aware scoring shared between "what
     * does casting this spell do" and "what does this creature's own ETB
     * trigger do" - delegates to SpellRoleValue so modal-mode selection
     * (RemotePlayerController.chooseModeForAbility) values the exact same
     * effect identically. Returns 0 for RAMP (handled separately via the
     * mana-source-count taper above, not here) and for null/unclassified. */
    private double roleValue(String role, List<CardStateView> opponentCreatures, List<CardStateView> myCreatures) {
        return SpellRoleValue.of(role, opponentCreatures, myCreatures);
    }
}
