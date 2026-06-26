package forge.headless.server.ai;

import forge.headless.protocol.CardStateView;
import forge.headless.protocol.DecisionRequest;
import forge.headless.protocol.GameStateView;
import forge.headless.protocol.PlayerStateView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Four pieces of combat math instead of "attack with anything that has
 * power > 0": a lethal-line check (go all-in when the numbers say this
 * attack can just win outright), a posture read (am I ahead, behind, or
 * even - see Posture), crackback-aware blocker reservation (don't tap out
 * into a counter-attack that kills me back), and per-creature safe/
 * favorable filtering on whatever's left to send. Magic has no hidden
 * battlefield information - every opponent's creatures, power, toughness,
 * and keywords are fully known at declare-attackers time - so all of this
 * is tractable straight from CardStateView, no guessing required.
 *
 * Modeled on forge-ai's AiAttackController, specifically aiAggression
 * (AiAttackController.java:1232-1264, a 0-5 posture read from force ratio
 * and life totals) and notNeededAsBlockers (AiAttackController.java:355,
 * which holds back exactly enough blockers to survive the opponent's next
 * combat via a full block simulation - predictNextCombatsRemainingLife).
 * Without either of these, the previous version of this class would
 * happily tap the whole board into attacks and then lose to the
 * counter-swing - very likely the single biggest leak in this AI's win
 * rate against forge-ai (see PLAN.md Phase 4.28's audit).
 *
 * Deliberately a lighter-weight version of their idea, not a port of the
 * mechanism: forge-ai actually simulates a full hypothetical combat
 * (builds a Combat object, runs its own AiBlockController against it) to
 * predict the crackback. We don't have a real Combat object to simulate
 * against here (CardStateView is a flat DTO, not a live engine state), so
 * the crackback check instead reuses this class's own existing
 * isLethal() math, mirrored: "would the opponent's current board, against
 * whichever of my creatures stay home to block, deal lethal-or-more
 * damage to me next turn" - the same conservative guaranteed-unblocked +
 * smallest-excess-through accounting already used for our own offense,
 * just pointed in the other direction. Real combat tricks, instant-speed
 * removal, and the opponent's own future draws aren't modeled - this is a
 * snapshot of the current board, same caveat as the rest of this class.
 *
 * Posture also isn't forge-ai's 6-level aiAggression with its many
 * matchup-specific conditions - just three buckets (DEFENSIVE/NORMAL/
 * DESPERATE) from a board-value and life comparison, since that's the
 * part of their model that most directly changes "how much should I risk
 * by attacking", without trying to replicate every situational rule they
 * have for it.
 */
public class GenericAttackStrategy implements AttackStrategy {
    private enum Posture {
        /** Clearly ahead on board and life - no need to gamble, so the
         * crackback check gets an extra safety margin rather than just
         * "don't literally die". */
        DEFENSIVE,
        /** Even, or no clear edge either way - hold back exactly enough
         * to not die to the crackback, same as forge-ai's baseline case. */
        NORMAL,
        /** Clearly behind on board and life - holding creatures back to
         * "stay safe" doesn't matter if the slow game is already lost,
         * so the crackback check is skipped entirely and everything
         * that can profitably attack does. */
        DESPERATE
    }

    @Override
    public List<String> chooseAttackers(List<DecisionRequest.Option> options, GameStateView state, String defenderName) {
        Map<String, CardStateView> byId = AiUtils.cardsById(state);
        PlayerStateView me = AiUtils.you(state);
        PlayerStateView defender = findPlayer(state, defenderName);

        if (defender == null || me == null) {
            // No resolvable defender/self info - fall back to the
            // original simple rule rather than guessing at combat math blind.
            List<String> chosen = new ArrayList<>();
            for (DecisionRequest.Option o : options) {
                CardStateView card = o.cardId != null ? byId.get(o.cardId) : null;
                if (card == null || (card.power != null ? card.power : 0) > 0) {
                    chosen.add(o.id);
                }
            }
            return chosen;
        }

        List<CardStateView> blockers = new ArrayList<>();
        if (defender.battlefield != null) {
            for (CardStateView c : defender.battlefield) {
                if (!c.tapped && c.typeLine != null && c.typeLine.contains("Creature")) {
                    blockers.add(c);
                }
            }
        }

        List<CardStateView> attackers = new ArrayList<>();
        for (DecisionRequest.Option o : options) {
            CardStateView card = o.cardId != null ? byId.get(o.cardId) : null;
            if (card != null && (card.power == null || card.power <= 0)) {
                continue;
            }
            attackers.add(card);
        }

        if (isLethal(attackers, blockers, defender.life)) {
            return options.stream().map(o -> o.id).toList();
        }

        Posture posture = readPosture(me, defender);

        // The opponent's *full* creature count, not just their currently
        // untapped ones - everything they have untaps again on their own
        // turn, so a tapped creature today is still a real attacker in
        // next turn's crackback.
        List<CardStateView> opponentFullBoard = new ArrayList<>();
        if (defender.battlefield != null) {
            for (CardStateView c : defender.battlefield) {
                if (c.typeLine != null && c.typeLine.contains("Creature")) {
                    opponentFullBoard.add(c);
                }
            }
        }

        // Everything else on my board that ISN'T attacking is a
        // potential blocker against the crackback. Vigilance creatures
        // are excluded from "things I might need to hold back" entirely -
        // they attack AND still block, so committing one to attack never
        // costs any blocking capacity.
        List<CardStateView> myBoard = me.battlefield != null ? me.battlefield : List.of();
        List<CardStateView> potentialHomeDefense = new ArrayList<>();
        for (CardStateView c : myBoard) {
            if (c.typeLine != null && c.typeLine.contains("Creature") && !c.tapped
                    && !hasKeyword(c.keywords, "Vigilance")) {
                potentialHomeDefense.add(c);
            }
        }

        // Greedily decide, in attack-value order, which non-vigilant
        // attack candidates can be spared without losing the crackback
        // check - the same idea as forge-ai's notNeededAsBlockers, just
        // using this class's own isLethal() math mirrored at the
        // opponent rather than a full block simulation.
        List<DecisionRequest.Option> candidates = new ArrayList<>();
        for (DecisionRequest.Option o : options) {
            CardStateView card = o.cardId != null ? byId.get(o.cardId) : null;
            if (card == null || (card.power != null ? card.power : 0) <= 0) {
                continue;
            }
            candidates.add(o);
        }
        candidates.sort(Comparator.comparingDouble((DecisionRequest.Option o) -> CreatureValue.of(byId.get(o.cardId))).reversed());

        List<CardStateView> stillHome = new ArrayList<>(potentialHomeDefense);
        List<DecisionRequest.Option> sparedToAttack = new ArrayList<>();
        for (DecisionRequest.Option o : candidates) {
            CardStateView card = byId.get(o.cardId);
            if (hasKeyword(card.keywords, "Vigilance")) {
                sparedToAttack.add(o); // doesn't cost any home defense at all
                continue;
            }
            if (posture == Posture.DESPERATE || !stillHome.contains(card)) {
                // DESPERATE: holding back doesn't matter if the slow game
                // is already lost. Not in stillHome: a non-creature-typed
                // or already-filtered candidate never counted as home
                // defense in the first place, so sending it costs nothing.
                sparedToAttack.add(o);
                continue;
            }
            List<CardStateView> withoutThisOne = new ArrayList<>(stillHome);
            withoutThisOne.remove(card);
            if (survivesCrackback(opponentFullBoard, withoutThisOne, me.life, posture)) {
                stillHome.remove(card);
                sparedToAttack.add(o);
            }
            // else: held back, this creature stays home to block.
        }

        List<DecisionRequest.Option> safe = new ArrayList<>();
        List<DecisionRequest.Option> risky = new ArrayList<>();
        for (DecisionRequest.Option o : sparedToAttack) {
            CardStateView card = byId.get(o.cardId);
            (isSafeOrFavorable(card, blockers) ? safe : risky).add(o);
        }

        List<String> chosen = new ArrayList<>();
        for (DecisionRequest.Option o : safe) {
            chosen.add(o.id);
        }
        // The defender has at most blockers.size() total blocks to hand
        // out. If I send more "risky" attackers (ones SOME blocker could
        // cleanly kill) than they have blockers, they literally can't
        // assign a killer to every one of them - some are guaranteed
        // through no matter which ones they pick, so it's worth sending
        // all of them. If risky.size() <= blockers.size() they can
        // profitably kill every single one for free, so send none.
        // Deliberately a hard threshold rather than partial commitment -
        // we don't know which specific attackers the defender will
        // choose to block, so committing fewer than all risky attackers
        // risks the defender killing exactly those and realizing none of
        // the guaranteed-through benefit.
        if (!risky.isEmpty() && risky.size() > blockers.size()) {
            for (DecisionRequest.Option o : risky) {
                chosen.add(o.id);
            }
        }
        return chosen;
    }

    /** Board value + life comparison against the specific defender being
     * attacked - three buckets rather than forge-ai's 6-level aiAggression,
     * capturing just "how much should I risk by attacking" without their
     * many situational modifiers.
     *
     * Two attempts before this one got the calibration wrong, both
     * verified live and caught before being trusted: a flat value
     * difference ("boardAdvantage >= 5") was swamped by
     * CombatKeywords' richer per-keyword scoring (a single well-
     * keyworded creature can swing raw value by 10-25 on its own), so
     * almost any early-game asymmetry tripped an extreme posture
     * immediately - 36/37 sampled decisions landed in DEFENSIVE/
     * DESPERATE, NORMAL (where the crackback check does its actual job)
     * almost never reached. Switching to a ratio alone didn't fix it
     * either - with the opponent's board still empty or nearly so
     * (extremely common early on, since someone always plays a creature
     * first), literally any single creature of mine produces a "ratio"
     * so large it clears any reasonable threshold on its own.
     *
     * Fix: require board AND life to both clearly agree, not just
     * whichever one is easiest to satisfy - and require some real board
     * presence on both sides before the ratio means anything at all
     * (a 1-creature vs 0-creature "ratio" isn't a real signal, it's just
     * turn order). NORMAL is the default and should be the common case;
     * the extremes are for genuinely lopsided positions, not any slight
     * lead. */
    private Posture readPosture(PlayerStateView me, PlayerStateView defender) {
        double myBoardValue = 0;
        if (me.battlefield != null) {
            for (CardStateView c : me.battlefield) {
                if (c.typeLine != null && c.typeLine.contains("Creature")) {
                    myBoardValue += CreatureValue.of(c);
                }
            }
        }
        double theirBoardValue = 0;
        if (defender.battlefield != null) {
            for (CardStateView c : defender.battlefield) {
                if (c.typeLine != null && c.typeLine.contains("Creature")) {
                    theirBoardValue += CreatureValue.of(c);
                }
            }
        }
        int lifeAdvantage = me.life - defender.life;

        // Need a real opposing board before "ahead on board" means
        // anything - otherwise this is just turn-order noise, not a
        // genuine advantage worth playing safe over.
        boolean clearlyAheadOnBoard = theirBoardValue >= 3 && myBoardValue >= theirBoardValue * 1.8;
        boolean clearlyBehindOnBoard = myBoardValue >= 3 && theirBoardValue >= myBoardValue * 1.8;

        if (clearlyAheadOnBoard && lifeAdvantage >= 5) {
            return Posture.DEFENSIVE;
        }
        if (clearlyBehindOnBoard && lifeAdvantage <= 0 || lifeAdvantage <= -15) {
            return Posture.DESPERATE;
        }
        return Posture.NORMAL;
    }

    /** Mirrors isLethal() at the opponent: would their full creature
     * board, attacking next turn against only homeDefenders as blockers,
     * deal lethal-or-more damage to me. DEFENSIVE posture asks for a
     * safety margin instead of an exact "not literally lethal" threshold,
     * since there's no need to cut it close when already ahead. Caller
     * already short-circuits DESPERATE before reaching here. */
    private boolean survivesCrackback(List<CardStateView> opponentCreatures, List<CardStateView> homeDefenders,
            int myLife, Posture posture) {
        double margin = posture == Posture.DEFENSIVE ? Math.max(5, myLife * 0.25) : 0;
        return !isLethal(opponentCreatures, homeDefenders, myLife - (int) margin);
    }

    /** Conservative: sums damage that's guaranteed through (evasive
     * attackers the defender literally cannot block) plus, if I have
     * more "blockable" attackers than they have blockers, the smallest
     * excess attackers' power (a defender blocking optimally would let
     * the least damage through, not the most). */
    private boolean isLethal(List<CardStateView> attackers, List<CardStateView> blockers, int defenderLife) {
        double guaranteed = 0;
        List<CardStateView> groundAttackers = new ArrayList<>();
        for (CardStateView a : attackers) {
            if (isGuaranteedUnblocked(a, blockers)) {
                guaranteed += a.power != null ? a.power : 0;
            } else {
                groundAttackers.add(a);
            }
        }
        int excess = groundAttackers.size() - blockers.size();
        if (excess > 0) {
            groundAttackers.sort(Comparator.comparingInt(c -> c.power != null ? c.power : 0));
            for (int i = 0; i < excess; i++) {
                guaranteed += groundAttackers.get(i).power != null ? groundAttackers.get(i).power : 0;
            }
        }
        return guaranteed >= defenderLife;
    }

    private boolean isGuaranteedUnblocked(CardStateView attacker, List<CardStateView> blockers) {
        if (hasKeyword(attacker.keywords, "Unblockable")) {
            return true;
        }
        if (hasKeyword(attacker.keywords, "Flying")) {
            return blockers.stream().noneMatch(b -> hasKeyword(b.keywords, "Flying") || hasKeyword(b.keywords, "Reach"));
        }
        if (hasKeyword(attacker.keywords, "Menace")) {
            return blockers.size() < 2;
        }
        return false;
    }

    /** An attack is unsafe only if the defender has a clean answer: a
     * blocker that kills this attacker without dying itself. Everything
     * else (no blockers, a trade, a block that doesn't kill mine, mutual
     * destruction) is fine to attack into - "favorable or neutral", not
     * just strictly-winning trades.
     *
     * First/double strike (CombatMath, shared with GenericBlockStrategy's
     * identical concern on the blocking side - forge-ai audit Tier 2 #4)
     * is checked before falling back to simultaneous-damage math: a
     * blocker my attacker kills before it can ever swing back is no
     * threat at all regardless of its raw power, and a blocker that kills
     * my attacker first is guaranteed to survive regardless of its raw
     * toughness - both are cases simultaneous math gets wrong. */
    private boolean isSafeOrFavorable(CardStateView attacker, List<CardStateView> blockers) {
        if (isGuaranteedUnblocked(attacker, blockers) || blockers.isEmpty()) {
            return true;
        }
        boolean iHaveDeathtouch = hasKeyword(attacker.keywords, "Deathtouch");
        int myPower = attacker.power != null ? attacker.power : 0;
        int myToughness = attacker.toughness != null ? attacker.toughness : 0;
        for (CardStateView b : blockers) {
            int bPower = b.power != null ? b.power : 0;
            int bToughness = b.toughness != null ? b.toughness : 0;
            boolean bDeathtouch = hasKeyword(b.keywords, "Deathtouch");

            if (CombatMath.diesWithoutStriking(myPower, iHaveDeathtouch, attacker.keywords, bToughness, b.keywords)) {
                continue; // I kill this blocker before it can ever hit back - not a threat
            }
            if (CombatMath.diesWithoutStriking(bPower, bDeathtouch, b.keywords, myToughness, attacker.keywords)) {
                return false; // this blocker kills me before I can hit back - guaranteed unsafe
            }
            boolean blockerKillsMine = bPower >= myToughness || (bDeathtouch && bPower > 0);
            // Deathtouch on MY side means any damage I deal is lethal, so
            // the blocker never "survives" blocking me regardless of
            // toughness - at worst this is a trade, never a clean loss.
            boolean blockerSurvives = !iHaveDeathtouch && bToughness > myPower;
            if (blockerKillsMine && blockerSurvives) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasKeyword(List<String> keywords, String name) {
        if (keywords == null) {
            return false;
        }
        for (String k : keywords) {
            if (k.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private PlayerStateView findPlayer(GameStateView state, String name) {
        if (state == null || state.players == null || name == null) {
            return null;
        }
        for (PlayerStateView p : state.players) {
            if (name.equals(p.name)) {
                return p;
            }
        }
        return null;
    }
}
