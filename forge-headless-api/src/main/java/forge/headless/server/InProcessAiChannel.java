package forge.headless.server;

import forge.headless.protocol.CardStateView;
import forge.headless.protocol.DecisionRequest;
import forge.headless.protocol.DecisionResponse;
import forge.headless.protocol.GameStateView;
import forge.headless.protocol.PlayerStateView;
import forge.headless.protocol.RemoteChannel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-process port of ai-bridge's app.py heuristics - same decision logic,
 * but as a plain in-JVM RemoteChannel instead of an HTTP round trip to a
 * separate Python process. One instance per AI seat (mirrors
 * WebSocketChannel's per-seat lifetime) - the per-turn tracking fields
 * below are intentionally instance state, not static/global like app.py's
 * module-level mutables, since a single JVM can now have several AI seats
 * (a 4-player pod) or several concurrent games (a batch run) where global
 * state would cross-contaminate between seats/games.
 *
 * Deliberately simple rule-based heuristics, same as the bridge it
 * replaces - not forge-ai. Intended as a starting baseline to build a
 * better general AI on top of in Java, where it now runs at in-process
 * speed instead of paying an HTTP round trip per decision.
 */
public class InProcessAiChannel implements RemoteChannel {
    private static final Map<String, String> BASIC_LAND_COLORS = Map.of(
            "Plains", "W", "Island", "U", "Swamp", "B", "Mountain", "R", "Forest", "G");
    private static final Pattern MANA_SYMBOL = Pattern.compile("\\{([^}]+)\\}");

    // Card ids whose cast was just cancelled mid-payment (color screw, most
    // likely, since decideSpellAbility's land-count cap is color-blind) -
    // skipped rather than retried with the exact same unpayable cast this
    // turn. Cleared every new turn since mana availability changes turn to
    // turn (a new land drawn/played) - a cast that failed early in the game
    // must not stay blacklisted forever, or that card (commander included)
    // would simply never be cast again for the rest of the game.
    private final Set<String> recentlyFailedCardIds = new HashSet<>();
    private String lastAttemptedCardId;
    private Integer lastTurnNumber;
    // Tracks consecutive PAY_MANA prompts for the exact same remaining cost
    // - the land-count check in decideSpellAbility is color-blind, so a
    // cast can still turn out unpayable (e.g. enough lands but wrong
    // colors). If auto-pay keeps getting asked for the same remaining
    // amount, it's stuck; cancel instead of retrying forever.
    private String lastPayManaPrompt;
    private int lastPayManaCount;

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
                response.booleanValue = decideMulliganKeep(state);
                break;
            case "CONFIRM":
            case "CONFIRM_ACTION":
                // Most "may" triggers are beneficial to take.
                response.booleanValue = true;
                break;
            case "DECLARE_ATTACKERS":
                response.chosenIds = decideAttackers(options, state);
                break;
            case "CHOOSE_SPELL_ABILITY":
                response.chosenIds = decideSpellAbility(options, state);
                break;
            case "PAY_MANA":
                // Always auto-pay rather than manually tapping one land at
                // a time - the manual tap-by-tap flow only makes sense for
                // a real interactive (human) session.
                response.chosenIds = decidePayMana(options, request.prompt);
                break;
            case "CHOOSE_LIST":
                response.chosenIds = decideList(options, request.min);
                break;
            default:
                if (!options.isEmpty()) {
                    // Generic fallback for any other option-based decision
                    // (e.g. CHOOSE_COLOR) not special-cased above: pick the
                    // first rather than falling through to booleanValue,
                    // which the engine would just ignore for these types.
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

    private static PlayerStateView you(GameStateView state) {
        if (state == null || state.players == null) {
            return null;
        }
        for (PlayerStateView p : state.players) {
            if (p.isYou) {
                return p;
            }
        }
        return null;
    }

    private static int manaValue(String manaCost) {
        if (manaCost == null || manaCost.isEmpty()) {
            return 0;
        }
        int total = 0;
        Matcher m = MANA_SYMBOL.matcher(manaCost);
        while (m.find()) {
            String symbol = m.group(1);
            if (symbol.chars().allMatch(Character::isDigit) && !symbol.isEmpty()) {
                total += Integer.parseInt(symbol);
            } else if (!symbol.equals("X") && !symbol.equals("Y") && !symbol.equals("Z")) {
                total += 1;
            }
        }
        return total;
    }

    private static Set<String> colorsInCost(String manaCost) {
        Set<String> colors = new HashSet<>();
        if (manaCost == null) {
            return colors;
        }
        Matcher m = MANA_SYMBOL.matcher(manaCost);
        while (m.find()) {
            String symbol = m.group(1);
            if (symbol.length() == 1 && "WUBRG".contains(symbol)) {
                colors.add(symbol);
            }
        }
        return colors;
    }

    /** Best-effort affordability check from typeLine/name alone (no real
     * mana-ability simulation here either). Basic lands are checked
     * exactly; anything else (duals, Command Tower, signets-as-lands,
     * etc.) is assumed able to produce any color, since there's no simple
     * signal for that from the card view - this only catches the common,
     * glaring case of a hand/board with the wrong basic-land colors for a
     * multicolor commander, not every possible color screw, but that's
     * the case that otherwise makes a tri-color commander look
     * "castable" by raw land count every turn while silently failing on
     * mana every time. */
    private static boolean canPlausiblyProduce(CardStateView land, String color) {
        String mapped = BASIC_LAND_COLORS.get(land.name);
        return mapped == null || mapped.equals(color);
    }

    private static Map<String, CardStateView> cardsById(GameStateView state) {
        PlayerStateView you = you(state);
        Map<String, CardStateView> map = new HashMap<>();
        if (you == null) {
            return map;
        }
        for (CardStateView c : you.hand != null ? you.hand : List.<CardStateView>of()) {
            map.put(c.id, c);
        }
        for (CardStateView c : you.battlefield != null ? you.battlefield : List.<CardStateView>of()) {
            map.put(c.id, c);
        }
        for (CardStateView c : you.commandZone != null ? you.commandZone : List.<CardStateView>of()) {
            map.put(c.id, c);
        }
        return map;
    }

    private boolean decideMulliganKeep(GameStateView state) {
        PlayerStateView you = you(state);
        List<CardStateView> hand = you != null && you.hand != null ? you.hand : List.of();
        long lands = hand.stream().filter(c -> c.typeLine != null && c.typeLine.contains("Land")).count();
        return lands >= 2 && lands <= 5;
    }

    private List<String> decideAttackers(List<DecisionRequest.Option> options, GameStateView state) {
        Map<String, CardStateView> byId = cardsById(state);
        List<String> chosen = new ArrayList<>();
        for (DecisionRequest.Option o : options) {
            CardStateView card = o.cardId != null ? byId.get(o.cardId) : null;
            // Skip 0-power attackers - they can't profitably do anything
            // by attacking and just walk into a bad block for free.
            if (card == null || (card.power != null ? card.power : 0) > 0) {
                chosen.add(o.id);
            }
        }
        return chosen;
    }

    private List<String> decideList(List<DecisionRequest.Option> options, Integer min) {
        int minN = min != null ? min : 0;
        List<String> chosen = new ArrayList<>();
        // Default to the minimum required - a safe, predictable choice
        // that doesn't accidentally surveil/discard/sacrifice more than
        // necessary.
        for (int i = 0; i < Math.min(minN, options.size()); i++) {
            chosen.add(options.get(i).id);
        }
        return chosen;
    }

    private List<String> decidePayMana(List<DecisionRequest.Option> options, String prompt) {
        if (prompt != null && prompt.equals(lastPayManaPrompt)) {
            lastPayManaCount++;
        } else {
            lastPayManaPrompt = prompt;
            lastPayManaCount = 1;
        }
        if (lastPayManaCount >= 3) {
            for (DecisionRequest.Option o : options) {
                if (o.id.equals("__CANCEL__")) {
                    if (lastAttemptedCardId != null) {
                        recentlyFailedCardIds.add(lastAttemptedCardId);
                    }
                    return List.of(o.id);
                }
            }
        }
        for (DecisionRequest.Option o : options) {
            if (o.id.equals("__AUTO__")) {
                return List.of(o.id);
            }
        }
        return List.of();
    }

    private List<String> decideSpellAbility(List<DecisionRequest.Option> options, GameStateView state) {
        for (DecisionRequest.Option o : options) {
            if ("Play land".equals(o.label)) {
                lastAttemptedCardId = null;
                return List.of(o.id);
            }
        }

        PlayerStateView you = you(state);
        // Only consider options that cast something from hand/command zone
        // - battlefield-card options here are activated/mana abilities,
        // which have no real mana cost and would otherwise always "win"
        // as the cheapest pick, looping forever without ever progressing
        // the turn.
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

        // A successful cast removes the card from hand/command zone, so if
        // the exact card we just told the engine to cast is *still*
        // castable right now, the attempt failed before ever reaching
        // PAY_MANA (most likely: no legal target exists yet, e.g. a
        // removal spell with no creatures to hit) - decidePayMana's retry
        // counter never saw this case at all, since payment was never even
        // reached. Without this, that card gets offered (and silently
        // fails) every single priority window for the rest of the game.
        if (lastAttemptedCardId != null && castableIds.containsKey(lastAttemptedCardId)) {
            recentlyFailedCardIds.add(lastAttemptedCardId);
        }

        DecisionRequest.Option best = null;
        int bestCmc = -1;
        boolean bestIsCommander = false;
        for (DecisionRequest.Option o : options) {
            String cardId = o.cardId;
            if (cardId == null || !castableIds.containsKey(cardId) || recentlyFailedCardIds.contains(cardId)) {
                continue;
            }
            CardStateView card = castableIds.get(cardId);
            int cmc = manaValue(card.manaCost);
            // Rough affordability check: land count for the generic
            // portion, plus (since a multicolor card can look "affordable"
            // by raw count while actually being uncastable every turn on
            // the wrong basics) at least one plausible source for each
            // colored pip required.
            if (cmc > numLands) {
                continue;
            }
            boolean colorOk = true;
            for (String color : colorsInCost(card.manaCost)) {
                boolean hasSource = battlefieldLands.stream().anyMatch(land -> canPlausiblyProduce(land, color));
                if (!hasSource) {
                    colorOk = false;
                    break;
                }
            }
            if (!colorOk) {
                continue;
            }
            // Getting the commander onto the battlefield as soon as
            // affordable is almost always the strongest play in Commander
            // - it outranks plain highest-CMC, but a higher-priority
            // commander option found later still wins over an earlier
            // non-commander pick.
            boolean isCommander = card.isCommander;
            if (best != null && bestIsCommander && !isCommander) {
                continue;
            }
            if (isCommander && !bestIsCommander) {
                bestCmc = cmc;
                best = o;
                bestIsCommander = true;
                continue;
            }
            if (cmc > bestCmc) {
                bestCmc = cmc;
                best = o;
                bestIsCommander = isCommander;
            }
        }
        lastAttemptedCardId = best != null ? best.cardId : null;
        return best != null ? List.of(best.id) : List.of();
    }
}
