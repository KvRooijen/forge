package forge.headless.server.ai;

import forge.headless.protocol.DecisionRequest;
import forge.headless.protocol.GameStateView;

import java.util.List;
import java.util.Map;

public interface BlockStrategy {
    /** Key = attacker group id, value = the blocker option ids assigned
     * to that attacker. A group with no entry (or an empty list) means
     * "don't block this attacker". The same blocker option id must not
     * appear under more than one group - a single creature can only
     * block one attacker. */
    Map<String, List<String>> chooseBlocks(List<DecisionRequest.Group> groups, GameStateView state);
}
