package forge.headless.server;

import com.google.common.collect.ListMultimap;
import forge.LobbyPlayer;
import forge.ai.AIOption;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.GameType;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardView;
import forge.game.card.CardState;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.cost.Cost;
import forge.game.cost.CostDecisionMakerBase;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPartWithList;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlayerController;
import forge.game.player.PlayerView;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.headless.protocol.DecisionRequest;
import forge.headless.protocol.DecisionResponse;
import forge.headless.protocol.RemoteChannel;
import forge.item.PaperCard;
import forge.util.ITriggerEvent;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Routes a handful of decisions to a remote client (human over WebSocket,
 * or AI bridge over HTTP) and falls back to an embedded PlayerControllerAi
 * for everything else. The fallback exists so the engine never gets stuck
 * waiting on a decision type we haven't wired up yet - this is meant to
 * grow as the frontend/AI bridge grow, not to be complete on day one.
 */
public class RemotePlayerController extends PlayerController {

    private final RemoteChannel channel;
    private final PlayerControllerAi delegate;

    public RemotePlayerController(Game game, Player p, LobbyPlayer lp, RemoteChannel channel) {
        super(game, p, lp);
        this.channel = channel;
        this.delegate = new PlayerControllerAi(game, p,
                new LobbyPlayerAi("fallback-ai-for-" + lp.getName(), EnumSet.noneOf(AIOption.class)));
    }

    private DecisionResponse ask(String type, String prompt, List<DecisionRequest.Option> options) {
        return channel.ask(new DecisionRequest(UUID.randomUUID().toString(), type, prompt, options));
    }

    // ---- Decisions wired to the remote channel ----

    @Override
    public boolean mulliganKeepHand(Player p, int cardsToReturn) {
        DecisionResponse resp = ask("MULLIGAN_KEEP", "Keep your hand?", null);
        return resp.booleanValue != null ? resp.booleanValue : true;
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow, Map<String, Object> params) {
        DecisionResponse resp = ask("CONFIRM", message, null);
        return resp.booleanValue != null ? resp.booleanValue : false;
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        GameEntity defender = combat.getDefenders().isEmpty() ? null : combat.getDefenders().get(0);
        if (defender == null) {
            return;
        }
        List<Card> candidates = new ArrayList<>();
        List<DecisionRequest.Option> options = new ArrayList<>();
        for (Card c : attacker.getCreaturesInPlay()) {
            if (CombatUtil.canAttack(c, defender)) {
                candidates.add(c);
                options.add(new DecisionRequest.Option(String.valueOf(c.getId()), c.getName()));
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        DecisionResponse resp = ask("DECLARE_ATTACKERS", "Declare attackers", options);
        if (resp.chosenIds == null) {
            return;
        }
        for (Card c : candidates) {
            if (resp.chosenIds.contains(String.valueOf(c.getId()))) {
                combat.addAttacker(c, defender);
            }
        }
    }

    // ---- Everything else: forward to the embedded AI ----

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) {
        return delegate.getAbilityToPlay(hostCard, abilities, triggerEvent);
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean mayChoseNewTargets) {
        delegate.playSpellAbilityNoStack(effectSA, mayChoseNewTargets);
    }

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        return delegate.orderSimultaneousSa(activePlayerSAs);
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        delegate.orderAndPlaySimultaneousSa(activePlayerSAs);
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        return delegate.playTrigger(host, wrapperAbility, isMandatory);
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        return delegate.playSaFromPlayEffect(tgtSA);
    }

    @Override
    public List<PaperCard> sideboard(Deck deck, GameType gameType, String message) {
        return delegate.sideboard(deck, gameType, message);
    }

    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) {
        return delegate.chooseCardsYouWonToAddToDeck(losses);
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder) {
        return delegate.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) {
        return delegate.divideShield(effectSource, affected, shieldAmount);
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) {
        return delegate.specifyManaCombo(sa, colorSet, manaAmount, different);
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        return delegate.choosePermanentsToSacrifice(sa, min, max, validTargets, message);
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        return delegate.choosePermanentsToDestroy(sa, min, max, validTargets, message);
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        return delegate.announceRequirements(ability, min, max, announce);
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
        return delegate.chooseNewTargetsFor(ability, filter, optional);
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        return delegate.chooseTargetsFor(currentAbility);
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        return delegate.chooseTarget(sa, allTargets);
    }

    @Override
    public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) {
        return delegate.helpPayForAssistSpell(cost, sa, max, requested);
    }

    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa, String title, int max) {
        return delegate.choosePlayerToAssistPayment(optionList, sa, title, max);
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        return delegate.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) {
        return delegate.chooseCardsForEffectMultiple(validMap, sa, title, isOptional);
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player relatedPlayer, Map<String, Object> params) {
        return delegate.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, relatedPlayer, params);
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title, Player relatedPlayer, Map<String, Object> params) {
        return delegate.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, relatedPlayer, params);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num, Map<String, Object> params) {
        return delegate.chooseSpellAbilitiesForEffect(spells, sa, title, num, params);
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) {
        return delegate.chooseSingleSpellForEffect(spells, sa, title, params);
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode bidlife, String string, int bid, Player winner) {
        return delegate.confirmBidAction(sa, bidlife, string, bid, winner);
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) {
        return delegate.confirmReplacementEffect(replacementEffect, effectSA, affected, question);
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message, String logic) {
        return delegate.confirmStaticApplication(hostCard, mode, message, logic);
    }

    @Override
    public boolean confirmTrigger(WrappedAbility sa) {
        return delegate.confirmTrigger(sa);
    }

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        return delegate.exertAttackers(attackers);
    }

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        return delegate.enlistAttackers(attackers);
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        delegate.declareBlockers(defender, combat);
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        return delegate.orderBlockers(attacker, blockers);
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        return delegate.orderBlocker(attacker, blocker, oldBlockers);
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        return delegate.orderAttackers(blocker, attackers);
    }

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addMsgSuffix) {
        delegate.reveal(cards, zone, owner, messagePrefix, addMsgSuffix);
    }

    @Override
    public void reveal(List<CardView> cards, ZoneType zone, PlayerView owner, String messagePrefix, boolean addMsgSuffix) {
        delegate.reveal(cards, zone, owner, messagePrefix, addMsgSuffix);
    }

    @Override
    public void notifyOfValue(SpellAbility saSource, GameObject realtedTarget, String value) {
        delegate.notifyOfValue(saSource, realtedTarget, value);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        return delegate.arrangeForScry(topN);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        return delegate.arrangeForSurveil(topN);
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
        return delegate.willPutCardOnTop(c);
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        return delegate.orderMoveToZoneList(cards, destinationZone, source);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) {
        return delegate.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand, String[] unlessTypes, SpellAbility sa) {
        return delegate.chooseCardsToDiscardUnlessType(min, hand, unlessTypes, sa);
    }

    @Override
    public CardCollection chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        return delegate.chooseCardsToDiscardToMaximumHandSize(numDiscard);
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        return delegate.chooseCardsToDelve(genericAmount, grave);
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) {
        return delegate.chooseCardsForConvokeOrImprovise(sa, manaCost, untappedCards, artifacts, creatures, maxReduction);
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        return delegate.chooseCardsForSplice(sa, cards);
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        return delegate.chooseCardsToRevealFromHand(min, max, valid);
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        return delegate.chooseSaToActivateFromOpeningHand(usableFromOpeningHand);
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        return delegate.chooseStartingPlayer(isFirstGame);
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        return delegate.chooseStartingHand(zones);
    }

    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        return delegate.chooseManaFromPool(manaChoices);
    }

    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) {
        return delegate.chooseSomeType(kindOfType, sa, validTypes, isOptional);
    }

    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) {
        return delegate.chooseSector(assignee, ai, sectors);
    }

    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) {
        return delegate.chooseContraptionsToCrank(contraptions);
    }

    @Override
    public int chooseSprocket(Card assignee, List<Integer> sprockets) {
        return delegate.chooseSprocket(assignee, sprockets);
    }

    @Override
    public forge.game.PlanarDice choosePDRollToIgnore(List<forge.game.PlanarDice> rolls) {
        return delegate.choosePDRollToIgnore(rolls);
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        return delegate.chooseRollToIgnore(rolls);
    }

    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) {
        return delegate.chooseDiceToReroll(rolls);
    }

    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        return delegate.chooseRollToModify(rolls);
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) {
        return delegate.chooseRollToSwap(rolls);
    }

    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power, int toughness) {
        return delegate.chooseRollSwapValue(swapChoices, currentResult, power, toughness);
    }

    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options, ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        return delegate.vote(sa, prompt, options, votes, forPlayer, optional);
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        return delegate.tuckCardsViaMulligan(hand, cardsToReturn);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        List<SpellAbility> legalPlays = new ArrayList<>();
        for (Card c : player.getCardsIn(ZoneType.Hand)) {
            legalPlays.addAll(c.getAllPossibleAbilities(player, true));
        }
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            legalPlays.addAll(c.getAllPossibleAbilities(player, true));
        }
        if (legalPlays.isEmpty()) {
            return null;
        }

        List<DecisionRequest.Option> options = new ArrayList<>();
        for (int i = 0; i < legalPlays.size(); i++) {
            options.add(new DecisionRequest.Option(String.valueOf(i), legalPlays.get(i).toString()));
        }
        DecisionResponse resp = ask("CHOOSE_SPELL_ABILITY", "Choose a play, or none to pass priority", options);
        if (resp.chosenIds == null || resp.chosenIds.isEmpty()) {
            return null;
        }
        int chosenIndex = Integer.parseInt(resp.chosenIds.get(0));
        return List.of(legalPlays.get(chosenIndex));
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        return delegate.playChosenSpellAbility(sa);
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        return delegate.chooseModeForAbility(sa, possible, min, num, allowRepeat);
    }

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        return delegate.chooseNumberForCostReduction(sa, min, max);
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) {
        return delegate.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        return delegate.chooseNumber(sa, title, min, max);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        return delegate.chooseNumber(sa, title, values, relatedPlayer);
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultChoice) {
        return delegate.chooseBinary(sa, question, kindOfChoice, defaultChoice);
    }

    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) {
        return delegate.chooseFlipResult(sa, flipper, call);
    }

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        return delegate.chooseColor(message, sa, colors);
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) {
        return delegate.chooseColorAllowColorless(message, c, colors);
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        return delegate.chooseColors(message, sa, min, max, options);
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp, String name) {
        return delegate.chooseSingleCardFace(sa, message, cpp, name);
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        return delegate.chooseSingleCardFace(sa, faces, message);
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message, Map<String, Object> params) {
        return delegate.chooseSingleCardState(sa, states, message, params);
    }

    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) {
        return delegate.chooseCardsPile(sa, pile1, pile2, faceUp);
    }

    @Override
    public forge.game.card.CounterType chooseCounterType(List<forge.game.card.CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        return delegate.chooseCounterType(options, sa, prompt, params);
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt, Card tgtCard) {
        return delegate.chooseKeywordForPump(options, sa, prompt, tgtCard);
    }

    @Override
    public boolean confirmPayment(CostPart costPart, String string, SpellAbility sa) {
        return delegate.confirmPayment(costPart, string, sa);
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        return delegate.chooseSingleReplacementEffect(possibleReplacers);
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleReplacers) {
        return delegate.chooseSingleStaticAbility(possibleReplacers);
    }

    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) {
        return delegate.chooseProtectionType(sa, choices);
    }

    @Override
    public void revealAnte(String message, com.google.common.collect.Multimap<Player, PaperCard> removedAnteCards) {
        delegate.revealAnte(message, removedAnteCards);
    }

    @Override
    public void revealAISkipCards(String message, Map<Player, Map<DeckSection, List<? extends PaperCard>>> deckCards) {
        delegate.revealAISkipCards(message, deckCards);
    }

    @Override
    public void revealUnsupported(Map<Player, List<PaperCard>> unsupported) {
        delegate.revealUnsupported(unsupported);
    }

    @Override
    public List<forge.game.spellability.OptionalCostValue> chooseOptionalCosts(SpellAbility choosen, List<forge.game.spellability.OptionalCostValue> optionalCostValues) {
        return delegate.chooseOptionalCosts(choosen, optionalCostValues);
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        return delegate.orderCosts(costs);
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        return delegate.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
    }

    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa) {
        return delegate.payCostDuringRoll(cost, sa);
    }

    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) {
        return delegate.payCombatCost(card, cost, sa, prompt);
    }

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt, ManaConversionMatrix matrix, boolean effect) {
        return delegate.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect);
    }

    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt, ManaConversionMatrix matrix, boolean effect) {
        return delegate.applyManaToCost(toPay, ability, prompt, matrix, effect);
    }

    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount, boolean isOptional, String prompt) {
        return delegate.chooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt);
    }

    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility ability, boolean effect, String prompt) {
        return delegate.getCostDecisionMaker(player, ability, effect, prompt);
    }

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        return delegate.chooseCardName(sa, cpp, valid, message);
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        return delegate.chooseCardName(sa, faces, message);
    }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
        return delegate.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional, decider);
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, int min, int max, DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        return delegate.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider);
    }

    @Override
    public void autoPassCancel() {
        delegate.autoPassCancel();
    }

    @Override
    public void awaitNextInput() {
        delegate.awaitNextInput();
    }

    @Override
    public void cancelAwaitNextInput() {
        delegate.cancelAwaitNextInput();
    }
}
