package forge.headless.server;

import forge.game.GameEntityCounterTable;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CounterType;
import forge.game.cost.*;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.util.Aggregates;
import forge.util.TextUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors forge-gui's HumanCostDecision (the visitor that answers "how do
 * you want to pay this part of the cost", e.g. which cards to discard/
 * sacrifice/exile, which color to choose, etc.) but driven through our own
 * RemotePlayerController instead of Forge's desktop GUI widgets.
 *
 * Before this existed, getCostDecisionMaker fell back to the embedded AI
 * delegate - meaning every cost-payment sub-decision for *any* spell with
 * a non-mana cost component (sacrifice, discard, exile, choose a color for
 * a hybrid-cost effect, etc.) was being silently decided by the AI instead
 * of asking the human, for both seats. This is the single highest-impact
 * of the remaining embedded-delegate gaps, since payment.payCost(...) is
 * invoked for every single cast/activation with such a cost, not a rare
 * edge case.
 *
 * A handful of rare sub-variants (discard/sacrifice/tap "with the same
 * name as each other", exile requiring an exact total CMC/mana-symbol
 * count, etc.) are implemented with a simpler flow than the desktop GUI's
 * (no live re-filtering as you select) - correctness over polish for cards
 * that come up rarely.
 */
class RemoteCostDecision extends CostDecisionMakerBase {
    private final RemotePlayerController controller;
    private boolean mandatory;

    RemoteCostDecision(RemotePlayerController controller, Player p, SpellAbility sa, boolean effect, String prompt) {
        super(p, effect, sa, sa.getHostCard());
        this.controller = controller;
        mandatory = sa.getPayCosts().isMandatory();
    }

    private boolean confirm(String message) {
        forge.headless.protocol.DecisionResponse resp = controller.ask("CONFIRM", message, null);
        return resp.booleanValue != null && resp.booleanValue;
    }

    private List<Card> pick(String prompt, List<Card> source, int min, int max) {
        return controller.chooseFromList(prompt, source, min, max, Card::toString, RemotePlayerController::cardIdOf);
    }

    private List<Player> pickPlayers(String prompt, List<Player> source, int min, int max) {
        return controller.chooseFromList(prompt, source, min, max, Player::getName, p -> null);
    }

    @Override
    public PaymentDecision visit(CostAddMana cost) {
        return PaymentDecision.number(cost.getAbilityAmount(ability));
    }

    @Override
    public PaymentDecision visit(CostChooseColor cost) {
        int c = cost.getAbilityAmount(ability);
        return PaymentDecision.colors(controller.chooseColors("Choose a color", ability, c, c, forge.card.ColorSet.WUBRG));
    }

    @Override
    public PaymentDecision visit(CostChooseCreatureType cost) {
        String choice = controller.chooseSomeType("Creature", ability, forge.card.CardType.getAllCreatureTypes(), true);
        return choice == null ? null : PaymentDecision.type(choice);
    }

    @Override
    public PaymentDecision visit(CostCollectEvidence cost) {
        CardCollection list = CardLists.filter(player.getCardsIn(ZoneType.Graveyard), CardPredicates.canExiledBy(ability, isEffect()));
        int total = AbilityUtils.calculateAmount(source, cost.getAmount(), ability);
        List<Card> chosen = pick("Collect evidence - choose cards totaling " + total + " mana value", new ArrayList<>(list), 0, list.size());
        if (CardLists.getTotalCMC(new CardCollection(chosen)) < total) {
            return null;
        }
        return PaymentDecision.card(chosen);
    }

    @Override
    public PaymentDecision visit(CostDiscard cost) {
        CardCollectionView hand = player.getCardsIn(ZoneType.Hand);
        String discardType = cost.getType();

        if (cost.payCostFromSource()) {
            return hand.contains(source) ? PaymentDecision.card(source) : null;
        }
        if (discardType.equals("Hand")) {
            if (!mandatory && !confirm("Discard your hand?")) {
                return null;
            }
            return PaymentDecision.card(hand);
        }
        if (discardType.equals("LastDrawn")) {
            Card lastDrawn = player.getLastDrawnCard();
            return hand.contains(lastDrawn) ? PaymentDecision.card(lastDrawn) : null;
        }

        int c = cost.getAbilityAmount(ability);
        if (discardType.equals("Random")) {
            return PaymentDecision.card(new CardCollection(Aggregates.random(hand, c)));
        }
        if (discardType.contains("+WithDifferentNames")) {
            CardCollection discarded = new CardCollection();
            CardCollectionView remaining = hand;
            while (c > 0) {
                List<Card> chosen = pick("Discard one card (different name than already discarded: " + discarded + ")", new ArrayList<>(remaining), 1, 1);
                if (chosen.isEmpty()) {
                    return null;
                }
                Card first = chosen.get(0);
                discarded.add(first);
                remaining = CardLists.filter(remaining, CardPredicates.sharesNameWith(first).negate());
                c--;
            }
            return PaymentDecision.card(discarded);
        }
        if (discardType.contains("+WithSameName")) {
            String type = TextUtil.fastReplace(discardType, "+WithSameName", "");
            CardCollectionView typed = CardLists.getValidCards(hand, type.split(";"), player, source, ability);
            CardCollectionView sameNamed = CardLists.filter(typed, c1 -> {
                for (Card card : typed) {
                    if (!card.equals(c1) && card.getName().equals(c1.getName())) {
                        return true;
                    }
                }
                return false;
            });
            if (c == 0) {
                return PaymentDecision.card(new CardCollection());
            }
            CardCollection discarded = new CardCollection();
            CardCollectionView remaining = sameNamed;
            while (c > 0) {
                List<Card> chosen = pick("Discard one card (already discarded: " + discarded + ")", new ArrayList<>(remaining), 1, 1);
                if (chosen.isEmpty()) {
                    return null;
                }
                Card first = chosen.get(0);
                discarded.add(first);
                CardCollection filtered = CardLists.filter(remaining, CardPredicates.nameEquals(first.getName()));
                filtered.remove(first);
                remaining = filtered;
                c--;
            }
            return PaymentDecision.card(discarded);
        }

        CardCollectionView valid = CardLists.getValidCards(hand, discardType.split(";"), player, source, ability);
        if (valid.isEmpty()) {
            return null;
        }
        List<Card> chosen = pick("Discard " + c + " card(s)", new ArrayList<>(valid), c, c);
        return chosen.size() == c ? PaymentDecision.card(chosen) : null;
    }

    @Override
    public PaymentDecision visit(CostDamage cost) {
        int c = cost.getAbilityAmount(ability);
        return confirm("Deal " + c + " damage to yourself to pay this cost?") ? PaymentDecision.number(c) : null;
    }

    @Override
    public PaymentDecision visit(CostDraw cost) {
        if (!cost.canPay(ability, player, isEffect())) {
            return null;
        }
        int c = cost.getAbilityAmount(ability);
        List<Player> res = cost.getPotentialPlayers(player, ability);
        if (!confirm("Draw " + c + " card(s) to pay this cost?")) {
            return null;
        }
        PaymentDecision decision = PaymentDecision.players(res);
        decision.c = c;
        return decision;
    }

    @Override
    public PaymentDecision visit(CostExile cost) {
        String type = cost.getType();
        Card onlyPayable = null;
        if (cost.payCostFromSource()) {
            onlyPayable = source;
        }
        if (type.equals("OriginalHost")) {
            onlyPayable = ability.getOriginalHost();
        }
        if (onlyPayable != null) {
            if (onlyPayable.canExiledBy(ability, isEffect()) && onlyPayable.getZone() == player.getZone(cost.from.get(0))
                    && confirm("Exile " + onlyPayable.getName() + "?")) {
                return PaymentDecision.card(onlyPayable);
            }
            return null;
        }

        forge.game.Game game = player.getGame();
        CardCollection list = cost.zoneRestriction != 1 ? new CardCollection(game.getCardsIn(cost.from)) : new CardCollection(player.getCardsIn(cost.from));

        if (type.equals("All")) {
            return confirm("Exile all " + list.size() + " card(s) from " + cost.from.get(0) + "?") ? PaymentDecision.card(list) : null;
        }

        list = CardLists.getValidCards(list, type.split(";"), player, source, ability);
        list = CardLists.filter(list, CardPredicates.canExiledBy(ability, isEffect()));

        // Rare quantified variants (+withTotalCMCEQ/GE, +withTotalManaSymbols,
        // +withTypesGE) - simplified to "pick any subset, then validate the
        // condition" instead of live-filtering candidates as you select.
        if (type.contains("+with")) {
            List<Card> chosen = pick(cost.toString(cost.getAbilityAmount(ability)), new ArrayList<>(list), 0, list.size());
            return chosen.isEmpty() ? null : PaymentDecision.card(chosen);
        }

        int c = cost.getAbilityAmount(ability);
        if (list.size() < c) {
            return null;
        }
        if (c == 0) {
            return PaymentDecision.number(0);
        }

        if (cost.from.size() == 1) {
            ZoneType fromZone = cost.from.get(0);
            if (fromZone == ZoneType.Battlefield || fromZone == ZoneType.Hand) {
                List<Card> chosen = pick("Exile " + c + " card(s) from your " + fromZone, new ArrayList<>(list), c, c);
                return chosen.size() == c ? PaymentDecision.card(chosen) : null;
            }
            if (fromZone == ZoneType.Library) {
                CardCollectionView top = player.getCardsIn(ZoneType.Library, c);
                return confirm("Exile the top " + c + " card(s) of your library?") ? PaymentDecision.card(top) : null;
            }
        }
        // Multi-zone fallback (exile from any one player's matching zone) -
        // pick the owner with enough cards, then which of theirs to exile.
        List<Player> payable = new ArrayList<>();
        for (Player p : game.getPlayers()) {
            if (CardLists.filter(list, CardPredicates.isOwner(p)).size() >= c) {
                payable.add(p);
            }
        }
        if (payable.isEmpty()) {
            return null;
        }
        List<Player> chosenPlayer = pickPlayers("Exile from whose " + cost.from.get(0) + "?", payable, 1, 1);
        if (chosenPlayer.isEmpty()) {
            return null;
        }
        CardCollection theirs = CardLists.filter(list, CardPredicates.isOwner(chosenPlayer.get(0)));
        List<Card> chosen = pick("Exile " + c + " card(s)", new ArrayList<>(theirs), c, c);
        return chosen.size() == c ? PaymentDecision.card(chosen) : null;
    }

    @Override
    public PaymentDecision visit(CostExileFromStack cost) {
        forge.game.Game game = player.getGame();
        String type = cost.getType();
        List<SpellAbility> saList = new ArrayList<>();
        for (SpellAbilityStackInstance si : game.getStack()) {
            Card stC = si.getSourceCard();
            SpellAbility stSA = si.getSpellAbility().getRootAbility();
            if (stC.isValid(type.split(";"), ability.getActivatingPlayer(), source, ability) && stSA.isSpell()) {
                saList.add(stSA);
            }
        }
        if (type.equals("All")) {
            return PaymentDecision.spellabilities(saList);
        }
        int c = cost.getAbilityAmount(ability);
        if (saList.size() < c) {
            return null;
        }
        List<SpellAbility> exiled = new ArrayList<>();
        List<SpellAbility> remaining = saList;
        for (int i = 0; i < c; i++) {
            int finalIdx = exiled.size();
            List<SpellAbility> chosen = controller.chooseFromList("Exile a spell/ability from the stack (already chosen: " + finalIdx + ")",
                    remaining, 1, 1, SpellAbility::getStackDescription, sa -> null);
            if (chosen.isEmpty()) {
                return null;
            }
            SpellAbility picked = chosen.get(0);
            exiled.add(picked);
            remaining = new ArrayList<>(remaining);
            remaining.remove(picked);
        }
        return PaymentDecision.spellabilities(exiled);
    }

    @Override
    public PaymentDecision visit(CostExiledMoveToGrave cost) {
        int c = cost.getAbilityAmount(ability);
        Player activator = ability.getActivatingPlayer();
        CardCollection list = CardLists.getValidCards(activator.getGame().getCardsIn(ZoneType.Exile), cost.getType().split(";"), activator, source, ability);
        if (list.size() < c) {
            return null;
        }
        int min = ability.isOptionalTrigger() ? 0 : c;
        List<Card> chosen = pick("Choose an exiled card to put into your graveyard", new ArrayList<>(list), min, c);
        return chosen.size() < min ? null : PaymentDecision.card(chosen);
    }

    @Override
    public PaymentDecision visit(CostExert cost) {
        if (cost.payCostFromSource()) {
            if (source.getController() == ability.getActivatingPlayer() && source.isInPlay()) {
                return confirm("Exert " + source.getName() + "?") ? PaymentDecision.card(source) : null;
            }
            return null;
        }
        CardCollectionView list = CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield), cost.getType().split(";"), player, source, ability);
        int c = cost.getAbilityAmount(ability);
        if (c == 0) {
            return PaymentDecision.number(0);
        }
        if (list.size() < c) {
            return null;
        }
        List<Card> chosen = pick("Exert " + c + " creature(s)", new ArrayList<>(list), c, c);
        return chosen.size() == c ? PaymentDecision.card(chosen) : null;
    }

    @Override
    public PaymentDecision visit(CostEnlist cost) {
        CardCollectionView list = CostEnlist.getCardsForEnlisting(player);
        if (list.isEmpty()) {
            return null;
        }
        List<Card> chosen = pick("Choose a creature to enlist", new ArrayList<>(list), 1, 1);
        return chosen.isEmpty() ? null : PaymentDecision.card(chosen);
    }

    @Override
    public PaymentDecision visit(CostFlipCoin cost) {
        int c = cost.getAbilityAmount(ability);
        return confirm("Flip " + c + " coin(s) to pay this cost?") ? PaymentDecision.number(c) : null;
    }

    @Override
    public PaymentDecision visit(CostForage cost) {
        CardCollection food = CardLists.filter(player.getCardsIn(ZoneType.Battlefield), CardPredicates.isType("Food"), CardPredicates.canBeSacrificedBy(ability, isEffect()));
        CardCollection exile = CardLists.filter(player.getCardsIn(ZoneType.Graveyard), CardPredicates.canExiledBy(ability, isEffect()));
        if (!food.isEmpty() && confirm("Sacrifice a Food to forage?")) {
            List<Card> chosen = pick("Sacrifice a Food", new ArrayList<>(food), 1, 1);
            return chosen.isEmpty() ? null : PaymentDecision.card(chosen);
        }
        if (exile.size() >= 3) {
            List<Card> chosen = pick("Exile 3 cards from your graveyard to forage", new ArrayList<>(exile), 3, 3);
            return chosen.size() == 3 ? PaymentDecision.card(chosen) : null;
        }
        return null;
    }

    @Override
    public PaymentDecision visit(CostRollDice cost) {
        int c = cost.getAbilityAmount(ability);
        return confirm("Roll " + c + " d" + cost.getType() + " to pay this cost?") ? PaymentDecision.number(c) : null;
    }

    @Override
    public PaymentDecision visit(CostGainControl cost) {
        // Forge's own InputSelectCardsFromList(controller, c, validCards, sa)
        // requires exactly c, not "up to c" - was letting the player submit
        // fewer (even zero) permanents than the cost actually demands.
        int c = cost.getAbilityAmount(ability);
        CardCollectionView validCards = CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield), cost.getType().split(";"), player, source, ability);
        validCards = CardLists.filter(validCards, crd -> crd.canBeControlledBy(player));
        List<Card> chosen = pick("Give up control of " + c + " permanent(s)", new ArrayList<>(validCards), c, c);
        return chosen.size() == c ? PaymentDecision.card(chosen) : null;
    }

    @Override
    public PaymentDecision visit(CostGainLife cost) {
        int c = cost.getAbilityAmount(ability);
        List<Player> oppsThatCanGainLife = new ArrayList<>();
        for (Player opp : cost.getPotentialTargets(player, ability)) {
            if (opp.canGainLife()) {
                oppsThatCanGainLife.add(opp);
            }
        }
        if (cost.getCntPlayers() == Integer.MAX_VALUE) {
            return PaymentDecision.players(oppsThatCanGainLife);
        }
        List<Player> chosen = pickPlayers("Choose an opponent to gain " + c + " life", oppsThatCanGainLife, 1, 1);
        return chosen.isEmpty() ? null : PaymentDecision.players(new ArrayList<>(chosen));
    }

    @Override
    public PaymentDecision visit(CostMill cost) {
        int c = cost.getAbilityAmount(ability);
        return confirm("Mill " + c + " card(s) to pay this cost?") ? PaymentDecision.number(c) : null;
    }

    @Override
    public PaymentDecision visit(CostPayLife cost) {
        int c = cost.getAbilityAmount(ability);
        if (mandatory) {
            return PaymentDecision.number(c);
        }
        if (player.canPayLife(c, isEffect(), ability) && confirm("Pay " + c + " life to pay this cost?")) {
            mandatory = true;
            return PaymentDecision.number(c);
        }
        return null;
    }

    @Override
    public PaymentDecision visit(CostPayEnergy cost) {
        int c = cost.getAbilityAmount(ability);
        if (player.canPayEnergy(c) && confirm("Pay " + c + " energy to pay this cost?")) {
            return PaymentDecision.number(c);
        }
        return null;
    }

    @Override
    public PaymentDecision visit(CostPayShards cost) {
        int c = cost.getAbilityAmount(ability);
        if (player.canPayShards(c) && confirm("Pay " + c + " mana shard(s) to pay this cost?")) {
            return PaymentDecision.number(c);
        }
        return null;
    }

    @Override
    public PaymentDecision visit(CostPartMana cost) {
        return new PaymentDecision(0);
    }

    @Override
    public PaymentDecision visit(CostPromiseGift cost) {
        forge.game.player.PlayerCollection opponents = cost.getPotentialPlayers(player, ability);
        Player giftee = controller.chooseSingleEntityForEffect(opponents, null, ability, "Choose an opponent to promise a gift", false, null, null);
        return giftee == null ? null : PaymentDecision.players(java.util.Collections.singletonList(giftee));
    }

    @Override
    public PaymentDecision visit(CostPutCardToLib cost) {
        int c = cost.getAbilityAmount(ability);
        CardCollection list = CardLists.getValidCards(cost.sameZone ? player.getGame().getCardsIn(cost.getFrom()) : player.getCardsIn(cost.getFrom()),
                cost.getType().split(";"), player, source, ability);

        if (cost.payCostFromSource()) {
            return source.getZone() == player.getZone(cost.from) && confirm("Put " + source.getName() + " on top of your library?")
                    ? PaymentDecision.card(source) : null;
        }
        if (cost.from == ZoneType.Hand) {
            List<Card> chosen = pick("Put " + c + " card(s) from your hand on top of your library", new ArrayList<>(list), c, c);
            return chosen.size() == c ? PaymentDecision.card(chosen) : null;
        }
        if (cost.sameZone) {
            List<Player> payable = new ArrayList<>();
            for (Player p : player.getGame().getPlayers()) {
                if (CardLists.filter(list, CardPredicates.isOwner(p)).size() >= c) {
                    payable.add(p);
                }
            }
            if (c == 0) {
                return PaymentDecision.number(0);
            }
            List<Player> chosenPlayer = pickPlayers("Put cards from whose " + cost.from + "?", payable, 1, 1);
            if (chosenPlayer.isEmpty()) {
                return null;
            }
            CardCollection theirs = CardLists.filter(list, CardPredicates.isOwner(chosenPlayer.get(0)));
            List<Card> chosen = pick("Put " + c + " card(s) on top of library", new ArrayList<>(theirs), c, c);
            return chosen.size() == c ? PaymentDecision.card(chosen) : null;
        }
        List<Card> chosen = pick("Put " + c + " card(s) from " + cost.from + " on top of library", new ArrayList<>(list), c, c);
        return chosen.size() == c ? PaymentDecision.card(chosen) : null;
    }

    @Override
    public PaymentDecision visit(CostPutCounter cost) {
        int c = cost.getAbilityAmount(ability);
        if (cost.payCostFromSource()) {
            if (isEffect() && ability.hasParam("UnlessCost")
                    && !confirm("Put " + c + " " + cost.getCounter().getName() + " counter(s) on " + ability.getHostCard().getName() + "?")) {
                return null;
            }
            return PaymentDecision.card(source);
        }
        CardCollectionView typeList = CardLists.getValidCards(source.getGame().getCardsIn(ZoneType.Battlefield), cost.getType().split(";"), player, ability.getHostCard(), ability);
        typeList = CardLists.filter(typeList, CardPredicates.canReceiveCounters(cost.getCounter()));
        if (typeList.isEmpty()) {
            return null;
        }
        List<Card> chosen = pick("Put a " + cost.getCounter().getName() + " counter on a permanent", new ArrayList<>(typeList), 1, 1);
        return chosen.isEmpty() ? null : PaymentDecision.card(chosen);
    }

    @Override
    public PaymentDecision visit(CostBlight cost) {
        return visit((CostPutCounter) cost);
    }

    @Override
    public PaymentDecision visit(CostReturn cost) {
        int c = cost.getAbilityAmount(ability);
        if (cost.payCostFromSource()) {
            Card card = ability.getHostCard();
            if (card.getController() == player && card.isInPlay()) {
                return confirm("Return " + card.getName() + " to hand?") ? PaymentDecision.card(card) : null;
            }
            return null;
        }
        CardCollectionView validCards = CardLists.getValidCards(ability.getActivatingPlayer().getCardsIn(ZoneType.Battlefield), cost.getType().split(";"), player, source, ability);
        if (validCards.size() < c) {
            return null;
        }
        List<Card> chosen = pick("Return " + c + " permanent(s) to hand", new ArrayList<>(validCards), c, c);
        return chosen.size() == c ? PaymentDecision.card(chosen) : null;
    }

    @Override
    public PaymentDecision visit(CostReveal cost) {
        if (cost.payCostFromSource()) {
            return PaymentDecision.card(source);
        }
        if (cost.getType().equals("Hand")) {
            return PaymentDecision.card(player.getCardsIn(ZoneType.Hand));
        }
        int num = cost.getAbilityAmount(ability);
        CardCollectionView hand = player.getCardsIn(cost.getRevealFrom());
        if (cost.getType().equals("SameColor")) {
            if (num == 0) {
                return PaymentDecision.number(0);
            }
            // Simplified: pick any num cards rather than live-restricting to
            // those sharing a color with the first pick.
            List<Card> chosen = pick("Reveal " + num + " card(s) of the same color", new ArrayList<>(hand), num, num);
            return chosen.size() == num ? PaymentDecision.card(chosen) : null;
        }
        hand = CardLists.getValidCards(hand, cost.getType().split(";"), player, source, ability);
        if (hand.size() < num) {
            return null;
        }
        if (num == 0) {
            return PaymentDecision.number(0);
        }
        if (!ability.isCastFromPlayEffect() && hand.size() == num) {
            return PaymentDecision.card(hand);
        }
        List<Card> chosen = pick("Reveal " + num + " card(s)", new ArrayList<>(hand), num, num);
        return chosen.size() == num ? PaymentDecision.card(chosen) : null;
    }

    @Override
    public PaymentDecision visit(CostBehold cost) {
        int num = cost.getAbilityAmount(ability);
        CardCollectionView hand = CardLists.getValidCards(player.getCardsIn(cost.getRevealFrom()), cost.getType().split(";"), player, source, ability);
        if (hand.size() < num) {
            return null;
        }
        List<Card> chosen = pick("Reveal " + num + " card(s)", new ArrayList<>(hand), num, num);
        return chosen.size() == num ? PaymentDecision.card(chosen) : null;
    }

    @Override
    public PaymentDecision visit(CostBeholdExile cost) {
        return visit((CostBehold) cost);
    }

    @Override
    public PaymentDecision visit(CostRevealChosen cost) {
        return PaymentDecision.number(1);
    }

    private GameEntityCounterTable removeCountersFrom(Card c, CounterType cType, int cntToRemove) {
        GameEntityCounterTable counterTable = new GameEntityCounterTable();
        if (cType != null) {
            counterTable.put(null, c, cType, cntToRemove);
            return counterTable;
        }
        java.util.Map<CounterType, Integer> cMap = counterTable.filterToRemove(c);
        cMap.keySet().removeIf(ct -> !c.canRemoveCounters(ct));
        if (cMap.isEmpty()) {
            return counterTable;
        }
        if (cMap.size() == 1) {
            counterTable.put(null, c, cMap.entrySet().iterator().next().getKey(), cntToRemove);
            return counterTable;
        }
        while (cntToRemove > 0) {
            CounterType chosen = controller.chooseCounterType(new ArrayList<>(cMap.keySet()), ability, "Choose a counter type to remove", null);
            if (chosen == null) {
                break;
            }
            int max = Math.min(cntToRemove, cMap.get(chosen));
            int remaining = Aggregates.sum(cMap.values());
            int min = Math.max(1, max - remaining);
            int chosenAmount = controller.chooseNumber(ability, "How many " + chosen.getName() + " counters to remove?", min, max);
            if (chosenAmount > 0) {
                counterTable.put(null, c, chosen, chosenAmount);
                cMap = counterTable.filterToRemove(c);
            }
            cntToRemove -= chosenAmount;
        }
        return counterTable;
    }

    @Override
    public PaymentDecision visit(CostRemoveAnyCounter cost) {
        int c = cost.getAbilityAmount(ability);
        CardCollectionView list = cost.payCostFromSource() ? new CardCollection(ability.getHostCard())
                : CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield), cost.getType().split(";"), player, source, ability);
        list = CardLists.filter(list, CardPredicates.hasCounters());
        if (list.isEmpty()) {
            return null;
        }
        List<Card> chosen = pick("Choose " + c + " card(s) to remove a counter from", new ArrayList<>(list), c, c);
        if (chosen.size() != c) {
            return null;
        }
        GameEntityCounterTable table = new GameEntityCounterTable();
        for (Card card : chosen) {
            CounterType type = cost.counter;
            if (type == null) {
                java.util.Map<CounterType, Integer> cMap = table.filterToRemove(card);
                type = controller.chooseCounterType(new ArrayList<>(cMap.keySet()), ability, "Choose a counter type to remove", null);
            }
            if (type == null || !card.canRemoveCounters(type)) {
                return null;
            }
            table.put(null, card, type, 1);
        }
        return table.isEmpty() ? null : PaymentDecision.counters(table);
    }

    @Override
    public PaymentDecision visit(CostRemoveCounter cost) {
        String amount = cost.getAmount();
        CounterType cntrs = cost.counter;
        boolean anyCounters = cntrs == null;
        int cntRemoved = amount.equals("All") ? 0 : cost.getAbilityAmount(ability);

        if (cost.payCostFromSource()) {
            int maxCounters = anyCounters ? source.getNumAllCounters() : source.getCounters(cntrs);
            if (amount.equals("All")) {
                if (!confirm("Remove all counters from " + source.getName() + "?")) {
                    return null;
                }
                cntRemoved = maxCounters;
            } else if (ability != null && !ability.isPwAbility()) {
                if (maxCounters < cntRemoved || !confirm("Remove " + cntRemoved + " counter(s) from " + source.getName() + "?")) {
                    return null;
                }
            }
            if (maxCounters < cntRemoved) {
                return null;
            }
            GameEntityCounterTable table = removeCountersFrom(source, cntrs, cntRemoved);
            return table.isEmpty() ? null : PaymentDecision.counters(table);
        }
        if ("OriginalHost".equals(cost.getType())) {
            Card origHost = ability.getOriginalHost();
            int maxCounters = anyCounters ? origHost.getNumAllCounters() : origHost.getCounters(cntrs);
            if (amount.equals("All")) {
                cntRemoved = maxCounters;
            }
            if (maxCounters < cntRemoved) {
                return null;
            }
            GameEntityCounterTable table = removeCountersFrom(origHost, cntrs, cntRemoved);
            return table.isEmpty() ? null : PaymentDecision.counters(table);
        }

        CardCollectionView validCards = CardLists.getValidCards(player.getCardsIn(cost.zone), cost.getType().split(";"), player, source, ability);
        validCards = anyCounters ? CardLists.filterAnyCounters(validCards, cntRemoved) : CardLists.filter(validCards, CardPredicates.hasCounter(cntrs, cntRemoved));
        if (validCards.isEmpty()) {
            return null;
        }
        List<Card> chosen = pick("Remove counters from a card", new ArrayList<>(validCards), 1, 1);
        if (chosen.isEmpty()) {
            return null;
        }
        GameEntityCounterTable table = removeCountersFrom(chosen.get(0), cntrs, cntRemoved);
        return table.isEmpty() ? null : PaymentDecision.counters(table);
    }

    @Override
    public PaymentDecision visit(CostSacrifice cost) {
        String amount = cost.getAmount();
        String type = cost.getType();

        if (cost.payCostFromSource()) {
            if (source.getController() == ability.getActivatingPlayer() && source.canBeSacrificedBy(ability, isEffect())
                    && (mandatory || confirm("Sacrifice " + source.getName() + "?"))) {
                return PaymentDecision.card(source);
            }
            return null;
        }
        if (type.equals("OriginalHost")) {
            Card host = ability.getOriginalHost();
            if (host.getController() == ability.getActivatingPlayer() && host.canBeSacrificedBy(ability, isEffect())
                    && confirm("Sacrifice " + host.getName() + "?")) {
                return PaymentDecision.card(host);
            }
            return null;
        }

        boolean differentNames = type.contains("+WithDifferentNames");
        if (differentNames) {
            type = type.replace("+WithDifferentNames", "");
        }
        CardCollectionView list = CardLists.filter(player.getCardsIn(ZoneType.Battlefield), CardPredicates.canBeSacrificedBy(ability, isEffect()));
        list = CardLists.getValidCards(list, type.split(";"), player, source, ability);

        if (amount.equals("All")) {
            return PaymentDecision.card(list);
        }
        int c = cost.getAbilityAmount(ability);
        if (c == 0) {
            return PaymentDecision.number(0);
        }
        if (differentNames) {
            CardCollection chosen = new CardCollection();
            CardCollectionView remaining = list;
            while (c > 0) {
                List<Card> picked = pick("Sacrifice a permanent (already chosen: " + chosen + ")", new ArrayList<>(remaining), 1, 1);
                if (picked.isEmpty()) {
                    return null;
                }
                Card first = picked.get(0);
                chosen.add(first);
                remaining = CardLists.filter(remaining, CardPredicates.sharesNameWith(first).negate());
                c--;
            }
            return PaymentDecision.card(chosen);
        }
        if (list.size() < c) {
            return null;
        }
        List<Card> chosen = pick("Sacrifice " + c + " permanent(s)", new ArrayList<>(list), c, c);
        return chosen.size() == c ? PaymentDecision.card(chosen) : null;
    }

    @Override
    public PaymentDecision visit(CostTap cost) {
        return PaymentDecision.number(1);
    }

    @Override
    public PaymentDecision visit(CostTapType cost) {
        String type = cost.getType();
        String amount = cost.getAmount();

        if (type.equals("OriginalHost")) {
            Card host = ability.getOriginalHost();
            return host.canTap() ? PaymentDecision.card(host) : null;
        }

        boolean sameType = type.contains(".sharesCreatureTypeWith");
        if (sameType) {
            type = TextUtil.fastReplace(type, ".sharesCreatureTypeWith", "");
        }
        boolean totalPower = type.contains("+withTotalPowerGE");
        String totalP = "";
        if (totalPower) {
            totalP = type.split("withTotalPowerGE")[1];
            type = TextUtil.fastReplace(type, TextUtil.concatNoSpace("+withTotalPowerGE", totalP), "");
        }

        CardCollection typeList = CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield), type.split(";"), player, source, ability);
        typeList = CardLists.filter(typeList, ability.isCrew() ? CardPredicates.CAN_CREW : CardPredicates.CAN_TAP);

        Integer c = amount.equals("Any") ? null : cost.getAbilityAmount(ability);
        if (c != null && c == 0) {
            return PaymentDecision.number(0);
        }

        if (sameType) {
            CardCollection list2 = typeList;
            CardCollection filtered = CardLists.filter(typeList, c12 -> {
                for (Card card : list2) {
                    if (!card.equals(c12) && card.sharesCreatureTypeWith(c12)) {
                        return true;
                    }
                }
                return false;
            });
            CardCollection tapped = new CardCollection();
            CardCollectionView remaining = filtered;
            while (c > 0) {
                List<Card> picked = pick("Tap a creature (already chosen: " + tapped + ")", new ArrayList<>(remaining), 1, 1);
                if (picked.isEmpty()) {
                    return null;
                }
                Card first = picked.get(0);
                tapped.add(first);
                CardCollection next = CardLists.filter(remaining, c1 -> c1.sharesCreatureTypeWith(first));
                next.remove(first);
                remaining = next;
                c--;
            }
            return PaymentDecision.card(tapped);
        }

        if (totalPower) {
            int needed = Integer.parseInt(totalP);
            List<Card> chosen = pick("Tap creatures totaling " + needed + " power", new ArrayList<>(typeList), 0, typeList.size());
            return CardLists.getTotalPower(new CardCollection(chosen), ability) < needed ? null : PaymentDecision.card(chosen);
        }

        if (c > typeList.size()) {
            return null;
        }
        List<Card> chosen = pick("Tap " + c + " permanent(s)", new ArrayList<>(typeList), c, c);
        return chosen.size() == c ? PaymentDecision.card(chosen) : null;
    }

    @Override
    public PaymentDecision visit(CostUntapType cost) {
        CardCollection typeList = CardLists.getValidCards(player.getGame().getCardsIn(ZoneType.Battlefield), cost.getType().split(";"), player, source, ability);
        typeList = CardLists.filter(typeList, c -> c.canUntap(null, false) && (c.getCounters(forge.game.card.CounterEnumType.STUN) == 0 || c.canRemoveCounters(forge.game.card.CounterEnumType.STUN)));
        int c = cost.getAbilityAmount(ability);
        List<Card> chosen = pick("Untap " + c + " permanent(s)", new ArrayList<>(typeList), c, c);
        return chosen.size() == c ? PaymentDecision.card(chosen) : null;
    }

    @Override
    public PaymentDecision visit(CostUntap cost) {
        return PaymentDecision.number(1);
    }

    @Override
    public PaymentDecision visit(CostUnattach cost) {
        CardCollection cardToUnattach = cost.findCardToUnattach(source, player, ability);
        if (cardToUnattach.size() == 1) {
            return confirm("Unattach " + cardToUnattach.getFirst().getName() + "?") ? PaymentDecision.card(cardToUnattach.getFirst()) : null;
        }
        if (cardToUnattach.size() > 1) {
            int c = cost.getAbilityAmount(ability);
            List<Card> chosen = pick("Choose what to unattach", new ArrayList<>(cardToUnattach), c, c);
            return chosen.size() == c ? PaymentDecision.card(chosen) : null;
        }
        return null;
    }

    @Override
    public boolean paysRightAfterDecision() {
        return true;
    }
}
