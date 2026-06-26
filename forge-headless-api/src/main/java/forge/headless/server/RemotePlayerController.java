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
 * Routes decisions to a remote channel - a human over WebSocket, or an AI
 * seat's in-process heuristic channel (InProcessAiChannel) with no network
 * hop at all. Everything not explicitly wired falls back to simple,
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

    /** Log entry types worth showing as "real actions" - excludes PHASE/TURN
     * noise, and MANA (tapping a land/rock for mana is too frequent/low-value
     * to be worth a log line - the floating-mana indicator already shows it). */
    private static final java.util.Set<forge.game.GameLogEntryType> ACTION_LOG_TYPES = java.util.EnumSet.of(
            forge.game.GameLogEntryType.DAMAGE, forge.game.GameLogEntryType.LIFE,
            forge.game.GameLogEntryType.ZONE_CHANGE, forge.game.GameLogEntryType.LAND,
            forge.game.GameLogEntryType.DISCARD, forge.game.GameLogEntryType.COMBAT,
            forge.game.GameLogEntryType.STACK_ADD, forge.game.GameLogEntryType.STACK_RESOLVE,
            forge.game.GameLogEntryType.MULLIGAN,
            forge.game.GameLogEntryType.INFORMATION, forge.game.GameLogEntryType.EFFECT_REPLACED,
            forge.game.GameLogEntryType.GAME_OUTCOME);

    private final RemoteChannel channel;
    private final forge.headless.protocol.WebSocketChannel spectatorChannel;
    private final PlayerControllerAi delegate;
    private final java.util.Random random = new java.util.Random();

    /**
     * Phases this seat wants to actually stop and be asked at, keyed by
     * WHICH PLAYER's turn it is - everything else gets silently auto-passed
     * when there's nothing forced to react to (stack empty). This mirrors
     * real Forge: CMatchUI.isUiSetToSkipPhase(playerTurn, phase) looks up
     * the phase indicator on the board of whoever's turn it currently is,
     * not a single shared toggle set - so "stop during declare attackers
     * on MY turn" and "stop during declare attackers on THEIR turn" are
     * genuinely independent preferences. Defaults to the same set for every
     * seat until the human overrides one specifically.
     */
    private final Map<String, EnumSet<forge.game.phase.PhaseType>> stopPhasesByTurnPlayer = new java.util.HashMap<>();

    private static EnumSet<forge.game.phase.PhaseType> defaultStopPhases() {
        // Upkeep triggers (e.g. Aminatou's surveil) and mandatory decisions
        // (declare attackers/blockers, mana payment) always ask regardless
        // of stop-phase prefs - stopping at Upkeep by default just meant an
        // extra "nothing to do, pass" click most turns.
        return EnumSet.of(
                forge.game.phase.PhaseType.MAIN1,
                forge.game.phase.PhaseType.COMBAT_DECLARE_ATTACKERS,
                forge.game.phase.PhaseType.COMBAT_DECLARE_BLOCKERS,
                forge.game.phase.PhaseType.MAIN2);
    }

    private EnumSet<forge.game.phase.PhaseType> stopPhasesFor(Player turnPlayer) {
        return stopPhasesByTurnPlayer.computeIfAbsent(turnPlayer.getName(), n -> defaultStopPhases());
    }

    /**
     * Game.getPhaseHandler().getTurn() value (the GLOBAL turn counter,
     * shared by every player - whoever's turn it is) for which "End Turn"
     * was pressed. While it matches the current global turn, every
     * remaining priority window this seat gets auto-passes, regardless of
     * whether it's this seat's own turn or an opponent's - "End Turn"
     * always means "stop asking me for the rest of *this* turn," same as
     * Forge's own End Turn button, and it's available on every turn, not
     * just your own. Naturally stops applying once the global turn
     * advances. Doesn't affect mandatory decisions (declare attackers/
     * blockers, paying for something already on the stack) - those go
     * through separate controller methods that always ask.
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

    private void applyStopPhases(String forPlayerName, java.util.Set<String> phaseNames) {
        EnumSet<forge.game.phase.PhaseType> set = EnumSet.noneOf(forge.game.phase.PhaseType.class);
        for (String name : phaseNames) {
            try {
                set.add(forge.game.phase.PhaseType.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // unknown phase name from the client - skip it
            }
        }
        stopPhasesByTurnPlayer.put(forPlayerName, set);
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

    // Package-private (not private) so RemoteCostDecision, which needs the
    // same primitives to implement getCostDecisionMaker, can reuse them
    // directly instead of duplicating the request/response plumbing.
    DecisionResponse ask(String type, String prompt, List<DecisionRequest.Option> options) {
        return channel.ask(new DecisionRequest(UUID.randomUUID().toString(), type, prompt, options, safeBuildStateView(player)));
    }

    private DecisionResponse askList(String prompt, List<DecisionRequest.Option> options, int min, int max) {
        return askList(prompt, options, min, max, null, null);
    }

    private DecisionResponse askList(String prompt, List<DecisionRequest.Option> options, int min, int max, String targetIntent, String listIntent) {
        DecisionRequest req = new DecisionRequest(UUID.randomUUID().toString(), "CHOOSE_LIST", prompt, options, safeBuildStateView(player));
        req.min = min;
        req.max = max;
        req.targetIntent = targetIntent;
        req.listIntent = listIntent;
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
    <T> List<T> chooseFromList(String prompt, List<T> source, int min, int max,
            java.util.function.Function<T, String> labelFn, java.util.function.Function<T, String> cardIdFn) {
        return chooseFromList(prompt, source, min, max, labelFn, cardIdFn, null, null);
    }

    /** targetIntent is a coarse, high-confidence-only hint ("HARMFUL"/
     * "BENEFICIAL"/null) for the specific case of choosing a target for a
     * spell/ability - see chooseTargetsFor. Every other caller of this
     * method (discard, sacrifice, surveil, mode choice, ...) passes null,
     * unchanged from before this existed - a discard/sacrifice choice
     * needs "worst card", not "most threatening", so it'd be actively
     * wrong to apply targeting logic there. */
    <T> List<T> chooseFromList(String prompt, List<T> source, int min, int max,
            java.util.function.Function<T, String> labelFn, java.util.function.Function<T, String> cardIdFn, String targetIntent) {
        return chooseFromList(prompt, source, min, max, labelFn, cardIdFn, targetIntent, null);
    }

    /** listIntent is the discard/sacrifice/destroy-specific counterpart
     * to targetIntent (see DecisionRequest.listIntent) - "WORST" when
     * this player is choosing which of their own things to lose, null
     * for every other use (unchanged default behavior). */
    <T> List<T> chooseFromList(String prompt, List<T> source, int min, int max,
            java.util.function.Function<T, String> labelFn, java.util.function.Function<T, String> cardIdFn,
            String targetIntent, String listIntent) {
        if (source.isEmpty()) {
            return new ArrayList<>();
        }
        // No real decision to make if every candidate must be taken anyway
        // (min == max == the whole list, e.g. a single forced replacement
        // effect, or "discard 2" with exactly 2 cards in hand) - asking
        // anyway would just be a pointless click on every land that enters
        // tapped.
        if (min == max && min == source.size()) {
            return new ArrayList<>(source);
        }
        List<DecisionRequest.Option> options = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            T item = source.get(i);
            CardStateView cardView = item instanceof Card c ? toCardView(c) : null;
            options.add(new DecisionRequest.Option(String.valueOf(i), labelFn.apply(item),
                    cardIdFn != null ? cardIdFn.apply(item) : null, cardView));
        }
        DecisionResponse resp = askList(prompt, options, Math.max(min, 0), Math.min(max, source.size()), targetIntent, listIntent);
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
    static String cardIdOf(Object o) {
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
        RemotePlayerController viewerController = viewer.getController() instanceof RemotePlayerController rpc ? rpc : null;
        for (Player p : g.getPlayers()) {
            List<String> stopAtPhasesForP = new ArrayList<>();
            if (viewerController != null) {
                for (forge.game.phase.PhaseType pt : viewerController.stopPhasesFor(p)) {
                    stopAtPhasesForP.add(pt.name());
                }
            }
            playerViews.add(new PlayerStateView(
                    p.getName(),
                    p.getLife(),
                    p == viewer,
                    p == active,
                    p.getCardsIn(ZoneType.Hand).size(),
                    p == viewer ? toCardViews(p.getCardsIn(ZoneType.Hand)) : null,
                    toCardViews(p.getCardsIn(ZoneType.Battlefield)),
                    toCardViews(p.getCardsIn(ZoneType.Command)),
                    toCardViews(p.getCardsIn(ZoneType.Graveyard)),
                    toCardViews(p.getCardsIn(ZoneType.Exile)),
                    p.getCardsIn(ZoneType.Library).size(),
                    topOfLibraryViewFor(p),
                    floatingManaOf(p),
                    stopAtPhasesForP,
                    p.getPoisonCounters(),
                    p.getCounters(forge.game.card.CounterEnumType.ENERGY),
                    p.getCounters(forge.game.card.CounterEnumType.EXPERIENCE)));
        }
        // GameLog.getLogEntriesForTypes already returns newest-first (see
        // its own doc comment) - taking a tail slice of that, like this
        // used to do, grabs the OLDEST entries instead of the newest, and
        // since "oldest 30" never changes once the game has more than 30
        // actions, the log appeared to freeze as well as read backwards.
        List<String> log = new ArrayList<>();
        for (forge.game.GameLogEntry entry : g.getGameLog().getLogEntriesForTypes(ACTION_LOG_TYPES)) {
            log.add(entry.toString());
            if (log.size() >= 30) {
                break;
            }
        }
        forge.game.phase.PhaseType phase = g.getPhaseHandler().getPhase();

        Player monarch = g.getMonarch();
        Player hasInitiative = g.getHasInitiative();
        String dayTime = g.isDay() ? "Day" : g.isNight() ? "Night" : null;

        return new GameStateView(
                // Player.getTurn() is that player's own turn count (their
                // 1st, 2nd, 3rd... turn), not the global turn number across
                // all 4 players, which is what "Turn N" should mean here.
                active != null ? active.getTurn() : g.getPhaseHandler().getTurn(),
                phase != null ? phase.name() : "",
                active != null ? active.getName() : null,
                playerViews,
                stackItemViews(g),
                log,
                monarch != null ? monarch.getName() : null,
                hasInitiative != null ? hasInitiative.getName() : null,
                dayTime);
    }

    private List<GameStateView.StackItemView> stackItemViews(Game g) {
        List<GameStateView.StackItemView> result = new ArrayList<>();
        // MagicStack.iterator() walks the underlying deque head-first, and
        // items are pushed via addFirst - so plain iteration order is
        // already top-of-stack (most recently added, next to resolve) first.
        for (forge.game.spellability.SpellAbilityStackInstance si : g.getStack()) {
            Card source = si.getSourceCard();
            result.add(new GameStateView.StackItemView(
                    source != null ? toCardView(source) : null,
                    si.getStackDescription()));
        }
        return result;
    }

    /** Surfaces the top card of a player's library only when a static
     * grant (e.g. One with the Multiverse's "you may look at the top card
     * of your library any time") actually allows it - Card.mayPlayerLook
     * is the same check the engine itself uses for that grant, so this
     * can't leak a peek the player wouldn't legitimately have. */
    private CardStateView topOfLibraryViewFor(Player p) {
        CardCollection top = p.getTopXCardsFromLibrary(1);
        if (top.isEmpty()) {
            return null;
        }
        Card topCard = top.get(0);
        return topCard.mayPlayerLook(p) ? toCardView(topCard) : null;
    }

    private static final Map<String, Byte> MANA_COLOR_CODES = Map.of(
            "W", forge.card.MagicColor.WHITE,
            "U", forge.card.MagicColor.BLUE,
            "B", forge.card.MagicColor.BLACK,
            "R", forge.card.MagicColor.RED,
            "G", forge.card.MagicColor.GREEN,
            "C", forge.card.MagicColor.COLORLESS);

    private Map<String, Integer> floatingManaOf(Player p) {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        forge.game.mana.ManaPool pool = p.getManaPool();
        for (Map.Entry<String, Byte> e : MANA_COLOR_CODES.entrySet()) {
            int amount = pool.getAmountOfColor(e.getValue());
            if (amount > 0) {
                result.put(e.getKey(), amount);
            }
        }
        return result;
    }

    private List<CardStateView> toCardViews(Iterable<Card> cards) {
        List<CardStateView> views = new ArrayList<>();
        for (Card c : cards) {
            CardStateView view = toCardView(c);
            if (view != null) {
                views.add(view);
            }
        }
        return views;
    }

    /** Single-card version of toCardViews - also used to attach a real,
     * renderable card preview to decision options (chooseFromList), even
     * for cards sitting in a zone we never otherwise serialize (library
     * dig/search/scry/surveil candidates) - the player is legitimately
     * allowed to see these at decision time, they're just not part of the
     * normal public board state. */
    private CardStateView toCardView(Card c) {
        if (c.getType().toString().isEmpty()) {
            return null;
        }
        forge.game.combat.Combat combat = player.getGame().getCombat();
        Map<String, Integer> counters = new java.util.LinkedHashMap<>();
        for (Map.Entry<forge.game.card.CounterType, Integer> e : c.getCounters().entrySet()) {
            if (e.getValue() != null && e.getValue() != 0) {
                counters.put(e.getKey().toString(), e.getValue());
            }
        }
        boolean attacking = false;
        String attackingTarget = null;
        String blockingAttacker = null;
        if (combat != null) {
            if (combat.isAttacking(c)) {
                attacking = true;
                forge.game.GameEntity defender = combat.getDefenderByAttacker(c);
                attackingTarget = defender != null ? defender.toString() : null;
            }
            if (combat.isBlocking(c)) {
                for (Card att : combat.getAttackers()) {
                    if (combat.getBlockers(att).contains(c)) {
                        blockingAttacker = att.getName();
                        break;
                    }
                }
            }
        }
        List<String> keywords = new ArrayList<>();
        for (forge.game.keyword.KeywordInterface kw : c.getUnhiddenKeywords()) {
            String text = kw.toString();
            if (!text.isEmpty() && !keywords.contains(text)) {
                keywords.add(text);
            }
        }
        Card attachedTo = c.getAttachedTo();
        Integer commanderTax = null;
        if (c.isCommander() && c.getZone() != null && c.getZone().is(ZoneType.Command)) {
            int tax = c.getOwner().getCommanderCast(c) * 2;
            commanderTax = tax > 0 ? tax : null;
        }
        List<String> producedColors = new ArrayList<>();
        for (String code : new String[] {"W", "U", "B", "R", "G", "C"}) {
            if (c.canProduceColorMana(java.util.Set.of(code))) {
                producedColors.add(code);
            }
        }
        // same "TODO: improve detection of taplands" heuristic forge-ai's
        // own AiController uses for land-choice decisions - good enough to
        // catch the common always-tapped case, not conditional taplands.
        boolean entersTapped = false;
        for (forge.game.replacement.ReplacementEffect repl : c.getReplacementEffects()) {
            if (repl.getParamOrDefault("Description", "").equals("CARDNAME enters tapped.")) {
                entersTapped = true;
                break;
            }
        }
        return new CardStateView(
                String.valueOf(c.getId()),
                c.getName(),
                c.getManaCost() != null ? c.getManaCost().toString() : "",
                c.getType().toString(),
                c.isCreature() ? c.getNetPower() : null,
                c.isCreature() ? c.getNetToughness() : null,
                c.isTapped(),
                c.isCommander(),
                c.isSick(),
                counters,
                attacking,
                attackingTarget,
                blockingAttacker,
                !c.getManaAbilities().isEmpty(),
                c.isRoom() ? roomDoorOf(c, forge.card.CardStateName.LeftSplit) : null,
                c.isRoom() ? roomDoorOf(c, forge.card.CardStateName.RightSplit) : null,
                keywords,
                attachedTo != null ? String.valueOf(attachedTo.getId()) : null,
                commanderTax, producedColors, entersTapped, c.getController() != null && c.getController().equals(player),
                c.isCreature() ? classifyEtbRole(c) : null);
    }

    /** CardStateName.LeftSplit/RightSplit are the two fixed door slots a
     * Room card can have - Card.getUnlockedRoomNames/getLockedRoomNames
     * iterate a Set instead, so they can't be trusted to come back in a
     * stable left-then-right order for side-specific UI placement. */
    private CardStateView.RoomDoor roomDoorOf(Card c, forge.card.CardStateName side) {
        if (!c.hasState(side)) {
            return null;
        }
        return new CardStateView.RoomDoor(c.getState(side).getName(), !c.getUnlockedRooms().contains(side));
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
        // Resolve to whichever player's life total this attack actually
        // threatens - a planeswalker/battle defender doesn't have its own
        // life total, but its controller's life is still what attacking
        // *them* (the alternative defender choice) would threaten, so
        // that's the meaningful "who is this combat against" signal for
        // lethal/combat-math purposes.
        Player defendingPlayer = defender instanceof Player p ? p
                : defender instanceof Card c ? c.getController() : null;
        DecisionRequest req = new DecisionRequest(UUID.randomUUID().toString(), "DECLARE_ATTACKERS", "Declare attackers", options, safeBuildStateView(player));
        req.defenderName = defendingPlayer != null ? defendingPlayer.getName() : null;
        DecisionResponse resp = channel.ask(req);
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
        // Picks between alternative ways to play the same card (e.g. normal
        // cost vs. flashback/adventure) - was random, so an Adventure card
        // could get cast for its alternative mode without the player
        // choosing that.
        if (abilities.size() <= 1) {
            return abilities.isEmpty() ? null : abilities.get(0);
        }
        List<SpellAbility> chosen = chooseFromList("Choose how to play " + hostCard.getName(), abilities, 1, 1, SpellAbility::toString, null);
        return chosen.isEmpty() ? abilities.get(0) : chosen.get(0);
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean mayChoseNewTargets) {
        // This is how mandatory triggers resolve (e.g. Aminatou's "at the
        // beginning of your upkeep, surveil 2" - no cost, so no decider,
        // so it skips confirmTrigger and goes straight here). Routing it
        // through the embedded AI delegate (like playChosenSpellAbility's
        // comment warns against) meant any interactive decision inside the
        // trigger's resolution (arrangeForSurveil, confirmAction, etc.)
        // got silently answered by forge-ai instead of asking the real
        // controller - the trigger "worked" but the human was never asked.
        safely(() -> forge.game.player.PlaySpellAbility.playSpellAbilityNoStack(this, player, effectSA, !mayChoseNewTargets), false);
    }

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        return activePlayerSAs;
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        // This is the actual mechanism that puts queued (non-static)
        // triggers on the stack - NOT WrappedAbility.resolve(), which only
        // runs later when the stack gets around to resolving them, by
        // which point targets must already be set (it deliberately reuses
        // old targets, mayChoseNewTargets=false, matching real MTG rules:
        // you choose targets when something goes on the stack, not when it
        // resolves). Leaving this on the embedded AI delegate meant the AI
        // silently chose targets for every queued trigger needing them
        // (Extravagant Replication, Bottomless Pool's door-unlock return,
        // Spirit-Sister's Call's graveyard choice, etc.) instead of asking.
        List<SpellAbility> orderedSAs = orderSimultaneousSa(activePlayerSAs);
        for (int i = orderedSAs.size() - 1; i >= 0; i--) {
            SpellAbility next = orderedSAs.get(i);
            if (next.isTrigger() && !next.isCopied()) {
                safely(() -> forge.game.player.PlaySpellAbility.playSpellAbility(this, player, next), false);
            } else {
                if (next.isCopied()) {
                    if (next.isSpell()) {
                        if (!next.getHostCard().isInZone(ZoneType.Stack)) {
                            next.setHostCard(player.getGame().getAction().moveToStack(next.getHostCard(), next));
                        } else {
                            player.getGame().getStackZone().add(next.getHostCard());
                        }
                    }
                    if (next.isMayChooseNewTargets()) {
                        next.setupNewTargets(player);
                    }
                }
                player.getGame().getStack().add(next);
            }
        }
        pushSpectatorUpdate();
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        // Gates *static* triggers (resolve immediately, not via the stack) -
        // this is the actual path Miracle's "Drawn" trigger takes (it's
        // declared Static$ True). Mirrors PlayerControllerHuman exactly:
        // hand it to the same shared no-stack resolution routine, which
        // internally re-checks the optional decider (WrappedAbility.resolve
        // -> confirmTrigger) before doing anything - asking here ourselves
        // would either duplicate that prompt or skip it. Was a coin flip,
        // so the reveal-and-offer-to-cast sequence often silently never
        // even started.
        return safely(() -> forge.game.player.PlaySpellAbility.playSpellAbilityNoStack(this, player, wrapperAbility), false);
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        // Not a yes/no decision - this is the actual "execute the chosen
        // play" hook (PlayEffect.java's confirmAction already asked whether
        // to play it). Was a coin flip, so even after confirming "yes" via
        // that earlier prompt, the spell often never actually got cast
        // (e.g. Miracle's mana-payment step never ran).
        boolean result = safely(() -> forge.game.player.PlaySpellAbility.playSpellAbility(this, player, tgtSA), false);
        pushSpectatorUpdate();
        return result;
    }

    @Override
    public List<PaperCard> sideboard(Deck deck, GameType gameType, String message) {
        return new ArrayList<>();
    }

    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) {
        return new ArrayList<>();
    }

    /** Shared by assignCombatDamage/divideShield/specifyManaCombo: split a
     * fixed total among several recipients via repeated single-amount asks
     * (assign-to-first-recipient-until-they're-full, then move to the
     * next), rather than a true simultaneous-distribution UI. Doesn't
     * enforce MTG's "lethal first, in order" rule for combat damage - real
     * Forge's own GUI widget doesn't enforce that purely client-side
     * either (the rule is advisory at this layer); this trades strict
     * legality for a working interactive flow instead of the AI silently
     * deciding for the human. */
    private <T> Map<T, Integer> distributeAmount(String prompt, List<T> recipients, int total, java.util.function.Function<T, String> labelFn) {
        Map<T, Integer> result = new java.util.LinkedHashMap<>();
        if (recipients.isEmpty() || total <= 0) {
            return result;
        }
        List<T> remaining = new ArrayList<>(recipients);
        int left = total;
        while (left > 0 && !remaining.isEmpty()) {
            T recipient = remaining.remove(0);
            int amount = chooseNumber(null, prompt + " - assign how much to " + labelFn.apply(recipient) + "? (" + left + " remaining)", 0, left);
            if (amount > 0) {
                result.put(recipient, amount);
                left -= amount;
            }
        }
        return result;
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder) {
        List<Card> recipients = new ArrayList<>(blockers);
        Map<Card, Integer> result = distributeAmount("Assign " + attacker.getName() + "'s combat damage", recipients, damageDealt, Card::getName);
        int assigned = result.values().stream().mapToInt(Integer::intValue).sum();
        if (defender != null && assigned < damageDealt) {
            result.put(null, damageDealt - assigned);
        }
        return result;
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) {
        List<GameEntity> recipients = new ArrayList<>(affected.keySet());
        return distributeAmount("Divide " + effectSource.getName() + "'s shield", recipients, shieldAmount, GameEntity::toString);
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) {
        List<Byte> colors = new ArrayList<>();
        for (byte color : forge.card.MagicColor.WUBRG) {
            if (colorSet.hasAnyColor(color)) {
                colors.add(color);
            }
        }
        Map<Byte, Integer> result = distributeAmount("Specify mana combo for " + sa.getHostCard().getName(), colors, manaAmount,
                c -> forge.card.MagicColor.toLongString(c));
        if (different) {
            // "different" means at most 1 of each color - distributeAmount
            // doesn't enforce that cap, so clamp it here.
            result.replaceAll((k, v) -> Math.min(v, 1));
        }
        return result;
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        return new CardCollection(chooseFromList(message != null ? message : "Choose permanents to sacrifice",
                new ArrayList<>(validTargets), min, max, Card::toString, RemotePlayerController::cardIdOf, null, "WORST"));
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        return new CardCollection(chooseFromList(message != null ? message : "Choose permanents to destroy",
                new ArrayList<>(validTargets), min, max, Card::toString, RemotePlayerController::cardIdOf, null, "WORST"));
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        // Backs X-cost announcement ("announce" is typically "X") - was
        // hardcoded to min (almost always 0), so every X spell was always
        // cast for X=0 regardless of available mana.
        //
        // For cards with no explicit XMax (the common case - X is normally
        // only bounded by available mana, computed dynamically), max comes
        // in as Integer.MAX_VALUE from AbilityUtils.getAnnouncementBounds.
        // chooseNumber's int-range overload below materializes every value
        // from min to max as a checklist, so an unclamped MAX_VALUE hangs
        // the game trying to build a multi-billion-entry list (found via
        // Entreat the Angels - any X-cost card with no XMax would hit this
        // the same way). Mirror PlayerControllerHuman's real fix: clamp by
        // how much X the player could actually pay for.
        Cost cost = ability.getPayCosts();
        if ("X".equals(announce) && cost != null) {
            Integer costX = cost.getMaxForNonManaX(ability, player, false);
            if (costX != null) {
                max = Math.min(max, costX);
            }
        }
        if (min > max) {
            return null;
        }
        return chooseNumber(ability, announce != null ? "Choose a value for " + announce : "Choose a value", min, max);
    }

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
        // Redirect/retarget effects (e.g. choosing a new target for a copy
        // of a spell) - same shortcut risk as chooseTargetsFor above: this
        // used to silently let the AI pick instead of asking.
        SpellAbility sa = ability.isWrapper() ? ((WrappedAbility) ability).getWrappedAbility() : ability;
        if (!sa.usesTargeting()) {
            return null;
        }
        TargetChoices oldTarget = sa.getTargets();
        forge.game.spellability.TargetRestrictions tr = sa.getTargetRestrictions();
        Card host = sa.getHostCard();
        int min = optional ? 0 : oldTarget.size();
        int max = tr.getMaxTargets(host, sa);
        sa.clearTargets();
        // Same incremental pick-then-recompute pattern as chooseTargetsFor
        // (see its comment) - candidates must be re-derived after each
        // pick so relational constraints between the new targets are
        // actually enforced, not just checked against the old targets.
        int picked = 0;
        while (picked < max) {
            List<GameObject> candidates = new ArrayList<>(tr.getAllCandidates(sa));
            candidates.removeAll(sa.getTargets());
            if (filter != null) {
                candidates.removeIf(o -> !filter.test(o));
            }
            if (candidates.isEmpty()) {
                break;
            }
            int pickMin = picked < min ? 1 : 0;
            List<GameObject> pick = chooseFromList("Choose new targets for " + sa.getStackDescription(), candidates, pickMin, 1,
                    GameObject::toString, RemotePlayerController::cardIdOf);
            if (pick.isEmpty()) {
                break;
            }
            sa.getTargets().add(pick.get(0));
            picked++;
        }
        if (picked < min) {
            sa.setTargets(oldTarget);
            return null;
        }
        return sa.getTargets();
    }

    private static final java.util.Set<forge.game.ability.ApiType> HARMFUL_TARGET_APIS = java.util.Set.of(
            forge.game.ability.ApiType.Destroy, forge.game.ability.ApiType.DealDamage,
            forge.game.ability.ApiType.Sacrifice, forge.game.ability.ApiType.SacrificeAll,
            forge.game.ability.ApiType.GainControl, forge.game.ability.ApiType.GainControlVariant);
    private static final java.util.Set<forge.game.ability.ApiType> BENEFICIAL_TARGET_APIS = java.util.Set.of(
            forge.game.ability.ApiType.Untap, forge.game.ability.ApiType.Protection, forge.game.ability.ApiType.ProtectionAll);

    /** High-confidence-only classification of whether this ability's
     * effect is generally bad or good for whatever it targets - used so
     * the AI can point removal/disruption at the most threatening
     * candidate and buffs/protection at its own best one, instead of
     * picking blindly. Deliberately conservative: ApiTypes whose
     * direction depends on parameters (Pump's sign, PutCounter's counter
     * type) are left unclassified (null) rather than guessed, since a
     * wrong guess (treating a -1/-1 counter removal spell as beneficial)
     * would be worse than no signal at all. ChangeZone (covers exile,
     * flicker, tutor, mill, bounce, and more depending on parameters) is
     * excluded for the same reason. */
    private static String classifyTargetIntent(SpellAbility sa) {
        forge.game.ability.ApiType api = sa.getApi();
        if (api == null) {
            return null;
        }
        if (HARMFUL_TARGET_APIS.contains(api)) {
            return "HARMFUL";
        }
        if (BENEFICIAL_TARGET_APIS.contains(api)) {
            return "BENEFICIAL";
        }
        return null;
    }

    private static final java.util.Set<forge.game.ability.ApiType> SWEEPER_APIS = java.util.Set.of(
            forge.game.ability.ApiType.DestroyAll, forge.game.ability.ApiType.DamageAll,
            forge.game.ability.ApiType.SacrificeAll);
    private static final java.util.Set<forge.game.ability.ApiType> SINGLE_REMOVAL_APIS = java.util.Set.of(
            forge.game.ability.ApiType.Destroy, forge.game.ability.ApiType.DealDamage,
            forge.game.ability.ApiType.Sacrifice, forge.game.ability.ApiType.GainControl,
            forge.game.ability.ApiType.GainControlVariant);

    /** Shared ApiType -> coarse role mapping, used both for "what does
     * casting this spell do" (classifySpellRole) and "what does this
     * creature's own ETB trigger do" (classifyEtbRole) - the same
     * REMOVAL/SWEEPER/DRAW/RAMP categories apply whether the effect comes
     * from casting the spell directly or from a creature entering.
     *
     * includeSacrifice exists because bare Sacrifice (not SacrificeAll)
     * is genuinely ambiguous in direction: an Edict spell ("opponent
     * sacrifices a creature") is real removal, but the *exact same*
     * ApiType backs the extremely common ETB pattern of sacrificing one
     * of *your own* other creatures for value (Disciple of Bolas: "When
     * this enters, sacrifice another creature, gain X life and draw X
     * cards" - SacValid$ Creature.Other, no opponent involved at all).
     * Confirmed live that classifyEtbRole was misreading this exact card
     * as REMOVAL before this split - treating "I can sacrifice my own
     * stuff" as if it threatens the opponent's board would be an
     * actively wrong signal, not just a missed one. classifyEtbRole
     * passes false; classifySpellRole (where a direct Sacrifice spell is
     * far more often an Edict effect) keeps the more permissive true. */
    private static String classifyApiRole(forge.game.ability.ApiType api, boolean includeSacrifice) {
        if (api == null) {
            return null;
        }
        if (api == forge.game.ability.ApiType.Mana) {
            return "RAMP";
        }
        if (SWEEPER_APIS.contains(api)) {
            return "SWEEPER";
        }
        if (api == forge.game.ability.ApiType.Sacrifice && !includeSacrifice) {
            return null;
        }
        if (SINGLE_REMOVAL_APIS.contains(api)) {
            return "REMOVAL";
        }
        if (api == forge.game.ability.ApiType.Draw || api == forge.game.ability.ApiType.Dig) {
            return "DRAW";
        }
        return null;
    }

    /** Coarse, high-confidence-only "what does casting this spell do"
     * classification, so the AI can value a spell by its effect on the
     * current board rather than by mana cost alone (see
     * GenericSpellSequencer.valueOf). Only the cases where a wrong guess
     * would be cheap to make and clearly useful to have are classified;
     * everything ambiguous returns null and the AI falls back to its CMC
     * proxy. Order matters: a creature spell that also has an ETB removal
     * effect should still read as CREATURE (its body is the durable
     * value, scored separately via CardStateView.etbRole - see
     * classifyEtbRole), so the permanent-type check comes first. */
    private static String classifySpellRole(SpellAbility sa) {
        if (sa == null || !sa.isSpell()) {
            // Activated/mana abilities of permanents already in play aren't
            // "spells to sequence" - the sequencer ignores these anyway.
            return null;
        }
        Card host = sa.getHostCard();
        if (host != null && host.isCreature()) {
            return "CREATURE";
        }
        return classifyApiRole(sa.getApi(), true);
    }

    /** Coarse, high-confidence-only "what does this permanent's own
     * 'when this enters' trigger do" classification - without this, a
     * vanilla 4/4 and a 4/4 "when this enters, destroy target creature"
     * (or draw two cards, or ramp) score *identically*, since
     * CreatureValue/valueOf otherwise only look at power/toughness/
     * keywords. Modern Magic - Commander especially - leans heavily on
     * exactly this pattern (value creatures: an effect stapled to a
     * body), so leaving it unscored was a real, not cosmetic, gap.
     *
     * Deliberately narrow: only "Mode$ ChangesZone, Destination$
     * Battlefield, ValidCard$ ...Self..." triggers (the textbook "when
     * CARDNAME enters" pattern) are inspected - other triggers (attack
     * triggers, anthem-style statics, "whenever you cast", death
     * triggers) are a different, not-yet-attempted category, not folded
     * in here to avoid overclaiming confidence this doesn't have. Reuses
     * the exact same ApiType classification as classifySpellRole, via
     * Trigger.ensureAbility() resolving the trigger's "Execute" SVar into
     * a real SpellAbility - the same mechanism that already backs
     * targetIntent/spellRole, not a new guess. */
    private static String classifyEtbRole(Card host) {
        if (host == null) {
            return null;
        }
        for (forge.game.trigger.Trigger trig : host.getTriggers()) {
            if (trig.getMode() != forge.game.trigger.TriggerType.ChangesZone) {
                continue;
            }
            if (!"Battlefield".equals(trig.getParamOrDefault("Destination", ""))) {
                continue;
            }
            if (!trig.getParamOrDefault("ValidCard", "").contains("Self")) {
                continue;
            }
            SpellAbility etbSa = trig.ensureAbility();
            String role = classifyApiRole(etbSa != null ? etbSa.getApi() : null, false);
            if (role != null) {
                return role;
            }
        }
        return null;
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        // Real target selection (e.g. "create a token that's a copy of
        // another target permanent you control") - this is the targeting
        // step every targeted spell/triggered ability goes through, not a
        // rare edge case. Was routed through the embedded AI delegate, so
        // the AI silently picked targets instead of asking - e.g.
        // Extravagant Replication's upkeep trigger auto-targeted.
        if (!currentAbility.usesTargeting()) {
            return true;
        }
        forge.game.spellability.TargetRestrictions tr = currentAbility.getTargetRestrictions();
        Card host = currentAbility.getHostCard();
        int min = tr.getMinTargets(host, currentAbility);
        int max = tr.getMaxTargets(host, currentAbility);
        if (max == 0) {
            return min == 0;
        }
        String targetIntent = classifyTargetIntent(currentAbility);
        // Pick targets one at a time and re-derive candidates after each
        // pick (rather than computing the full candidate list once and
        // letting the player bulk-select up to max from it) - canTarget
        // checks relational constraints between targets ALREADY chosen
        // for this ability (TargetsForEachPlayer, DifferentControllers,
        // DifferentNames, WithSameCreatureType, etc.), so getAllCandidates
        // only returns a fully unconstrained list on the very first pick.
        // A bulk multi-select from that first list could let two targets
        // through that the engine would never have allowed one-at-a-time -
        // e.g. Bronzebeak Foragers ("up to one target nonland permanent
        // EACH opponent controls") could double-target one opponent.
        int picked = 0;
        while (picked < max) {
            List<GameObject> candidates = new ArrayList<>(tr.getAllCandidates(currentAbility));
            candidates.removeAll(currentAbility.getTargets());
            if (candidates.isEmpty()) {
                break;
            }
            int pickMin = picked < min ? 1 : 0;
            List<GameObject> pick = chooseFromList(currentAbility.getStackDescription(), candidates, pickMin, 1,
                    GameObject::toString, RemotePlayerController::cardIdOf, targetIntent);
            if (pick.isEmpty()) {
                break;
            }
            currentAbility.getTargets().add(pick.get(0));
            picked++;
        }
        return picked >= min;
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        // Backs "change the target of target spell" redirect effects -
        // choosing *which* of that spell's (possibly several) targets to
        // redirect. Was a random pick instead of asking.
        if (allTargets.size() <= 1) {
            return allTargets.isEmpty() ? null : allTargets.get(0);
        }
        List<Pair<SpellAbilityStackInstance, GameObject>> chosen = chooseFromList("Choose a target to change",
                allTargets, 1, 1, p -> p.getValue().toString(),
                p -> p.getValue() instanceof Card c ? cardIdOf(c) : null);
        return chosen.isEmpty() ? allTargets.get(0) : chosen.get(0);
    }

    @Override
    public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) {
        // Assist (e.g. Battalion Foot Soldier-style spells): another player
        // may pay some of the generic cost. Mirrors PlayerControllerHuman -
        // ask how much, actually pay that amount from this (the assisting)
        // player's own mana, then reduce the caster's real cost only once
        // payment succeeds. Was a hardcoded decline.
        int willPay = chooseNumber(sa, "How much would you like to help pay for Assist? (Max: " + max + ")", 0, max);
        if (willPay <= 0) {
            return true;
        }
        ManaCostBeingPaid assistCost = new ManaCostBeingPaid(ManaCost.get(willPay));
        if (applyManaToCost(assistCost, sa, "Paying for assist", null, false)) {
            cost.decreaseGenericMana(willPay);
            return true;
        }
        return false;
    }

    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa, String title, int max) {
        return chooseSingleEntityForEffect(optionList, null, sa, title, true, null, null);
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        int effectiveMin = isOptional ? 0 : min;
        return new CardCollection(chooseFromList(title != null ? title : "Choose cards", new ArrayList<>(sourceList),
                effectiveMin, max, Card::toString, RemotePlayerController::cardIdOf));
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) {
        // Each entry is its own bucket of candidates (e.g. a dig effect
        // separately offering "a creature card" and "a land card") - up to
        // one pick per bucket, same as PlayerControllerHuman. Was always
        // returning nothing chosen.
        CardCollection result = new CardCollection();
        for (Map.Entry<String, CardCollection> e : validMap.entrySet()) {
            result.addAll(chooseCardsForEffect(e.getValue(), sa,
                    (title != null ? title : "Choose cards") + " (" + e.getKey() + ")", 0, 1, isOptional, null));
        }
        return result;
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
        return chooseFromList(title != null ? title : "Choose", spells, 0, num, SpellAbility::toString, null);
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) {
        // Backs modal "choose one -" effects like Phenomenon Investigators'
        // Believe/Doubt - was a random pick, so mode choice was never
        // actually up to the player.
        List<SpellAbility> chosen = chooseFromList(title != null ? title : "Choose one", spells, 1, 1, SpellAbility::toString, null);
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode bidlife, String string, int bid, Player winner) {
        DecisionResponse resp = ask("CONFIRM", string, null);
        return resp.booleanValue != null ? resp.booleanValue : false;
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) {
        DecisionResponse resp = ask("CONFIRM", question, null);
        return resp.booleanValue != null ? resp.booleanValue : false;
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message, String logic) {
        DecisionResponse resp = ask("CONFIRM", message, null);
        return resp.booleanValue != null ? resp.booleanValue : false;
    }

    @Override
    public boolean confirmTrigger(WrappedAbility sa) {
        // Only reached for triggers with an OptionalDecider (this is what
        // backs Miracle: "Drawn" triggers with OptionalDecider$ You decide
        // whether to even reveal the card) - mandatory triggers (no
        // decider, e.g. Aminatou's upkeep surveil) never call this at all.
        // Was a coin flip; that's why Miracle never worked - half the time
        // it silently declined to reveal instead of asking.
        String prompt = "Reveal " + sa.getHostCard().getName() + " (" + sa.getStackDescription() + ")?";
        DecisionResponse resp = ask("CONFIRM", prompt, null);
        return resp.booleanValue != null ? resp.booleanValue : false;
    }

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        return chooseFromList("Choose attackers to exert", attackers, 0, attackers.size(), Card::getName, RemotePlayerController::cardIdOf);
    }

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        return chooseFromList("Choose creatures to enlist", attackers, 0, attackers.size(), Card::getName, RemotePlayerController::cardIdOf);
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        // Falls through to forge-ai's own block logic for any channel
        // that doesn't actually answer blocking decisions itself (see
        // RemoteChannel.supportsBlocking) - InProcessAiChannel's
        // heuristics never covered blocking, so it still delegates.
        // RuleBasedAiChannel and the human's WebSocketChannel both
        // override supportsBlocking to true and get asked for real: this
        // previously always fell through to the embedded delegate for
        // every seat, including the human's own defense, which meant
        // blocking was never actually interactive.
        if (!channel.supportsBlocking()) {
            withDelegateVoid(() -> delegate.declareBlockers(defender, combat));
            pushSpectatorUpdate();
            return;
        }
        // One combined request covering every attacker, instead of a
        // separate CHOOSE_LIST prompt per attacker in sequence - lets the
        // frontend show the whole combat (which creature attacks what,
        // which blockers are legal for each) at once rather than stepping
        // through attackers one by one with no visibility into the rest.
        CardCollection attackers = combat.getAttackersOf(defender);
        List<DecisionRequest.Group> groups = new ArrayList<>();
        Map<String, Card> attackerById = new java.util.LinkedHashMap<>();
        Map<String, Card> blockerById = new java.util.LinkedHashMap<>();
        for (Card attacker : attackers) {
            List<Card> candidates = new ArrayList<>();
            for (Card blocker : defender.getCreaturesInPlay()) {
                if (CombatUtil.canBlock(attacker, blocker, combat)) {
                    candidates.add(blocker);
                }
            }
            if (candidates.isEmpty()) {
                continue;
            }
            String groupId = String.valueOf(attacker.getId());
            attackerById.put(groupId, attacker);
            List<DecisionRequest.Option> options = new ArrayList<>();
            for (Card blocker : candidates) {
                String optionId = String.valueOf(blocker.getId());
                blockerById.put(optionId, blocker);
                options.add(new DecisionRequest.Option(optionId, blocker.getName(), optionId, toCardView(blocker)));
            }
            groups.add(new DecisionRequest.Group(groupId, attacker.getName() + " (attacking you)",
                    toCardView(attacker), options, 0, candidates.size()));
        }
        if (groups.isEmpty()) {
            pushSpectatorUpdate();
            return;
        }
        DecisionRequest req = new DecisionRequest(UUID.randomUUID().toString(), "DECLARE_BLOCKERS",
                "Declare blockers", null, safeBuildStateView(player));
        req.groups = groups;
        DecisionResponse resp = channel.ask(req);
        java.util.Set<Card> alreadyBlocking = new java.util.HashSet<>();
        if (resp.groupChoices != null) {
            for (DecisionRequest.Group g : groups) {
                Card attacker = attackerById.get(g.id);
                for (String optionId : resp.groupChoices.getOrDefault(g.id, List.of())) {
                    Card blocker = blockerById.get(optionId);
                    if (blocker != null && !alreadyBlocking.contains(blocker)) {
                        combat.addBlocker(attacker, blocker);
                        alreadyBlocking.add(blocker);
                    }
                }
            }
        }
        pushSpectatorUpdate();
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        // Damage assignment order among multiple blockers on the same
        // attacker - was left in original (engine) order, so it was never
        // actually up to the attacking player even when it mattered (e.g.
        // assigning lethal to a deathtouch blocker first).
        return orderCards("Order blockers of " + attacker.getName() + " (damage assignment order)", blockers);
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        CardCollection all = new CardCollection(oldBlockers);
        all.add(blocker);
        return orderCards("Order blockers of " + attacker.getName() + " (damage assignment order)", all);
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        return orderCards("Order attackers blocked by " + blocker.getName() + " (damage assignment order)", attackers);
    }

    private CardCollection orderCards(String prompt, CardCollection cards) {
        if (cards.size() <= 1) {
            return cards;
        }
        List<Card> remaining = new ArrayList<>(cards);
        List<Card> ordered = new ArrayList<>();
        while (!remaining.isEmpty()) {
            String stepPrompt = prompt + " - choose position " + (ordered.size() + 1) + " of " + cards.size();
            List<Card> pick = chooseFromList(stepPrompt, remaining, 1, 1, Card::getName, RemotePlayerController::cardIdOf);
            Card chosen = pick.isEmpty() ? remaining.get(0) : pick.get(0);
            ordered.add(chosen);
            remaining.remove(chosen);
        }
        return new CardCollection(ordered);
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
        // GameAction.scry() reverses+replays toTop one at a time, so
        // whatever order we return it in becomes the actual top-to-bottom
        // order of the library - we need to both ask which cards leave AND
        // (if more than one stays) ask what order the rest go back in,
        // instead of silently keeping their pre-scry order.
        List<Card> toBottom = chooseFromList("Scry " + topN.size() + " - choose cards to put on the BOTTOM of your library",
                new ArrayList<>(topN), 0, topN.size(), Card::toString, RemotePlayerController::cardIdOf);
        CardCollection keep = new CardCollection();
        CardCollection bottom = new CardCollection();
        for (Card c : topN) {
            (toBottom.contains(c) ? bottom : keep).add(c);
        }
        CardCollection top = orderCards("Order the cards staying on top (first = topmost)", keep);
        return new ImmutablePair<>(top, bottom);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        List<Card> toGrave = chooseFromList("Surveil " + topN.size() + " - choose cards to put into your graveyard",
                new ArrayList<>(topN), 0, topN.size(), Card::toString, RemotePlayerController::cardIdOf);
        CardCollection keep = new CardCollection();
        CardCollection grave = new CardCollection();
        for (Card c : topN) {
            (toGrave.contains(c) ? grave : keep).add(c);
        }
        CardCollection top = orderCards("Order the cards staying on top (first = topmost)", keep);
        return new ImmutablePair<>(top, grave);
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
        // Clash: after revealing the top card, each player decides for
        // themselves whether it goes back on top or to the bottom - a
        // real per-player decision, not a random tie-break. Was a coin
        // flip.
        DecisionResponse resp = ask("CONFIRM",
                "Put " + c.getName() + " on top of your library? (otherwise it goes to the bottom)", null);
        return resp.booleanValue != null ? resp.booleanValue : true;
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
                min, max, Card::toString, RemotePlayerController::cardIdOf, null, "WORST"));
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand, String[] unlessTypes, SpellAbility sa) {
        // e.g. Thirst for Meaning: "discard two cards unless you discard an
        // enchantment card" - forcing exactly `min` cards every time (the
        // old behavior here) never offered the cheaper alternative. Real
        // rule: the player may instead submit just one card of unlessTypes
        // and that alone satisfies it, regardless of `min`.
        String typeLabel = String.join(" or ", unlessTypes).toLowerCase();
        List<Card> chosen = new ArrayList<>(chooseFromList(
                "Discard " + min + " cards, or just one " + typeLabel + " card instead",
                new ArrayList<>(hand), 1, min, Card::toString, RemotePlayerController::cardIdOf, null, "WORST"));
        boolean hasQualifying = chosen.stream().anyMatch(c -> c.isValid(unlessTypes, sa.getActivatingPlayer(), sa.getHostCard(), sa));
        if (chosen.size() < min && !hasQualifying) {
            for (Card c : hand) {
                if (chosen.size() >= min) {
                    break;
                }
                if (!chosen.contains(c)) {
                    chosen.add(c);
                }
            }
        }
        return new CardCollection(chosen);
    }

    @Override
    public CardCollection chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        return new CardCollection(chooseFromList("Discard down to maximum hand size",
                new ArrayList<>(player.getCardsIn(ZoneType.Hand)), numDiscard, numDiscard, Card::toString, RemotePlayerController::cardIdOf, null, "WORST"));
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        return new CardCollection(chooseFromList("Delve - choose up to " + genericAmount + " cards to exile from your graveyard",
                new ArrayList<>(grave), 0, Math.min(genericAmount, grave.size()), Card::toString, RemotePlayerController::cardIdOf));
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) {
        // Mirrors InputSelectCardsForConvokeOrImprovise: tap untapped
        // creatures/artifacts one at a time to pay part of the cost
        // instead of mana, picking which color (or colorless, for
        // Improvise) each contributes - stopping once the cost is fully
        // paid, the cap is hit, or the player declines further taps. Was
        // always returning no cards tapped at all.
        Map<Card, ManaCostShard> chosen = new java.util.HashMap<>();
        ManaCostBeingPaid remainingCost = new ManaCostBeingPaid(manaCost);
        List<Card> remaining = new ArrayList<>(untappedCards);
        int maxSelectable = maxReduction != null ? maxReduction : Math.min(manaCost.getCMC(), untappedCards.size());
        String cardType = artifacts && creatures ? "artifact or creature" : creatures ? "creature" : "artifact";
        String description = artifacts && creatures ? "Waterbend" : creatures ? "Convoke" : "Improvise";

        while (chosen.size() < maxSelectable && !remaining.isEmpty() && !remainingCost.isPaid()) {
            List<Card> pick = chooseFromList(description + " - tap a " + cardType + " to help pay (remaining: "
                    + remainingCost + "), or none to stop", remaining, 0, 1, Card::toString, RemotePlayerController::cardIdOf);
            if (pick.isEmpty()) {
                break;
            }
            Card card = pick.get(0);
            remaining.remove(card);
            byte chosenColor;
            if (artifacts && !creatures) {
                chosenColor = ManaCostShard.COLORLESS.getColorMask();
            } else {
                ColorSet colors = card.getColor();
                if (colors.isMulticolor()) {
                    colors = ColorSet.fromMask(colors.getColor() & remainingCost.getUnpaidColors());
                }
                chosenColor = colors.isMulticolor()
                        ? chooseColorAllowColorless(description + " " + card + " - for which color?", card, colors)
                        : colors.getColor();
            }
            ManaCostShard shard = remainingCost.payManaViaConvoke(chosenColor);
            if (shard != null) {
                chosen.put(card, shard);
            }
        }
        return chosen;
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        return chooseFromList("Choose cards to splice onto " + sa.getHostCard().getName(), cards, 0, cards.size(),
                Card::toString, RemotePlayerController::cardIdOf);
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        return new CardCollection(chooseFromList("Choose cards to reveal", new ArrayList<>(valid), min, max,
                Card::toString, RemotePlayerController::cardIdOf));
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        // Rule 103.5: player picks which to activate, and the order to
        // resolve them in. Was always declining all of them.
        List<SpellAbility> chosen = chooseFromList("Choose abilities to activate from your opening hand",
                usableFromOpeningHand, 0, usableFromOpeningHand.size(), SpellAbility::toString, null);
        if (chosen.size() <= 1) {
            return chosen;
        }
        List<SpellAbility> remaining = new ArrayList<>(chosen);
        List<SpellAbility> ordered = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<SpellAbility> pick = chooseFromList("Choose the order to activate these - position "
                    + (ordered.size() + 1) + " of " + chosen.size(), remaining, 1, 1, SpellAbility::toString, null);
            SpellAbility next = pick.isEmpty() ? remaining.get(0) : pick.get(0);
            ordered.add(next);
            remaining.remove(next);
        }
        return ordered;
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        return player;
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        List<PlayerZone> chosen = chooseFromList("Choose your starting hand", zones, 1, 1, PlayerZone::toString, null);
        return chosen.isEmpty() ? pickOne(zones) : chosen.get(0);
    }

    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        List<Mana> chosen = chooseFromList("Choose which floating mana to spend", manaChoices, 1, 1, Mana::toString, null);
        return chosen.isEmpty() ? pickOne(manaChoices) : chosen.get(0);
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
        List<Object> chosen = chooseFromList(prompt != null ? prompt : "Vote", options, optional ? 0 : 1, 1, Object::toString, null);
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        return new CardCollection(chooseFromList("Choose " + cardsToReturn + " card(s) to put back",
                new ArrayList<>(hand), cardsToReturn, cardsToReturn, Card::toString, RemotePlayerController::cardIdOf));
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        pushSpectatorUpdate();
        Game g = player.getGame();
        if (autoPassEndTurnAt == g.getPhaseHandler().getTurn()) {
            return null;
        }
        if (g.getStackZone().isEmpty() && !stopPhasesFor(g.getPhaseHandler().getPlayerTurn()).contains(g.getPhaseHandler().getPhase())) {
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
        // Cards granted "may play from exile" (Aminatou's Augury, impulse
        // draw effects, etc.) sit here - getAllPossibleAbilities naturally
        // returns nothing for the vast majority of exile cards (no grant,
        // no legal way to cast them), so this doesn't flood the menu.
        for (Card c : player.getCardsIn(ZoneType.Exile)) {
            legalPlays.addAll(c.getAllPossibleAbilities(player, true));
        }
        // Same idea for "play from the top of your library" grants (Verge
        // Rangers, One with the Multiverse) - getAllPossibleAbilities
        // returns nothing for the rest of the library (face-down, no
        // grant), so this doesn't flood the menu with the whole deck.
        for (Card c : player.getCardsIn(ZoneType.Library)) {
            legalPlays.addAll(c.getAllPossibleAbilities(player, true));
        }
        if (legalPlays.isEmpty()) {
            return null;
        }

        List<DecisionRequest.Option> options = new ArrayList<>();
        for (int i = 0; i < legalPlays.size(); i++) {
            SpellAbility sa = legalPlays.get(i);
            Card host = sa.getHostCard();
            String cardId = host != null ? String.valueOf(host.getId()) : null;
            DecisionRequest.Option option = new DecisionRequest.Option(String.valueOf(i), sa.toString(), cardId, host != null ? toCardView(host) : null);
            option.spellRole = classifySpellRole(sa);
            options.add(option);
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
        if (!allowRepeat) {
            int count = Math.min(num, possible.size());
            return chooseFromList("Choose a mode", possible, Math.min(min, count), count,
                    AbilitySub::toString, null);
        }
        // Fiery Confluence-style "choose N modes, repeats allowed" - a
        // single bulk multi-select over `possible` can't express picking
        // the same entry twice, so ask one mode at a time instead, always
        // offering the full list back each time.
        List<AbilitySub> chosen = new ArrayList<>();
        for (int i = 0; i < num; i++) {
            int pickMin = chosen.size() < min ? 1 : 0;
            List<AbilitySub> pick = chooseFromList("Choose a mode (" + (i + 1) + "/" + num + ")", possible,
                    pickMin, 1, AbilitySub::toString, null);
            if (pick.isEmpty()) {
                break;
            }
            chosen.add(pick.get(0));
        }
        return chosen;
    }

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        return chooseNumber(sa, "Choose a value to reduce the cost", min, max);
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) {
        return chooseNumber(sa, prompt != null ? prompt : "Choose a value", 0, max);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        if (min >= max) {
            return min;
        }
        // Defensive cap: this enumerates every value as a checklist option,
        // unlike the real GUI's free-typed number box, so an uncapped caller
        // (e.g. an X announcement with no XMax and no other clamp applied)
        // would otherwise try to materialize a multi-billion-entry list.
        max = Math.min(max, min + 200);
        List<Integer> range = new ArrayList<>();
        for (int i = min; i <= max; i++) {
            range.add(i);
        }
        List<Integer> chosen = chooseFromList(title != null ? title : "Choose a number", range, 1, 1, String::valueOf, null);
        return chosen.isEmpty() ? min : chosen.get(0);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        List<Integer> chosen = chooseFromList(title != null ? title : "Choose a number", values, 1, 1, String::valueOf, null);
        return chosen.isEmpty() ? (values.isEmpty() ? 0 : values.get(0)) : chosen.get(0);
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultChoice) {
        DecisionResponse resp = ask("CONFIRM", question, null);
        return resp.booleanValue != null ? resp.booleanValue : (defaultChoice != null && defaultChoice);
    }

    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) {
        // Only reached when multiple coins were flipped and came up split -
        // this is a tie-break between two already-random outcomes, not a
        // real decision for the player to make, so a coin flip of our own
        // is the correct (not stubbed) behavior here.
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
        // Distinct from the single-color chooseColor above (e.g. dual
        // lands) - this backs "choose N colors" effects like Thriving
        // lands' "choose a color other than X". Was a random pick, so the
        // color choice was never actually up to the player.
        List<forge.card.MagicColor.Color> picked = chooseFromList(message != null ? message : "Choose a color",
                new ArrayList<>(options.toEnumSet()), min, max, Object::toString, null);
        return ColorSet.fromEnums(picked);
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp, String name) {
        return null;
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        List<ICardFace> chosen = chooseFromList(message != null ? message : "Choose a card face", faces, 1, 1, ICardFace::getName, null);
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message, Map<String, Object> params) {
        List<CardState> chosen = chooseFromList(message != null ? message : "Choose a state", states, 1, 1, CardState::toString, null);
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) {
        List<DecisionRequest.Option> options = List.of(
                new DecisionRequest.Option("0", "Pile 1 (" + pile1.size() + " cards)"),
                new DecisionRequest.Option("1", "Pile 2 (" + pile2.size() + " cards)"));
        DecisionResponse resp = ask("CONFIRM", "Choose a pile", options);
        return resp.chosenIds != null && !resp.chosenIds.isEmpty() && resp.chosenIds.get(0).equals("0");
    }

    @Override
    public forge.game.card.CounterType chooseCounterType(List<forge.game.card.CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        List<forge.game.card.CounterType> chosen = chooseFromList(prompt != null ? prompt : "Choose a counter type",
                options, 1, 1, forge.game.card.CounterType::toString, null);
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt, Card tgtCard) {
        List<String> chosen = chooseFromList(prompt != null ? prompt : "Choose a keyword", options, 1, 1, s -> s, null);
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    @Override
    public boolean confirmPayment(CostPart costPart, String string, SpellAbility sa) {
        DecisionResponse resp = ask("CONFIRM", string, null);
        return resp.booleanValue != null ? resp.booleanValue : false;
    }

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        // Called for every replacement event even when there's only one
        // applicable effect (e.g. "enters tapped" on basically every dual
        // land) - chooseFromList's min==max==size shortcut means that case
        // doesn't actually prompt the player.
        List<ReplacementEffect> chosen = chooseFromList("Choose a replacement effect", possibleReplacers, 1, 1, ReplacementEffect::toString, null);
        return chosen.isEmpty() ? possibleReplacers.get(0) : chosen.get(0);
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleReplacers) {
        List<StaticAbility> chosen = chooseFromList("Choose a static ability", possibleReplacers, 1, 1, StaticAbility::toString, null);
        return chosen.isEmpty() ? possibleReplacers.get(0) : chosen.get(0);
    }

    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) {
        List<String> chosen = chooseFromList("Choose protection from", choices, 1, 1, s -> s, null);
        return chosen.isEmpty() ? null : chosen.get(0);
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
        // Backs additional/optional costs like kicker - was always "decline
        // all", so kicker-style spells could never actually be kicked.
        return chooseFromList("Choose optional costs for " + choosen.getHostCard().getName(),
                optionalCostValues, 0, optionalCostValues.size(), forge.game.spellability.OptionalCostValue::toString, null);
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        return costs;
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        if (alreadyPaid) {
            return false;
        }
        DecisionResponse resp = ask("CONFIRM", "Pay " + cost + " to prevent " + sa.getHostCard().getName() + "'s effect?", null);
        return resp.booleanValue != null ? resp.booleanValue : false;
    }

    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa) {
        DecisionResponse resp = ask("CONFIRM", "Pay " + cost + "?", null);
        return resp.booleanValue != null ? resp.booleanValue : false;
    }

    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) {
        // This used to just ask a yes/no CONFIRM and return that answer
        // verbatim - meaning attackers facing a real cost (Sphere of
        // Safety's "pay {X} or this creature can't attack you") got to
        // attack for free as long as the answer was "yes", since nothing
        // ever actually paid the cost. payCostDuringAbilityResolve runs
        // the real cost-payment flow (including a PAY_MANA prompt for
        // mana costs) and only returns true once the cost is actually paid.
        return forge.game.player.PlaySpellAbility.payCostDuringAbilityResolve(this, player, cost, sa, prompt);
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
        // The single highest-impact of the remaining embedded-delegate
        // gaps: this is invoked by payment.payCost(...) for *every* cast/
        // activation with a non-mana cost component (sacrifice, discard,
        // exile, choose-a-color, etc.) - not a rare edge case. Was
        // silently letting the AI decide these for the human seat too.
        return new RemoteCostDecision(this, player, ability, effect, prompt);
    }

    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount, boolean isOptional, String prompt) {
        return new CardCollection(chooseFromList(prompt != null ? prompt : "Choose cards", new ArrayList<>(optionList),
                isOptional ? 0 : amount, amount, Card::toString, RemotePlayerController::cardIdOf));
    }

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        // Naming any card in the database (e.g. Meddling Mage-style effects)
        // would need a searchable card list UI we don't have yet. Was a
        // bare null - ChooseCardNameEffect doesn't null-check before
        // calling chosen.isEmpty(), so that crashed the game outright the
        // moment any seat (human or AI) hit one of these effects. Falling
        // back to a random valid name instead - the same fallback the
        // engine itself already uses one branch over for its "random"
        // mode - means this is merely not-smart instead of fatal.
        return forge.StaticData.instance().getCommonCards().streamAllFaces()
                .filter(cpp).collect(forge.util.StreamUtil.random())
                .map(ICardFace::getName).orElse("");
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        List<ICardFace> chosen = chooseFromList(message != null ? message : "Choose a card name", faces, 1, 1, ICardFace::getName, null);
        return chosen.isEmpty() ? null : chosen.get(0).getName();
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
