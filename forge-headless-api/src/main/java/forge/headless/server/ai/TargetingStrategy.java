package forge.headless.server.ai;

import forge.headless.protocol.DecisionRequest;

import java.util.List;

/** Covers CHOOSE_LIST requests carrying a non-null targetIntent
 * ("HARMFUL"/"BENEFICIAL") - choosing a target for a spell/ability, as
 * opposed to every other CHOOSE_LIST use (discard, sacrifice, surveil,
 * mode choice, ...), which RuleBasedAiChannel keeps routing to
 * ListChoiceStrategy instead. */
public interface TargetingStrategy {
    List<String> chooseTarget(List<DecisionRequest.Option> options, String targetIntent, Integer min);
}
