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

        public Option() { }

        public Option(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }
}
