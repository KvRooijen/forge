package forge.headless.protocol;

import java.util.List;

/**
 * A question the engine needs answered before it can continue. Sent to a
 * human seat over WebSocket or to an AI seat over HTTP - same shape either
 * way.
 */
public class DecisionRequest {
    public String id;
    public String type;
    public String prompt;
    public List<Option> options;
    public GameStateView state;
    /** Only meaningful for CHOOSE_LIST: how many of `options` must/may be picked. */
    public Integer min;
    public Integer max;
    /** Only set for MULLIGAN_KEEP: how many cards this mulligan round would
     * tuck back if not kept (0 on the very first, never-mulliganed hand,
     * growing with each subsequent mulligan under the London rule) - lets
     * the mulligan strategy lower its standards as it digs, rather than
     * applying the same bar to a fresh 7 and an already-mulliganed-several-
     * times hand alike. */
    public Integer mulliganCardsToReturn;
    /** Only meaningful for DECLARE_BLOCKERS: one group per attacker, so the
     * whole combat can be shown and answered in a single request instead of
     * one CHOOSE_LIST per attacker in sequence. */
    public List<Group> groups;
    /** Only set for CHOOSE_LIST requests that are specifically choosing a
     * target for a spell/ability ("HARMFUL"/"BENEFICIAL"), and only when
     * that classification is high-confidence (see
     * RemotePlayerController.classifyTargetIntent) - null for every other
     * CHOOSE_LIST use (discard, sacrifice, surveil, mode choice, ...),
     * which need different logic (e.g. "worst card", not "most
     * threatening") and shouldn't be treated as a targeting decision. */
    public String targetIntent;
    /** Only set for CHOOSE_LIST requests where the *direction* of "good
     * choice" is known with confidence and isn't a targeting decision -
     * currently only "WORST" (discard/sacrifice/destroy: this player is
     * about to lose whichever items are chosen, so the least valuable
     * ones should be picked). A deliberately separate field from
     * targetIntent even though both only ever apply to CHOOSE_LIST - the
     * two represent different decisions (who an effect should hit, vs.
     * which of my own things I'd rather give up) and conflating them
     * under one field would risk a discard prompt being misrouted into
     * targeting logic expecting "HARMFUL"/"BENEFICIAL". Null for every
     * other CHOOSE_LIST use (surveil, mode choice, dig/search-keep, ...),
     * which fall back to the existing "take the minimum required"
     * default - "keep the best" search semantics aren't classified here,
     * a different, riskier direction this round deliberately didn't
     * attempt. */
    public String listIntent;
    /** Only meaningful for DECLARE_ATTACKERS: the name of whichever
     * player this combat is actually against, resolved from the
     * defender even when it's a planeswalker/battle rather than the
     * player directly (their life total is still what attacking *them*
     * threatens) - lets a strategy reason about a specific opponent's
     * board/life instead of guessing which PlayerStateView is relevant. */
    public String defenderName;

    public DecisionRequest() { }

    public DecisionRequest(String id, String type, String prompt, List<Option> options) {
        this.id = id;
        this.type = type;
        this.prompt = prompt;
        this.options = options;
    }

    public DecisionRequest(String id, String type, String prompt, List<Option> options, GameStateView state) {
        this.id = id;
        this.type = type;
        this.prompt = prompt;
        this.options = options;
        this.state = state;
    }

    public static class Option {
        public String id;
        public String label;
        public String cardId;
        /** Full card data for rendering a real card preview instead of just
         * the text label - set even for cards outside the normal public
         * board state (e.g. scry/surveil/dig candidates sitting in the
         * library), since the player legitimately sees them at decision
         * time even though we don't otherwise serialize that zone. */
        public CardStateView card;
        /** Only set on CHOOSE_SPELL_ABILITY options: a coarse,
         * high-confidence classification of what casting this spell does
         * ("REMOVAL"/"SWEEPER"/"DRAW"/"RAMP"/"CREATURE"), derived from the
         * SpellAbility's engine ApiType (see
         * RemotePlayerController.classifySpellRole). Lets the AI value a
         * spell by what it accomplishes against the current board rather
         * than treating every non-creature as worth its mana cost. Null
         * whenever the effect's value can't be classified with confidence
         * - the AI falls back to a CMC proxy for those, same as before. */
        public String spellRole;

        public Option() { }

        public Option(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public Option(String id, String label, String cardId) {
            this.id = id;
            this.label = label;
            this.cardId = cardId;
        }

        public Option(String id, String label, String cardId, CardStateView card) {
            this.id = id;
            this.label = label;
            this.cardId = cardId;
            this.card = card;
        }
    }

    /** One attacker's worth of blocker candidates, within a DECLARE_BLOCKERS
     * request that covers the whole combat in one round trip. */
    public static class Group {
        public String id;
        public String prompt;
        /** The attacking creature itself, so the frontend can show what's
         * being blocked, not just a text label. */
        public CardStateView attacker;
        public List<Option> options;
        public int min;
        public int max;

        public Group() { }

        public Group(String id, String prompt, CardStateView attacker, List<Option> options, int min, int max) {
            this.id = id;
            this.prompt = prompt;
            this.attacker = attacker;
            this.options = options;
            this.min = min;
            this.max = max;
        }
    }
}
