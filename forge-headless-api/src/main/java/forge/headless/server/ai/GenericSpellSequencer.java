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
 * Picks the best-VALUE affordable play, not the most-expensive one - in
 * bracket-2 Commander, developing your own board efficiently usually
 * matters more than interaction, and "always cast whatever costs the
 * most mana" doesn't actually optimize for that. Since the engine calls
 * this again after every successful cast (mana decreasing each time), the
 * natural effect of always taking the best efficiency-per-mana play is to
 * spend a turn's mana across several efficient plays rather than burning
 * it all on one expensive-but-unremarkable card.
 *
 * Value signals available from CardStateView (no oracle text, so nothing
 * deck-specific like "this draws cards" is detectable):
 *  - ramp/mana rocks (producesMana): weighted heavily early (low land
 *    count), tapering off once mana is no longer the bottleneck.
 *  - creatures: power+toughness scaled by the same evasive/scary keyword
 *    multiplier ThreatAssessor uses for the opponents' boards - a card
 *    that would be a big threat in someone else's hands is also a good
 *    one to play in mine.
 *  - other permanents (artifacts/enchantments with no mana ability):
 *    no real signal available, falls back to CMC as a rough impact
 *    proxy - an acknowledged blind spot, not a considered judgment that
 *    they're worth exactly their CMC.
 * Commander gets a hard priority override, same as before - casting your
 * commander as soon as affordable is almost always correct in Commander
 * regardless of what else is available.
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
        List<CardStateView> battlefieldLands = new ArrayList<>();
        if (you != null && you.battlefield != null) {
            for (CardStateView c : you.battlefield) {
                if (c.typeLine != null && c.typeLine.contains("Land")) {
                    battlefieldLands.add(c);
                }
            }
        }
        int numLands = battlefieldLands.size();

        DecisionRequest.Option best = null;
        double bestEfficiency = Double.NEGATIVE_INFINITY;
        boolean bestIsCommander = false;
        for (DecisionRequest.Option o : nonLandOptions) {
            String cardId = o.cardId;
            if (cardId == null || !castableIds.containsKey(cardId) || excludedCardIds.contains(cardId)) {
                continue;
            }
            CardStateView card = castableIds.get(cardId);
            int cmc = ManaUtils.manaValue(card.manaCost);
            if (cmc > numLands) {
                continue;
            }
            boolean colorOk = true;
            for (String color : ManaUtils.colorsInCost(card.manaCost)) {
                boolean hasSource = battlefieldLands.stream()
                        .anyMatch(land -> land.producedColors != null && land.producedColors.contains(color));
                if (!hasSource) {
                    colorOk = false;
                    break;
                }
            }
            if (!colorOk) {
                continue;
            }

            // Getting the commander onto the battlefield as soon as
            // affordable outranks plain value/efficiency, but a
            // higher-priority commander option found later still wins
            // over an earlier non-commander pick.
            boolean isCommander = card.isCommander;
            if (best != null && bestIsCommander && !isCommander) {
                continue;
            }
            if (isCommander && !bestIsCommander) {
                best = o;
                bestIsCommander = true;
                continue;
            }

            double efficiency = valueOf(card, numLands) / Math.max(1, cmc);
            if (efficiency > bestEfficiency) {
                bestEfficiency = efficiency;
                best = o;
                bestIsCommander = isCommander;
            }
        }
        return best;
    }

    private double valueOf(CardStateView card, int numLands) {
        double value = 0;
        if (card.producesMana) {
            // Ramp is worth the most when mana is actually the
            // bottleneck (early), tapering to ~0 once there's already
            // plenty of it - a 7th mana rock doesn't accelerate anything
            // that isn't already accelerated.
            value += Math.max(0, 6 - numLands) * 2.0;
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
