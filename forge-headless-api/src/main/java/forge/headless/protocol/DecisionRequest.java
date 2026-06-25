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
