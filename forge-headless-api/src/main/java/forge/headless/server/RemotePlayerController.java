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

    /** Log entry types worth showing as "real actions" - excludes PHASE/TURN noise. */
    private static final java.util.Set<forge.game.GameLogEntryType> ACTION_LOG_TYPES = java.util.EnumSet.of(
            forge.game.GameLogEntryType.DAMAGE, forge.game.GameLogEntryType.LIFE,
            forge.game.GameLogEntryType.ZONE_CHANGE, forge.game.GameLogEntryType.LAND,
            forge.game.GameLogEntryType.DISCARD, forge.game.GameLogEntryType.COMBAT,
            forge.game.GameLogEntryType.STACK_ADD, forge.game.GameLogEntryType.STACK_RESOLVE,
            forge.game.GameLogEntryType.MANA, forge.game.GameLogEntryType.MULLIGAN,
            forge.game.GameLogEntryType.INFORMATION, forge.game.GameLogEntryType.EFFECT_REPLACED,
            forge.game.GameLogEntryType.GAME_OUTCOME);

    private final RemoteChannel channel;
    private final forge.headless.protocol.WebSocketChannel spectatorChannel;
    private final PlayerControllerAi delegate;
    private final java.util.Random random = new java.util.Random();

    /**
     * Phases this seat wants to actually stop and be asked at - everything
     * else gets silently auto-passed when there's nothing forced to react
     * to (stack empty), mirroring Forge's per-player "stop at this phase"
     * toggles. Defaults to the phases most players actually care about.
     */
    private final EnumSet<forge.game.phase.PhaseType> stopPhases = EnumSet.of(
            forge.game.phase.PhaseType.UPKEEP,
            forge.game.phase.PhaseType.MAIN1,
            forge.game.phase.PhaseType.COMBAT_DECLARE_ATTACKERS,
            forge.game.phase.PhaseType.COMBAT_DECLARE_BLOCKERS,
            forge.game.phase.PhaseType.MAIN2);

    /**
     * Game.getPhaseHandler().getTurn() value (the GLOBAL turn counter,
     * shared by every player - not Player.getTurn(), which only counts
     * this player's own turns and therefore never changes while it's an
     * opponent's turn) for which "End Turn" was pressed. While it matches
     * the current global turn, every remaining priority window this seat
     * gets auto-passes regardless of stopPhases, same idea as Forge's own
     * End Turn button. Naturally stops applying once the global turn
     * advances, no reset needed. Doesn't affect mandatory decisions
     * (declare attackers/blockers, paying for something already on the
     * stack) - those go through separate controller methods that always
     * ask.
     */
    private int autoPassEndTurnAt = -1;
    private static final String END_TURN_OPTION = "__END_TURN__";

    public RemotePlayerController(Game game, Player p, LobbyPlayer lp, RemoteChannel channel,
            forge.headless.protocol.WebSocketChannel spectatorChannel) {
        super(game, p, lp);
        this.channel = channel;
        this.spectatorChannel = spectatorChannel;
        this.delegate = new PlayerControllerAi(game, p,
                new LobbyPlayerAi("fallback-ai-for-" + lp.getName(), EnumSet.noneOf(AIOption.class)));
        if (channel instanceof forge.headless.protocol.WebSocketChannel wsChannel) {
            wsChannel.onPhasePrefsChanged(this::applyStopPhases);
        }
    }

    private void applyStopPhases(java.util.Set<String> phaseNames) {
        stopPhases.clear();
        for (String name : phaseNames) {
            try {
                stopPhases.add(forge.game.phase.PhaseType.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // unknown phase name from the client - skip it
            }
        }
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

    /**
     * Temporarily swaps the player's actual controller to the embedded AI
     * delegate for the duration of the call, the same trick Forge's own
     * InputPayMana uses for its "Auto" button (Player.runWithController).
     * Without this, forge-ai code that does "ai.getController() instanceof
     * PlayerControllerAi" (common in mana/cost evaluation) sees this
     * wrapper instead and throws - wrapping a Runnable around the real
     * delegate makes player.getController() briefly return the real thing.
     */
    private <T> T withDelegate(java.util.function.Supplier<T> call, T fallback) {
        return safely(() -> {
            Object[] result = new Object[1];
            player.runWithController(() -> result[0] = call.get(), delegate);
            @SuppressWarnings("unchecked")
            T value = (T) result[0];
            return value;
        }, fallback);
    }

    private void withDelegateVoid(Runnable call) {
        safely(() -> {
            player.runWithController(call, delegate);
            return null;
        }, null);
    }

    private <T> T safely(java.util.function.Supplier<T> call, T fallback) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            System.err.println("[RemotePlayerController] call failed for "
                    + player.getName() + ", falling back: " + e);
            return fallback;
        }
    }

    private DecisionResponse ask(String type, String prompt, List<DecisionRequest.Option> options) {
        return channel.ask(new DecisionRequest(UUID.randomUUID().toString(), type, prompt, options, safeBuildStateView(player)));
    }

    private DecisionResponse askList(String prompt, List<DecisionRequest.Option> options, int min, int max) {
        DecisionRequest req = new DecisionRequest(UUID.randomUUID().toString(), "CHOOSE_LIST", prompt, options, safeBuildStateView(player));
        req.min = min;
        req.max = max;
        return channel.ask(req);
    }

    /**
     * Generic "pick between min and max items from this list" decision -
     * backs scry/surveil arrangement, modal-spell mode choice, sacrifice/
     * discard targets, dig effects, type choices, etc. Replacing each of
     * those individually-stubbed PlayerController methods with a call into
     * this shared helper means one real UI control (a checkbox list, or a
     * click-to-toggle on the card itself when cardIdFn is given) covers all
     * of them instead of each picking randomly.
     */
    private <T> List<T> chooseFromList(String prompt, List<T> source, int min, int max,
            java.util.function.Function<T, String> labelFn, java.util.function.Function<T, String> cardIdFn) {
        if (source.isEmpty()) {
            return new ArrayList<>();
        }
        List<DecisionRequest.Option> options = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            T item = source.get(i);
            options.add(new DecisionRequest.Option(String.valueOf(i), labelFn.apply(item),
                    cardIdFn != null ? cardIdFn.apply(item) : null));
        }
        DecisionResponse resp = askList(prompt, options, Math.max(min, 0), Math.min(max, source.size()));
        List<T> chosen = new ArrayList<>();
        if (resp.chosenIds != null) {
            for (String id : resp.chosenIds) {
                try {
                    int idx = Integer.parseInt(id);
                    if (idx >= 0 && idx < source.size()) {
                        chosen.add(source.get(idx));
                    }
                } catch (NumberFormatException ignored) { }
            }
        }
        return chosen;
    }

    /**
     * Only tags a cardId when the card is actually sitting in a zone the
     * frontend renders (hand/battlefield/command zone) - otherwise the UI
     * tries to make the player click a card that isn't drawn anywhere
     * (library search results, scry/surveil candidates, top-of-library
     * reorder, etc.), which looks like "nothing happens" when really the
     * checklist just silently has zero clickable targets. Those cases
     * fall back to the plain text checklist instead.
     */
    private static String cardIdOf(Object o) {
        if (!(o instanceof Card c)) {
            return null;
        }
        forge.game.zone.Zone zone = c.getZone();
        if (zone == null) {
            return null;
        }
        switch (zone.getZoneType()) {
            case Hand:
            case Battlefield:
            case Command:
                return String.valueOf(c.getId());
            default:
                return null;
        }
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
        for (forge.game.GameLogEntry entry : g.getGameLog().getLogEntriesForTypes(ACTION_LOG_TYPES)) {
            log.add(entry.toString());
        }
        int logStart = Math.max(0, log.size() - 30);
        forge.game.phase.PhaseType phase = g.getPhaseHandler().getPhase();

        List<String> viewerStopPhases = new ArrayList<>();
        if (viewer.getController() instanceof RemotePlayerController viewerController) {
            for (forge.game.phase.PhaseType pt : viewerController.stopPhases) {
                viewerStopPhases.add(pt.name());
            }
        }

        return new GameStateView(
                // Player.getTurn() is that player's own turn count (their
                // 1st, 2nd, 3rd... turn), not the global turn number across
                // all 4 players, which is what "Turn N" should mean here.
                active != null ? active.getTurn() : g.getPhaseHandler().getTurn(),
                phase != null ? phase.name() : "",
                active != null ? active.getName() : null,
                playerViews,
                toCardViews(g.getStackZone().getCards()),
                log.subList(logStart, log.size()),
                viewerStopPhases);
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
                options.add(new DecisionRequest.Option(String.valueOf(c.getId()), c.getName(), String.valueOf(c.getId())));
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
        withDelegateVoid(() -> delegate.playSpellAbilityNoStack(effectSA, mayChoseNewTargets));
    }

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        return activePlayerSAs;
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        withDelegateVoid(() -> delegate.orderAndPlaySimultaneousSa(activePlayerSAs));
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
        return withDelegate(() -> delegate.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder), new java.util.HashMap<>());
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) {
        return withDelegate(() -> delegate.divideShield(effectSource, affected, shieldAmount), new java.util.HashMap<>());
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) {
        return withDelegate(() -> delegate.specifyManaCombo(sa, colorSet, manaAmount, different), new java.util.HashMap<>());
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        return new CardCollection(chooseFromList(message != null ? message : "Choose permanents to sacrifice",
                new ArrayList<>(validTargets), min, max, Card::toString, RemotePlayerController::cardIdOf));
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        return new CardCollection(chooseFromList(message != null ? message : "Choose permanents to destroy",
                new ArrayList<>(validTargets), min, max, Card::toString, RemotePlayerController::cardIdOf));
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        return min;
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
        return withDelegate(() -> delegate.chooseNewTargetsFor(ability, filter, optional), null);
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        return withDelegate(() -> delegate.chooseTargetsFor(currentAbility), false);
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
        int effectiveMin = isOptional ? 0 : min;
        return new CardCollection(chooseFromList(title != null ? title : "Choose cards", new ArrayList<>(sourceList),
                effectiveMin, max, Card::toString, RemotePlayerController::cardIdOf));
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) {
        return new CardCollection();
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player relatedPlayer, Map<String, Object> params) {
        List<T> chosen = chooseFromList(title != null ? title : "Choose one", new ArrayList<>(optionList),
                isOptional ? 0 : 1, 1, String::valueOf, RemotePlayerController::cardIdOf);
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title, Player relatedPlayer, Map<String, Object> params) {
        return chooseFromList(title != null ? title : "Choose entities", new ArrayList<>(optionList),
                min, max, String::valueOf, RemotePlayerController::cardIdOf);
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
        withDelegateVoid(() -> delegate.declareBlockers(defender, combat));
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
        // GameAction.scry() reverses+replays toTop, so cards we don't move
        // out keep their original relative order automatically - we only
        // need to ask which ones go to the bottom.
        List<Card> toBottom = chooseFromList("Scry " + topN.size() + " - choose cards to put on the BOTTOM of your library",
                new ArrayList<>(topN), 0, topN.size(), Card::toString, RemotePlayerController::cardIdOf);
        CardCollection top = new CardCollection();
        CardCollection bottom = new CardCollection();
        for (Card c : topN) {
            (toBottom.contains(c) ? bottom : top).add(c);
        }
        return new ImmutablePair<>(top, bottom);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        List<Card> toGrave = chooseFromList("Surveil " + topN.size() + " - choose cards to put into your graveyard",
                new ArrayList<>(topN), 0, topN.size(), Card::toString, RemotePlayerController::cardIdOf);
        CardCollection top = new CardCollection();
        CardCollection grave = new CardCollection();
        for (Card c : topN) {
            (toGrave.contains(c) ? grave : top).add(c);
        }
        return new ImmutablePair<>(top, grave);
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
        return randomBoolean();
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        // Caller moves cards to the zone one at a time in list order with
        // no reversal, so the LAST card in our returned list ends up on
        // top. Ask the player top-to-bottom (most natural framing), then
        // reverse before returning.
        List<Card> remaining = new ArrayList<>(cards);
        List<Card> topToBottom = new ArrayList<>();
        while (!remaining.isEmpty()) {
            String prompt = "Put cards back in order - choose position " + (topToBottom.size() + 1)
                    + " of " + cards.size() + " from the top";
            List<Card> pick = chooseFromList(prompt, remaining, 1, 1, Card::toString, RemotePlayerController::cardIdOf);
            Card chosen = pick.isEmpty() ? remaining.get(0) : pick.get(0);
            topToBottom.add(chosen);
            remaining.remove(chosen);
        }
        java.util.Collections.reverse(topToBottom);
        return new CardCollection(topToBottom);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) {
        return new CardCollection(chooseFromList("Choose cards to discard", new ArrayList<>(validCards),
                min, max, Card::toString, RemotePlayerController::cardIdOf));
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
        List<String> chosen = chooseFromList("Choose a " + kindOfType, toList(validTypes),
                isOptional ? 0 : 1, 1, s -> s, null);
        return chosen.isEmpty() ? null : chosen.get(0);
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
        Game g = player.getGame();
        if (autoPassEndTurnAt == g.getPhaseHandler().getTurn()) {
            return null;
        }
        if (g.getStackZone().isEmpty() && !stopPhases.contains(g.getPhaseHandler().getPhase())) {
            return null;
        }
        List<SpellAbility> legalPlays = new ArrayList<>();
        for (Card c : player.getCardsIn(ZoneType.Hand)) {
            legalPlays.addAll(c.getAllPossibleAbilities(player, true));
        }
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            legalPlays.addAll(c.getAllPossibleAbilities(player, true));
        }
        for (Card c : player.getCardsIn(ZoneType.Command)) {
            legalPlays.addAll(c.getAllPossibleAbilities(player, true));
        }
        if (legalPlays.isEmpty()) {
            return null;
        }

        List<DecisionRequest.Option> options = new ArrayList<>();
        for (int i = 0; i < legalPlays.size(); i++) {
            SpellAbility sa = legalPlays.get(i);
            String cardId = sa.getHostCard() != null ? String.valueOf(sa.getHostCard().getId()) : null;
            options.add(new DecisionRequest.Option(String.valueOf(i), sa.toString(), cardId));
        }
        options.add(new DecisionRequest.Option(END_TURN_OPTION, "End Turn", null));
        DecisionResponse resp = ask("CHOOSE_SPELL_ABILITY", "Choose a play, or none to pass priority", options);
        if (resp.chosenIds == null || resp.chosenIds.isEmpty()) {
            return null;
        }
        String chosen = resp.chosenIds.get(0);
        if (chosen.equals(END_TURN_OPTION)) {
            autoPassEndTurnAt = g.getPhaseHandler().getTurn();
            return null;
        }
        int chosenIndex = Integer.parseInt(chosen);
        return List.of(legalPlays.get(chosenIndex));
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        // forge.game.player.PlaySpellAbility is the shared engine-level cast
        // routine (not AI- or GUI-specific) - it moves the spell to the
        // stack, attempts payment via our own payManaCost/applyManaToCost,
        // and properly rolls back (off the stack, mana refunded) if payment
        // fails. The AI delegate's own cast path doesn't go through this,
        // which is how an unpayable spell could get stuck on the stack.
        boolean result = safely(() -> forge.game.player.PlaySpellAbility.playSpellAbility(this, player, sa), false);
        pushSpectatorUpdate();
        return result;
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        // allowRepeat (choosing the same mode more than once) isn't modeled
        // by chooseFromList yet, since none of the cards we've audited so
        // far need it - revisit if that turns out to matter.
        int count = Math.min(num, possible.size());
        return chooseFromList("Choose a mode", possible, Math.min(min, count), count,
                AbilitySub::toString, null);
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
        List<forge.card.MagicColor.Color> choices = new ArrayList<>(colors.toEnumSet());
        if (choices.isEmpty()) {
            return 0;
        }
        if (choices.size() == 1) {
            return choices.get(0).getColorMask();
        }
        List<DecisionRequest.Option> options = new ArrayList<>();
        for (forge.card.MagicColor.Color c : choices) {
            options.add(new DecisionRequest.Option(String.valueOf(c.getColorMask()), c.toString()));
        }
        DecisionResponse resp = ask("CHOOSE_COLOR", message, options);
        if (resp.chosenIds == null || resp.chosenIds.isEmpty()) {
            return choices.get(0).getColorMask();
        }
        try {
            return (byte) Integer.parseInt(resp.chosenIds.get(0));
        } catch (NumberFormatException e) {
            return choices.get(0).getColorMask();
        }
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

    // ---- Mana payment: mirrors Forge's own InputPayMana - tap mana sources
    // one at a time (each tap resolves immediately, same as clicking a land
    // in Forge's UI), or auto-pay, or cancel. Cancelling mid-payment doesn't
    // need its own undo logic: forge.game.player.PlaySpellAbility already
    // rolls back the whole cast (untaps lands, refunds mana, takes the
    // spell back off the stack) whenever applyManaToCost returns false. ----

    private static final String AUTO_PAY_OPTION = "__AUTO__";
    private static final String CANCEL_PAY_OPTION = "__CANCEL__";

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt, ManaConversionMatrix matrix, boolean effect) {
        // Shared engine routine (same one PlayerControllerHuman uses) - it
        // calls back into our own applyManaToCost below for the actual
        // tap-or-auto-or-cancel interaction.
        return forge.game.player.PlaySpellAbility.payManaCost(this, toPay, costPartMana, sa, player, prompt, matrix, effect);
    }

    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt, ManaConversionMatrix matrix, boolean effect) {
        // Guard against an unpayable cost looping forever: if neither a tap
        // nor an auto-pay attempt actually reduces what's left to pay for a
        // couple of rounds in a row, give up instead of re-asking forever.
        // This matters most for bot seats, which always answer "auto-pay"
        // and have no human to notice nothing is progressing and cancel.
        String lastRemaining = null;
        int stallCount = 0;

        while (!toPay.isPaid()) {
            String remainingNow = toPay.toString(false, player.getManaPool());
            if (remainingNow.equals(lastRemaining)) {
                stallCount++;
                if (stallCount >= 2) {
                    return false;
                }
            } else {
                stallCount = 0;
            }
            lastRemaining = remainingNow;

            List<SpellAbility> sources = new ArrayList<>();
            for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
                for (SpellAbility ma : c.getManaAbilities()) {
                    ma.setActivatingPlayer(player);
                    if (ma.canPlay(true)) {
                        sources.add(ma);
                    }
                }
            }

            List<DecisionRequest.Option> options = new ArrayList<>();
            for (int i = 0; i < sources.size(); i++) {
                SpellAbility ma = sources.get(i);
                options.add(new DecisionRequest.Option(String.valueOf(i), ma.toString(), String.valueOf(ma.getHostCard().getId())));
            }
            options.add(new DecisionRequest.Option(AUTO_PAY_OPTION, "Auto-pay remaining cost", null));
            options.add(new DecisionRequest.Option(CANCEL_PAY_OPTION, "Cancel", null));

            String remaining = toPay.toString(false, player.getManaPool());
            DecisionResponse resp = ask("PAY_MANA", (prompt != null ? prompt : "Pay mana cost") + " - remaining: " + remaining, options);
            String chosen = resp.chosenIds == null || resp.chosenIds.isEmpty() ? CANCEL_PAY_OPTION : resp.chosenIds.get(0);

            if (chosen.equals(CANCEL_PAY_OPTION)) {
                return false;
            } else if (chosen.equals(AUTO_PAY_OPTION)) {
                // Same trick Forge's own "Auto" button uses: temporarily run
                // the AI's mana-payment evaluator as this player's real
                // controller, so its internal "instanceof PlayerControllerAi"
                // checks succeed instead of seeing this wrapper.
                //
                // Forge's real UI only enables the Auto button once a dry
                // run (canPayManaCost, no side effects) confirms the cost is
                // actually payable. ComputerUtilMana.payManaCost itself is
                // NOT atomic - on a real (non-test) attempt it taps sources
                // and spends floating mana as it goes, and if it ends up
                // unable to complete the cost, those partial taps are only
                // undone via the ability's own paying-mana refund tracking.
                // Skipping the dry run risked tapping out real sources for
                // an attempt already known to fail.
                boolean[] canPay = new boolean[1];
                player.runWithController(() -> canPay[0] = forge.ai.ComputerUtilMana.canPayManaCost(toPay, ability, player, effect), delegate);
                if (canPay[0]) {
                    player.runWithController(() -> forge.ai.ComputerUtilMana.payManaCost(toPay, ability, player, effect), delegate);
                }
            } else {
                int idx;
                try {
                    idx = Integer.parseInt(chosen);
                } catch (NumberFormatException e) {
                    idx = -1;
                }
                if (idx >= 0 && idx < sources.size()) {
                    SpellAbility tapAbility = sources.get(idx);
                    if (forge.game.player.PlaySpellAbility.playSpellAbility(this, player, tapAbility)) {
                        player.getManaPool().payManaFromAbility(ability, toPay, tapAbility);
                    }
                }
            }
            pushSpectatorUpdate();
        }
        return true;
    }

    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility ability, boolean effect, String prompt) {
        return withDelegate(() -> delegate.getCostDecisionMaker(player, ability, effect, prompt), null);
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
        List<Card> chosen = chooseFromList(selectPrompt != null ? selectPrompt : "Choose a card", new ArrayList<>(fetchList),
                isOptional ? 0 : 1, 1, Card::toString, RemotePlayerController::cardIdOf);
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, int min, int max, DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        return chooseFromList(selectPrompt != null ? selectPrompt : "Choose cards", new ArrayList<>(fetchList),
                min, max, Card::toString, RemotePlayerController::cardIdOf);
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
