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
import forge.headless.protocol.CardStateView;
import forge.headless.protocol.DecisionRequest;
import forge.headless.protocol.DecisionResponse;
import forge.headless.protocol.GameStateView;
import forge.headless.protocol.PlayerStateView;
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
 * Routes decisions to a remote client (human over WebSocket, or AI bridge
 * over HTTP). Everything not explicitly wired falls back to simple,
 * non-strategic defaults (decline/first/random) rather than an embedded
 * PlayerControllerAi - forge-ai's per-ability evaluator classes routinely
 * cast a player's controller to PlayerControllerAi (e.g. ManaAi,
 * DamageDealAi), which breaks the moment that player's *actual* attached
 * controller is this class instead. Mana/cost payment is the one cluster
 * still routed through an embedded delegate (no headless-safe alternative
 * exists yet - forge-ai's payment logic is AI-evaluation-coupled, and
 * forge-gui's is Swing-widget-coupled), guarded so a failure there can't
 * take down the whole game thread.
 */
public class RemotePlayerController extends PlayerController {

    private final RemoteChannel channel;
    private final forge.headless.protocol.WebSocketChannel spectatorChannel;
    private final PlayerControllerAi delegate;
    private final java.util.Random random = new java.util.Random();

    public RemotePlayerController(Game game, Player p, LobbyPlayer lp, RemoteChannel channel,
            forge.headless.protocol.WebSocketChannel spectatorChannel) {
        super(game, p, lp);
        this.channel = channel;
        this.spectatorChannel = spectatorChannel;
        this.delegate = new PlayerControllerAi(game, p,
                new LobbyPlayerAi("fallback-ai-for-" + lp.getName(), EnumSet.noneOf(AIOption.class)));
    }

    // ---- Generic non-AI fallback helpers ----

    private <T> T pickOne(List<T> options) {
        return (options == null || options.isEmpty()) ? null : options.get(random.nextInt(options.size()));
    }

    private <T> T pickOne(Iterable<T> options) {
        return pickOne(toList(options));
    }

    private <T> List<T> toList(Iterable<T> options) {
        List<T> list = new ArrayList<>();
        if (options != null) {
            for (T t : options) {
                list.add(t);
            }
        }
        return list;
    }

    private <T> List<T> pickN(Iterable<T> options, int n) {
        List<T> list = toList(options);
        java.util.Collections.shuffle(list, random);
        return new ArrayList<>(list.subList(0, Math.min(Math.max(n, 0), list.size())));
    }

    private boolean randomBoolean() {
        return random.nextBoolean();
    }

    private <T> T safeDelegate(java.util.function.Supplier<T> call, T fallback) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            System.err.println("[RemotePlayerController] cost/mana delegate call failed for "
                    + player.getName() + ", falling back: " + e);
            return fallback;
        }
    }

    private DecisionResponse ask(String type, String prompt, List<DecisionRequest.Option> options) {
        return channel.ask(new DecisionRequest(UUID.randomUUID().toString(), type, prompt, options, safeBuildStateView(player)));
    }

    /**
     * Pushes a fresh state snapshot (from the human's perspective) to the
     * human's WebSocket whenever anything happens, on any seat - not just
     * when the human itself is asked something. Without this the human's
     * view only updates on their own turn and looks stale/stuck the rest
     * of the time, since decision requests (which normally carry state)
     * mostly go to other seats.
     */
    private void pushSpectatorUpdate() {
        if (spectatorChannel == null) {
            return;
        }
        Player human = findPlayerForChannel(spectatorChannel);
        if (human != null) {
            GameStateView view = safeBuildStateView(human);
            if (view != null) {
                spectatorChannel.pushState(view);
            }
        }
    }

    private Player findPlayerForChannel(forge.headless.protocol.WebSocketChannel target) {
        for (Player p : player.getGame().getPlayers()) {
            if (p.getController() instanceof RemotePlayerController rpc && rpc.channel == target) {
                return p;
            }
        }
        return null;
    }

    /**
     * buildStateView() reads a lot of live, mutable game state - a future
     * edge case there must never be allowed to propagate up and kill the
     * whole game thread (the engine only wraps runGame() in a top-level
     * try/catch with no GAME_OVER on failure, so an uncaught exception here
     * leaves the client stuck on "waiting for game state" forever).
     */
    private GameStateView safeBuildStateView(Player viewer) {
        try {
            return buildStateView(viewer);
        } catch (RuntimeException e) {
            System.err.println("[RemotePlayerController] buildStateView failed, skipping this update: " + e);
            return null;
        }
    }

    private GameStateView buildStateView(Player viewer) {
        Game g = player.getGame();
        List<PlayerStateView> playerViews = new ArrayList<>();
        Player active = g.getPhaseHandler().getPlayerTurn();
        for (Player p : g.getPlayers()) {
            playerViews.add(new PlayerStateView(
                    p.getName(),
                    p.getLife(),
                    p == viewer,
                    p == active,
                    p.getCardsIn(ZoneType.Hand).size(),
                    p == viewer ? toCardViews(p.getCardsIn(ZoneType.Hand)) : null,
                    toCardViews(p.getCardsIn(ZoneType.Battlefield)),
                    toCardViews(p.getCardsIn(ZoneType.Command))));
        }
        List<String> log = new ArrayList<>();
        for (forge.game.GameLogEntry entry : g.getGameLog().getLogEntries(null)) {
            log.add(entry.toString());
        }
        int logStart = Math.max(0, log.size() - 30);
        forge.game.phase.PhaseType phase = g.getPhaseHandler().getPhase();
        return new GameStateView(
                g.getPhaseHandler().getTurn(),
                phase != null ? phase.nameForUi : "",
                active != null ? active.getName() : null,
                playerViews,
                toCardViews(g.getStackZone().getCards()),
                log.subList(logStart, log.size()));
    }

    private List<CardStateView> toCardViews(Iterable<Card> cards) {
        List<CardStateView> views = new ArrayList<>();
        for (Card c : cards) {
            if (c.getType().toString().isEmpty()) {
                continue;
            }
            views.add(new CardStateView(
                    String.valueOf(c.getId()),
                    c.getName(),
                    c.getManaCost() != null ? c.getManaCost().toString() : "",
                    c.getType().toString(),
                    c.isCreature() ? c.getNetPower() : null,
                    c.isCreature() ? c.getNetToughness() : null,
                    c.isTapped(),
                    c.isCommander()));
        }
        return views;
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
        pushSpectatorUpdate();
    }

    // ---- Everything else: simple non-AI defaults (see class comment) ----

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) {
        return pickOne(abilities);
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean mayChoseNewTargets) {
        safeDelegate(() -> {
            delegate.playSpellAbilityNoStack(effectSA, mayChoseNewTargets);
            return null;
        }, null);
    }

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        return activePlayerSAs;
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        safeDelegate(() -> {
            delegate.orderAndPlaySimultaneousSa(activePlayerSAs);
            return null;
        }, null);
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        return isMandatory || randomBoolean();
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        return randomBoolean();
    }

    @Override
    public List<PaperCard> sideboard(Deck deck, GameType gameType, String message) {
        return new ArrayList<>();
    }

    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) {
        return new ArrayList<>();
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder) {
        return safeDelegate(() -> delegate.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder), new java.util.HashMap<>());
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) {
        return safeDelegate(() -> delegate.divideShield(effectSource, affected, shieldAmount), new java.util.HashMap<>());
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) {
        return safeDelegate(() -> delegate.specifyManaCombo(sa, colorSet, manaAmount, different), new java.util.HashMap<>());
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        return new CardCollection(pickN(validTargets, min));
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        return new CardCollection(pickN(validTargets, min));
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        return min;
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
        return safeDelegate(() -> delegate.chooseNewTargetsFor(ability, filter, optional), null);
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        return safeDelegate(() -> delegate.chooseTargetsFor(currentAbility), false);
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        return pickOne(allTargets);
    }

    @Override
    public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) {
        return false;
    }

    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa, String title, int max) {
        return null;
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        return new CardCollection(pickN(sourceList, isOptional ? 0 : min));
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) {
        return new CardCollection();
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player relatedPlayer, Map<String, Object> params) {
        return (isOptional && randomBoolean()) ? null : pickOne(optionList);
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title, Player relatedPlayer, Map<String, Object> params) {
        return pickN(optionList, min);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num, Map<String, Object> params) {
        return pickN(spells, num);
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) {
        return pickOne(spells);
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode bidlife, String string, int bid, Player winner) {
        return randomBoolean();
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) {
        return randomBoolean();
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message, String logic) {
        return randomBoolean();
    }

    @Override
    public boolean confirmTrigger(WrappedAbility sa) {
        return randomBoolean();
    }

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        return new ArrayList<>();
    }

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        return new ArrayList<>();
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        safeDelegate(() -> {
            delegate.declareBlockers(defender, combat);
            return null;
        }, null);
        pushSpectatorUpdate();
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        return blockers;
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        return oldBlockers;
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        return attackers;
    }

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addMsgSuffix) {
        // no-op: nobody needs to be shown anything outside the state snapshot
    }

    @Override
    public void reveal(List<CardView> cards, ZoneType zone, PlayerView owner, String messagePrefix, boolean addMsgSuffix) {
        // no-op
    }

    @Override
    public void notifyOfValue(SpellAbility saSource, GameObject realtedTarget, String value) {
        // no-op
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        return new ImmutablePair<>(topN, new CardCollection());
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        return new ImmutablePair<>(topN, new CardCollection());
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
        return randomBoolean();
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        return cards;
    }

    @Override
    public CardCollectionView chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) {
        return new CardCollection(pickN(validCards, min));
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand, String[] unlessTypes, SpellAbility sa) {
        return new CardCollection(pickN(hand, min));
    }

    @Override
    public CardCollection chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        return new CardCollection(pickN(player.getCardsIn(ZoneType.Hand), numDiscard));
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        return new CardCollection();
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) {
        return new java.util.HashMap<>();
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        return new ArrayList<>();
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        return new CardCollection(pickN(valid, min));
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        return new ArrayList<>();
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        return player;
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        return pickOne(zones);
    }

    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        return pickOne(manaChoices);
    }

    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) {
        return (isOptional && randomBoolean()) ? null : pickOne(toList(validTypes));
    }

    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) {
        return pickOne(sectors);
    }

    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) {
        return pickN(contraptions, 1);
    }

    @Override
    public int chooseSprocket(Card assignee, List<Integer> sprockets) {
        Integer choice = pickOne(sprockets);
        return choice != null ? choice : 0;
    }

    @Override
    public forge.game.PlanarDice choosePDRollToIgnore(List<forge.game.PlanarDice> rolls) {
        return pickOne(rolls);
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        return pickOne(rolls);
    }

    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) {
        return new ArrayList<>();
    }

    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        return pickOne(rolls);
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) {
        return pickOne(rolls);
    }

    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power, int toughness) {
        return pickOne(swapChoices);
    }

    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options, ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        return (optional && randomBoolean()) ? null : pickOne(options);
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        return new CardCollection(pickN(hand, cardsToReturn));
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        pushSpectatorUpdate();
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
        boolean result = safeDelegate(() -> delegate.playChosenSpellAbility(sa), false);
        pushSpectatorUpdate();
        return result;
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        return pickN(possible, num);
    }

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        return min;
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) {
        return 0;
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        return min + random.nextInt(Math.max(max - min + 1, 1));
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        Integer choice = pickOne(values);
        return choice != null ? choice : 0;
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultChoice) {
        return randomBoolean();
    }

    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) {
        return randomBoolean();
    }

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        forge.card.MagicColor.Color choice = pickOne(new ArrayList<>(colors.toEnumSet()));
        return choice != null ? choice.getColorMask() : 0;
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) {
        return chooseColor(message, null, colors);
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        List<forge.card.MagicColor.Color> picked = pickN(options.toEnumSet(), min);
        return ColorSet.fromEnums(picked);
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp, String name) {
        return null;
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        return pickOne(faces);
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message, Map<String, Object> params) {
        return pickOne(states);
    }

    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) {
        return randomBoolean();
    }

    @Override
    public forge.game.card.CounterType chooseCounterType(List<forge.game.card.CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        return pickOne(options);
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt, Card tgtCard) {
        return pickOne(options);
    }

    @Override
    public boolean confirmPayment(CostPart costPart, String string, SpellAbility sa) {
        return randomBoolean();
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        return pickOne(possibleReplacers);
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleReplacers) {
        return pickOne(possibleReplacers);
    }

    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) {
        return pickOne(choices);
    }

    @Override
    public void revealAnte(String message, com.google.common.collect.Multimap<Player, PaperCard> removedAnteCards) {
        // no-op
    }

    @Override
    public void revealAISkipCards(String message, Map<Player, Map<DeckSection, List<? extends PaperCard>>> deckCards) {
        // no-op
    }

    @Override
    public void revealUnsupported(Map<Player, List<PaperCard>> unsupported) {
        // no-op
    }

    @Override
    public List<forge.game.spellability.OptionalCostValue> chooseOptionalCosts(SpellAbility choosen, List<forge.game.spellability.OptionalCostValue> optionalCostValues) {
        return new ArrayList<>();
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        return costs;
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        return false;
    }

    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa) {
        return false;
    }

    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) {
        return false;
    }

    // ---- Mana/cost payment: no headless-safe non-AI implementation exists
    // yet (see class comment), so this stays on the embedded delegate,
    // guarded so a failure here can't take down the whole game thread. ----

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt, ManaConversionMatrix matrix, boolean effect) {
        return safeDelegate(() -> delegate.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect), false);
    }

    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt, ManaConversionMatrix matrix, boolean effect) {
        return safeDelegate(() -> delegate.applyManaToCost(toPay, ability, prompt, matrix, effect), false);
    }

    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility ability, boolean effect, String prompt) {
        return safeDelegate(() -> delegate.getCostDecisionMaker(player, ability, effect, prompt), null);
    }

    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount, boolean isOptional, String prompt) {
        return new CardCollection(pickN(optionList, isOptional ? 0 : amount));
    }

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        return null;
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        ICardFace choice = pickOne(faces);
        return choice != null ? choice.getName() : null;
    }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
        return (isOptional && randomBoolean()) ? null : pickOne(fetchList);
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, int min, int max, DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        return pickN(fetchList, min);
    }

    @Override
    public void autoPassCancel() {
        // no-op
    }

    @Override
    public void awaitNextInput() {
        // no-op
    }

    @Override
    public void cancelAwaitNextInput() {
        // no-op
    }
}
