package forge.headless.server.ai;

import forge.headless.protocol.DecisionRequest;

import java.util.List;

/** Covers CHOOSE_LIST - generic multi-select prompts like discard,
 * sacrifice, surveil, and search results. */
public interface ListChoiceStrategy {
    List<String> chooseFromList(List<DecisionRequest.Option> options, Integer min);
}
