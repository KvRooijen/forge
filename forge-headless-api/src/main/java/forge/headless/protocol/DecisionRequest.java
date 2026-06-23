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
}
