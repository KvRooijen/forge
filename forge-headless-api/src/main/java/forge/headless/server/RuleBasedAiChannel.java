package forge.headless.server;

import forge.headless.protocol.DecisionRequest;
import forge.headless.protocol.DecisionResponse;
import forge.headless.protocol.GameStateView;
import forge.headless.protocol.RemoteChannel;
import forge.headless.server.ai.HeuristicAiBrain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Successor to InProcessAiChannel - same RemoteChannel/protocol shape, but
 * decision-making is delegated to a HeuristicAiBrain's per-category
 * strategy modules instead of one flat switch statement, so each category
 * (land sequencing, threat assessment, spell sequencing, ...) can be
 * elaborated or swapped for a deck-specific version independently. See
 * forge.headless.server.ai.HeuristicAiBrain.
 *
 * Per-turn/per-cast retry bookkeeping (recentlyFailedCardIds,
 * lastAttemptedCardId, lastPayManaPrompt/Count) is deliberately kept here
 * at the orchestration layer rather than inside any one strategy - it's
 * plumbing for "don't offer the same unpayable cast forever", not a
 * decision-quality concern any strategy module should own or duplicate.
 */
public class RuleBasedAiChannel implements RemoteChannel {
    private final HeuristicAiBrain brain;

    private final Set<String> recentlyFailedCardIds = new HashSet<>();
    private String lastAttemptedCardId;
    private Integer lastTurnNumber;
    private String lastPayManaPrompt;
    private int lastPayManaCount;

    public RuleBasedAiChannel() {
        this(HeuristicAiBrain.generic());
    }

    public RuleBasedAiChannel(HeuristicAiBrain brain) {
        this.brain = brain;
    }

    @Override
    public boolean supportsBlocking() {
        return true;
    }

    @Override
    public DecisionResponse ask(DecisionRequest request) {
        DecisionResponse response = new DecisionResponse();
        response.id = request.id;
        GameStateView state = request.state;
        if (state != null) {
            resetPerTurnStateIfNewTurn(state);
        }
        List<DecisionRequest.Option> options = request.options != null ? request.options : List.of();

        switch (request.type) {
            case "MULLIGAN_KEEP":
                response.booleanValue = brain.mulliganStrategy.keepHand(state,
                        request.mulliganCardsToReturn != null ? request.mulliganCardsToReturn : 0);
                break;
            case "CONFIRM":
            case "CONFIRM_ACTION":
                response.booleanValue = brain.triggerStrategy.confirm(state, request.prompt);
                break;
            case "DECLARE_ATTACKERS":
                response.chosenIds = brain.attackStrategy.chooseAttackers(options, state, request.defenderName);
                break;
            case "DECLARE_BLOCKERS":
                response.groupChoices = brain.blockStrategy.chooseBlocks(request.groups != null ? request.groups : List.of(), state);
                break;
            case "CHOOSE_SPELL_ABILITY":
                response.chosenIds = chooseSpellAbility(options, state);
                break;
            case "PAY_MANA":
                response.chosenIds = choosePayMana(options, request.prompt);
                break;
            case "CHOOSE_LIST":
                response.chosenIds = request.targetIntent != null
                        ? brain.targetingStrategy.chooseTarget(options, request.targetIntent, request.min)
                        : brain.listChoiceStrategy.chooseFromList(options, request.min, request.listIntent);
                break;
            default:
                if (!options.isEmpty()) {
                    response.chosenIds = List.of(options.get(0).id);
                } else {
                    response.booleanValue = true;
                }
        }
        return response;
    }

    private void resetPerTurnStateIfNewTurn(GameStateView state) {
        if (lastTurnNumber == null || state.turnNumber != lastTurnNumber) {
            lastTurnNumber = state.turnNumber;
            recentlyFailedCardIds.clear();
        }
    }

    private List<String> chooseSpellAbility(List<DecisionRequest.Option> options, GameStateView state) {
        List<DecisionRequest.Option> landOptions = new ArrayList<>();
        List<DecisionRequest.Option> nonLandOptions = new ArrayList<>();
        for (DecisionRequest.Option o : options) {
            if ("Play land".equals(o.label)) {
                landOptions.add(o);
            } else {
                nonLandOptions.add(o);
            }
        }

        if (!landOptions.isEmpty()) {
            DecisionRequest.Option land = brain.landPlayStrategy.chooseLand(state, forge.headless.server.ai.AiUtils.you(state), landOptions);
            if (land != null) {
                lastAttemptedCardId = null;
                return List.of(land.id);
            }
        }

        // A successful cast removes the card from hand/command zone, so if
        // the exact card we just told the engine to cast is *still*
        // offered right now, the attempt failed before ever reaching
        // PAY_MANA (most likely: no legal target exists yet) - choosePayMana's
        // retry counter never saw this case, since payment was never even
        // reached. Without this, that card would be offered (and silently
        // fail) every priority window for the rest of the turn.
        boolean stillOffered = nonLandOptions.stream().anyMatch(o -> lastAttemptedCardId != null && lastAttemptedCardId.equals(o.cardId));
        if (lastAttemptedCardId != null && stillOffered) {
            recentlyFailedCardIds.add(lastAttemptedCardId);
        }

        List<DecisionRequest.Option> eligible = new ArrayList<>();
        for (DecisionRequest.Option o : nonLandOptions) {
            if (o.cardId == null || !recentlyFailedCardIds.contains(o.cardId)) {
                eligible.add(o);
            }
        }
        DecisionRequest.Option best = brain.spellSequencer.chooseSpell(eligible, state, recentlyFailedCardIds);
        lastAttemptedCardId = best != null ? best.cardId : null;
        return best != null ? List.of(best.id) : List.of();
    }

    private List<String> choosePayMana(List<DecisionRequest.Option> options, String prompt) {
        int attempt;
        if (prompt != null && prompt.equals(lastPayManaPrompt)) {
            attempt = ++lastPayManaCount;
        } else {
            lastPayManaPrompt = prompt;
            lastPayManaCount = 1;
            attempt = 1;
        }
        List<String> choice = brain.manaPaymentStrategy.choosePayment(options, attempt);
        if (choice.size() == 1 && choice.get(0).equals("__CANCEL__") && lastAttemptedCardId != null) {
            recentlyFailedCardIds.add(lastAttemptedCardId);
        }
        return choice;
    }
}
