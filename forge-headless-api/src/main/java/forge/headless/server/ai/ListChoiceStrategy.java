package forge.headless.server.ai;

import forge.headless.protocol.DecisionRequest;

import java.util.List;

/** Covers CHOOSE_LIST - generic multi-select prompts like discard,
 * sacrifice, surveil, and search results. */
public interface ListChoiceStrategy {
    /** listIntent is "WORST" when this player is about to lose whichever
     * items get chosen (discard/sacrifice/destroy - see
     * DecisionRequest.listIntent), null for every other use (surveil,
     * mode choice, search-keep, ...), which fall back to the existing
     * "take the minimum required" default. */
    List<String> chooseFromList(List<DecisionRequest.Option> options, Integer min, String listIntent);
}
