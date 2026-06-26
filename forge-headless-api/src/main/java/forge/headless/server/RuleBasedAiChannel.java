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
import java.util.UUID;

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
    private final String seatName;
    // One per channel instance, which is one per seat per game (see
    // AiPlayerType.RULE_BASED_V2 / BatchRunner.playGame) - scopes
    // DecisionLogger records to "this seat, this game" without needing a
    // game-wide id threaded down from BatchRunner.
    private final String seatChannelId = UUID.randomUUID().toString();

    private final Set<String> recentlyFailedCardIds = new HashSet<>();
    private String lastAttemptedCardId;
    private Integer lastTurnNumber;
    private String lastPayManaPrompt;
    private int lastPayManaCount;

    public RuleBasedAiChannel() {
        this(HeuristicAiBrain.generic(), null);
    }

    public RuleBasedAiChannel(HeuristicAiBrain brain) {
        this(brain, null);
    }

    public RuleBasedAiChannel(HeuristicAiBrain brain, String seatName) {
        this.brain = brain;
        this.seatName = seatName;
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
        if (DecisionLogger.isEnabled()) {
            enrichOptionValuesForLogging(request);
            DecisionLogger.log(seatChannelId, seatName, request, response);
        }
        return response;
    }

    /**
     * Backfills DecisionRequest.Option.value with CreatureValue wherever a
     * strategy didn't already set a richer value itself (e.g.
     * GenericSpellSequencer's board-aware spell value) - covers attack/
     * block candidates and targeting/discard-style CHOOSE_LIST options for
     * free, since they all already key their own ranking off
     * CreatureValue.of(...) for this exact card. DECLARE_ATTACKERS options
     * specifically carry only a cardId, not the full CardStateView (a
     * deliberately lighter wire payload sent on every single attack
     * decision, logging or not - see RemotePlayerController.declareAttackers),
     * so for those this falls back to looking the id up in the state's own
     * battlefield, same as GenericAttackStrategy's internal byId map
     * already does for ranking. Only touches options, never changes any
     * decision - this exists purely so logged records show the value the
     * AI was actually weighing, not just what got picked. Skipped entirely
     * when logging is off.
     */
    private static void enrichOptionValuesForLogging(DecisionRequest request) {
        java.util.Map<String, forge.headless.protocol.CardStateView> byId = cardsById(request.state);
        enrichList(request.options, byId);
        if (request.groups != null) {
            for (DecisionRequest.Group g : request.groups) {
                enrichList(g.options, byId);
            }
        }
    }

    private static java.util.Map<String, forge.headless.protocol.CardStateView> cardsById(GameStateView state) {
        java.util.Map<String, forge.headless.protocol.CardStateView> byId = new java.util.HashMap<>();
        if (state == null || state.players == null) {
            return byId;
        }
        for (var p : state.players) {
            // p.hand is deliberately null for any player who isn't the
            // viewer (hidden information) - addZone tolerates that.
            addZone(byId, p.battlefield);
            addZone(byId, p.hand);
            addZone(byId, p.graveyard);
            addZone(byId, p.exile);
            addZone(byId, p.commandZone);
        }
        return byId;
    }

    private static void addZone(java.util.Map<String, forge.headless.protocol.CardStateView> byId, List<forge.headless.protocol.CardStateView> zone) {
        if (zone == null) {
            return;
        }
        for (forge.headless.protocol.CardStateView c : zone) {
            byId.put(c.id, c);
        }
    }

    private static void enrichList(List<DecisionRequest.Option> options, java.util.Map<String, forge.headless.protocol.CardStateView> byId) {
        if (options == null) {
            return;
        }
        for (DecisionRequest.Option o : options) {
            if (o.value != null) {
                continue;
            }
            forge.headless.protocol.CardStateView card = o.card != null ? o.card : byId.get(o.cardId);
            if (card != null) {
                o.value = forge.headless.server.ai.CreatureValue.of(card);
            }
        }
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
